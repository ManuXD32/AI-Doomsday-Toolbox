/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Small Android-native process broker for the Agent's Debian PRoot runtime.  It deliberately
 * has no filesystem policy of its own: the Kotlin executor clamps mounts and commands, while
 * this process owns the child process group, resource limits, owner-process liveness check, and
 * bounded cancellation. Harness launches opt into a graceful child-only/no-auto-kill mode; the
 * legacy default remains TERM -> KILL for callers that do not provide those options.
 */

#define _GNU_SOURCE
#include <ctype.h>
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t g_stop_requested = 0;
static volatile sig_atomic_t g_force_requested = 0;
static pid_t g_child_pid = -1;
static int g_term_grace_ms = 3000;
static bool g_no_force_on_timeout = false;
static bool g_term_child_only = false;
static const char *g_child_pid_file = NULL;
static const char *g_process_ledger_file = NULL;

#define MAX_LEDGER_PROCESSES 256
#define LEDGER_REFRESH_MS 100
#define OWNER_REFRESH_MS 100
#define OWNER_MISSES_BEFORE_FORCE 3

/*
 * PR_SET_PDEATHSIG is deliberately not used for the Android app owner. Linux can associate the
 * parent of a forked process with the launching thread, and the parent-death signal is then
 * delivered when that thread retires even though the app's process is still alive. The broker
 * instead records the app process PID and /proc start time and checks that identity while it
 * supervises the PRoot child. The child keeps its own PDEATHSIG so a real broker death still
 * tears down the immediate PRoot process.
 */
static pid_t g_owner_pid = -1;
static long long g_owner_start_ticks = -1;

static void request_stop(int signal_number) {
    if (signal_number == SIGINT) {
        g_force_requested = 1;
    } else {
        g_stop_requested = 1;
    }
}

static int parse_positive(const char *value, long long minimum, long long maximum, long long *out) {
    char *end = NULL;
    errno = 0;
    long long parsed = strtoll(value, &end, 10);
    if (errno != 0 || end == value || *end != '\0' || parsed < minimum || parsed > maximum) {
        return -1;
    }
    *out = parsed;
    return 0;
}

static int apply_limit(int resource, rlim_t requested) {
    struct rlimit current;
    if (getrlimit(resource, &current) != 0) return -1;
    if (current.rlim_max != RLIM_INFINITY && requested > current.rlim_max) {
        requested = current.rlim_max;
    }
    struct rlimit limit = {requested, requested};
    return setrlimit(resource, &limit);
}

static void signal_process_group(int signal_number) {
    if (g_child_pid <= 0) return;
    /* The child creates its own group before exec. Send to both the group and pid because a
       child may exit between the two calls and a malformed launcher may not create a group. */
    (void) kill(-g_child_pid, signal_number);
    (void) kill(g_child_pid, signal_number);
}

static void signal_term_target(int signal_number) {
    if (g_term_child_only) {
        if (g_child_pid > 0) (void) kill(g_child_pid, signal_number);
    } else {
        signal_process_group(signal_number);
    }
}

static pid_t read_parent_pid(pid_t pid) {
    char path[64];
    int written = snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    if (written <= 0 || (size_t) written >= sizeof(path)) return -1;

    FILE *stat_file = fopen(path, "r");
    if (stat_file == NULL) return -1;
    char line[4096];
    if (fgets(line, sizeof(line), stat_file) == NULL) {
        fclose(stat_file);
        return -1;
    }
    fclose(stat_file);

    char *after_name = strrchr(line, ')');
    if (after_name == NULL || after_name[1] != ' ') return -1;
    char state = '\0';
    long parent = -1;
    if (sscanf(after_name + 2, "%c %ld", &state, &parent) != 2 || parent <= 0) return -1;
    return (pid_t) parent;
}

static long long read_start_ticks(pid_t pid) {
    char path[64];
    int written = snprintf(path, sizeof(path), "/proc/%d/stat", pid);
    if (written <= 0 || (size_t) written >= sizeof(path)) return -1;

    FILE *stat_file = fopen(path, "r");
    if (stat_file == NULL) return -1;
    char line[4096];
    if (fgets(line, sizeof(line), stat_file) == NULL) {
        fclose(stat_file);
        return -1;
    }
    fclose(stat_file);

    char *after_name = strrchr(line, ')');
    if (after_name == NULL || after_name[1] != ' ') return -1;
    char *cursor = after_name + 2;
    if (*cursor == '\0') return -1;
    ++cursor; /* state (field 3) */
    while (*cursor == ' ') ++cursor;
    for (int index = 1; index <= 19; ++index) {
        char *end = NULL;
        errno = 0;
        long long value = strtoll(cursor, &end, 10);
        if (errno != 0 || end == cursor) return -1;
        if (index == 19) {
            return value > 0 ? value : -1;
        }
        cursor = end;
        while (*cursor == ' ') ++cursor;
    }
    return -1;
}

static long read_uid(pid_t pid) {
    char path[64];
    int written = snprintf(path, sizeof(path), "/proc/%d/status", pid);
    if (written <= 0 || (size_t) written >= sizeof(path)) return -1;

    FILE *status_file = fopen(path, "r");
    if (status_file == NULL) return -1;
    char line[512];
    long uid = -1;
    while (fgets(line, sizeof(line), status_file) != NULL) {
        if (strncmp(line, "Uid:", 4) == 0) {
            (void) sscanf(line + 4, "%ld", &uid);
            break;
        }
    }
    fclose(status_file);
    return uid;
}

static bool owner_process_alive(void) {
    if (g_owner_pid <= 1) return false;
    if (getppid() != g_owner_pid) return false;
    /* The kernel parent relation is the authoritative lifetime anchor. Start ticks add PID
       reuse protection when procfs is readable, but Android may deny /proc/<pid>/stat to this
       executable. An unreadable optional check must not reject a live, unchanged parent. */
    if (g_owner_start_ticks <= 0) return true;
    long long current_start_ticks = read_start_ticks(g_owner_pid);
    return current_start_ticks <= 0 || current_start_ticks == g_owner_start_ticks;
}

static bool is_numeric_name(const char *name) {
    if (name == NULL || *name == '\0') return false;
    for (const char *cursor = name; *cursor != '\0'; ++cursor) {
        if (!isdigit((unsigned char) *cursor)) return false;
    }
    return true;
}

static bool is_descendant_of(pid_t pid, pid_t root) {
    if (pid <= 0 || root <= 0 || pid == root) return pid == root;
    pid_t current = pid;
    for (int depth = 0; depth < 64; ++depth) {
        current = read_parent_pid(current);
        if (current <= 0 || current == 1) return false;
        if (current == root) return true;
        if (current == getpid()) return false;
    }
    return false;
}

static bool valid_marker(const char *marker) {
    if (marker == NULL || *marker == '\0') return false;
    for (const char *cursor = marker; *cursor != '\0'; ++cursor) {
        if (!(isalnum((unsigned char) *cursor) || *cursor == '_' || *cursor == '.' ||
              *cursor == ':' || *cursor == '-')) {
            return false;
        }
    }
    return true;
}

static int write_process_ledger(void) {
    if (g_process_ledger_file == NULL || g_child_pid <= 0) return 0;
    const char *marker = getenv("ADT_HARNESS_OWNER_MARKER");
    if (!valid_marker(marker)) return -1;

    size_t length = strlen(g_process_ledger_file);
    if (length == 0 || length >= PATH_MAX - 5) return -1;
    char temporary[PATH_MAX];
    int written = snprintf(temporary, sizeof(temporary), "%s.tmp", g_process_ledger_file);
    if (written <= 0 || (size_t) written >= sizeof(temporary)) return -1;

    int descriptor = open(temporary, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (descriptor < 0) return -1;
    (void) fchmod(descriptor, S_IRUSR | S_IWUSR);
    FILE *ledger = fdopen(descriptor, "w");
    if (ledger == NULL) {
        close(descriptor);
        (void) unlink(temporary);
        return -1;
    }

    bool success = fprintf(ledger, "adt-harness-process-ledger-v1\nmarker=%s\n", marker) >= 0;
    int count = 0;
    if (success) {
        long child_uid = read_uid(g_child_pid);
        long long child_start_ticks = read_start_ticks(g_child_pid);
        if (child_uid >= 0 && child_start_ticks > 0 &&
            fprintf(ledger, "process %d %lld %ld\n", g_child_pid, child_start_ticks, child_uid) >= 0) {
            count = 1;
        }
    }
    if (success) {
        DIR *proc = opendir("/proc");
        if (proc == NULL) {
            success = false;
        } else {
            struct dirent *entry;
            while (success && count < MAX_LEDGER_PROCESSES &&
                   (entry = readdir(proc)) != NULL) {
                if (!is_numeric_name(entry->d_name)) continue;
                char *end = NULL;
                long parsed = strtol(entry->d_name, &end, 10);
                if (end == entry->d_name || *end != '\0' || parsed <= 0 || parsed > INT_MAX) {
                    continue;
                }
                pid_t pid = (pid_t) parsed;
                if (pid == getpid() || pid == g_child_pid ||
                    (!is_descendant_of(pid, g_child_pid) && !is_descendant_of(pid, getpid()))) {
                    continue;
                }
                long uid = read_uid(pid);
                long long start_ticks = read_start_ticks(pid);
                if (uid < 0 || start_ticks <= 0) continue;
                if (fprintf(ledger, "process %d %lld %ld\n", pid, start_ticks, uid) < 0) {
                    success = false;
                } else {
                    ++count;
                }
            }
            closedir(proc);
        }
    }
    if (success && fflush(ledger) == 0 && fsync(fileno(ledger)) == 0) {
        if (fclose(ledger) != 0) success = false;
    } else {
        (void) fclose(ledger);
        success = false;
    }
    if (success && rename(temporary, g_process_ledger_file) != 0) success = false;
    if (!success) (void) unlink(temporary);
    return success ? 0 : -1;
}

static bool adopted_children_exist(void) {
    DIR *proc = opendir("/proc");
    /* Treat an unavailable proc view as unknown so cleanup does not claim success early. */
    if (proc == NULL) return true;
    bool found = false;
    struct dirent *entry;
    while ((entry = readdir(proc)) != NULL) {
        if (!is_numeric_name(entry->d_name)) continue;
        char *end = NULL;
        long parsed = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end != '\0' || parsed <= 0 || parsed > INT_MAX) continue;
        pid_t pid = (pid_t) parsed;
        if (pid == getpid() || pid == g_child_pid) continue;
        if (read_parent_pid(pid) == getpid()) {
            found = true;
            break;
        }
    }
    closedir(proc);
    return found;
}

/*
 * PR_SET_CHILD_SUBREAPER makes detached PRoot descendants become children of this broker after
 * their immediate parent exits. They are then distinguishable from unrelated app processes by
 * the broker's own child relationship. This is deliberately bounded and never scans/kills a
 * same-UID process by command name alone.
 */
static void signal_adopted_children(int signal_number) {
    for (int pass = 0; pass < 32; ++pass) {
        DIR *proc = opendir("/proc");
        if (proc == NULL) return;
        bool found = false;
        struct dirent *entry;
        while ((entry = readdir(proc)) != NULL) {
            if (!is_numeric_name(entry->d_name)) continue;
            char *end = NULL;
            long parsed = strtol(entry->d_name, &end, 10);
            if (end == entry->d_name || *end != '\0' || parsed <= 0 || parsed > INT_MAX) continue;
            pid_t pid = (pid_t) parsed;
            if (pid == getpid() || pid == g_child_pid) continue;
            if (read_parent_pid(pid) == getpid()) {
                (void) kill(pid, signal_number);
                found = true;
            }
        }
        closedir(proc);
        if (!found) return;
        usleep((useconds_t) (10 * 1000));
    }
}

static void reap_adopted_children(void) {
    int status = 0;
    while (waitpid(-1, &status, WNOHANG) > 0) {
        /* Reap every child currently adopted by this broker. */
    }
}

static void terminate_adopted_children(bool force) {
    if (force) {
        signal_adopted_children(SIGKILL);
        reap_adopted_children();
        return;
    }
    /* In Harness graceful mode Kotlin owns the deadline and will send SIGINT if Force is
       requested. Do not turn a broker-side grace expiry into an implicit Force. A natural child
       exit keeps the legacy bounded cleanup below because no user graceful request is pending. */
    const bool preserve_graceful_tree = g_no_force_on_timeout && g_stop_requested;
    signal_adopted_children(SIGTERM);
    const int poll_us = 10 * 1000;
    int elapsed_ms = 0;
    while (!g_force_requested && (preserve_graceful_tree || elapsed_ms < g_term_grace_ms)) {
        reap_adopted_children();
        if (!adopted_children_exist()) return;
        usleep((useconds_t) poll_us);
        elapsed_ms += poll_us / 1000;
    }
    if (g_force_requested || !preserve_graceful_tree) {
        signal_adopted_children(SIGKILL);
        reap_adopted_children();
    }
}

static int write_child_pid_file(void) {
    if (g_child_pid_file == NULL) return 0;
    size_t length = strlen(g_child_pid_file);
    if (length == 0 || length >= PATH_MAX) return -1;
    int descriptor = open(g_child_pid_file, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (descriptor < 0) return -1;
    char value[32];
    int written = snprintf(value, sizeof(value), "%d\n", g_child_pid);
    bool success = written > 0 && (size_t) written < sizeof(value) &&
        write(descriptor, value, (size_t) written) == (ssize_t) written;
    if (close(descriptor) != 0) success = false;
    return success ? 0 : -1;
}

static int wait_for_child(int *status) {
    const int poll_us = 10 * 1000;
    int elapsed_ms = 0;
    int ledger_elapsed_ms = LEDGER_REFRESH_MS;
    int owner_elapsed_ms = OWNER_REFRESH_MS;
    int owner_missed_checks = 0;
    bool term_sent = false;
    while (true) {
        owner_elapsed_ms += poll_us / 1000;
        if (!g_force_requested && owner_elapsed_ms >= OWNER_REFRESH_MS) {
            owner_elapsed_ms = 0;
            if (owner_process_alive()) {
                owner_missed_checks = 0;
            } else if (++owner_missed_checks >= OWNER_MISSES_BEFORE_FORCE) {
                /* A real app-process loss is an implicit Force, including Harness graceful mode. */
                g_force_requested = 1;
            }
        }
        pid_t waited = waitpid(g_child_pid, status, WNOHANG);
        if (waited == g_child_pid) {
            (void) write_process_ledger();
            g_child_pid = -1;
            terminate_adopted_children(g_force_requested != 0);
            return 0;
        }
        if (waited < 0 && errno == EINTR) continue;
        if (waited < 0) return -1;
        if (g_force_requested) {
            (void) write_process_ledger();
            signal_process_group(SIGKILL);
            do {
                waited = waitpid(g_child_pid, status, 0);
            } while (waited < 0 && errno == EINTR);
            if (waited == g_child_pid) {
                g_child_pid = -1;
                terminate_adopted_children(true);
                return 0;
            }
            return -1;
        }
        if (g_stop_requested && !term_sent) {
            (void) write_process_ledger();
            signal_term_target(SIGTERM);
            term_sent = true;
            elapsed_ms = 0;
        }
        if (term_sent && elapsed_ms >= g_term_grace_ms && !g_no_force_on_timeout) {
            (void) write_process_ledger();
            signal_process_group(SIGKILL);
            do {
                waited = waitpid(g_child_pid, status, 0);
            } while (waited < 0 && errno == EINTR);
            if (waited == g_child_pid) {
                g_child_pid = -1;
                terminate_adopted_children(true);
                return 0;
            }
            return -1;
        }
        usleep((useconds_t) poll_us);
        if (term_sent) elapsed_ms += poll_us / 1000;
        ledger_elapsed_ms += poll_us / 1000;
        if (g_process_ledger_file != NULL && ledger_elapsed_ms >= LEDGER_REFRESH_MS) {
            (void) write_process_ledger();
            ledger_elapsed_ms = 0;
        }
    }
}

int main(int argc, char **argv) {
    /* termux.c may set PDEATHSIG before exec. Clear that inherited app-edge signal before any
       owner-thread retirement can reach this process; the owner PID/start-time monitor below is
       the only app-death policy. The broker re-establishes PDEATHSIG only for its PRoot child. */
    if (prctl(PR_SET_PDEATHSIG, 0) != 0) {
        perror("proot_broker: clear inherited PR_SET_PDEATHSIG");
        return 77;
    }
    long long max_processes = 128;
    long long max_open_files = 512;
    long long max_file_bytes = 512LL * 1024LL * 1024LL;
    int command_index = -1;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--") == 0) {
            command_index = i + 1;
            break;
        }
        if (strcmp(argv[i], "--no-force-on-timeout") == 0) {
            g_no_force_on_timeout = true;
            continue;
        }
        if (strcmp(argv[i], "--term-child-only") == 0) {
            g_term_child_only = true;
            continue;
        }
        const char *option = argv[i++];
        if (i >= argc) return 64;
        const char *value = argv[i];
        if (strcmp(option, "--max-processes") == 0) {
            if (value == NULL || parse_positive(value, 1, 512, &max_processes) != 0) return 64;
        } else if (strcmp(option, "--max-open-files") == 0) {
            if (value == NULL || parse_positive(value, 16, 2048, &max_open_files) != 0) return 64;
        } else if (strcmp(option, "--max-file-bytes") == 0) {
            if (value == NULL || parse_positive(value, 1, 4LL * 1024LL * 1024LL * 1024LL,
                                                &max_file_bytes) != 0) return 64;
        } else if (strcmp(option, "--term-grace-ms") == 0) {
            long long grace = 0;
            if (value == NULL || parse_positive(value, 100, 10000, &grace) != 0) return 64;
            g_term_grace_ms = (int) grace;
        } else if (strcmp(option, "--child-pid-file") == 0) {
            if (value == NULL || value[0] == '\0' || strlen(value) >= PATH_MAX) return 64;
            g_child_pid_file = value;
        } else if (strcmp(option, "--process-ledger-file") == 0) {
            if (value == NULL || value[0] == '\0' || strlen(value) >= PATH_MAX - 5) return 64;
            g_process_ledger_file = value;
        } else {
            fprintf(stderr, "proot_broker: unknown option: %s\n", option);
            return 64;
        }
    }
    if (command_index < 0 || command_index >= argc) {
        fprintf(stderr, "proot_broker: expected -- followed by a command\n");
        return 64;
    }

    if (apply_limit(RLIMIT_NPROC, (rlim_t) max_processes) != 0 ||
        apply_limit(RLIMIT_NOFILE, (rlim_t) max_open_files) != 0 ||
        apply_limit(RLIMIT_FSIZE, (rlim_t) max_file_bytes) != 0) {
        perror("proot_broker: setrlimit");
        return 77;
    }
    /* A PTY launcher creates a new session before exec. In that case this broker is already the
       process-group leader and Linux returns EPERM for the otherwise harmless setpgid call. */
    if (setpgid(0, 0) != 0 && errno != EACCES && errno != EPERM) {
        perror("proot_broker: setpgid");
        return 77;
    }

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = request_stop;
    sigemptyset(&action.sa_mask);
    sigaction(SIGTERM, &action, NULL);
    sigaction(SIGINT, &action, NULL);

    /* ProcessBuilder and the PTY JNI launcher both make the Android app process this broker's
       parent. Capture its process identity before fork; wait_for_child() revalidates it instead
       of using PR_SET_PDEATHSIG, whose thread-parent semantics can terminate a live Harness when
       the Kotlin I/O launcher worker retires. */
    g_owner_pid = getppid();
    g_owner_start_ticks = read_start_ticks(g_owner_pid);
    if (g_owner_pid <= 1 || !owner_process_alive()) {
        fputs("HARNESS_BROKER_OWNER_UNAVAILABLE\n", stderr);
        return 143;
    }
    if (g_stop_requested || g_force_requested) return 143;
    if (prctl(PR_SET_CHILD_SUBREAPER, 1) != 0) {
        perror("proot_broker: PR_SET_CHILD_SUBREAPER");
        return 77;
    }

    pid_t parent_pid = getpid();
    g_child_pid = fork();
    if (g_child_pid < 0) {
        perror("proot_broker: fork");
        return 77;
    }
    if (g_child_pid == 0) {
        if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != parent_pid) _exit(143);
        (void) setpgid(0, 0);
        execv(argv[command_index], &argv[command_index]);
        fprintf(stderr, "proot_broker: exec failed for %s: %s\n", argv[command_index], strerror(errno));
        _exit(errno == ENOENT ? 127 : 126);
    }
    (void) setpgid(g_child_pid, g_child_pid);
    if (write_child_pid_file() != 0) {
        perror("proot_broker: child pid file");
        (void) kill(-g_child_pid, SIGKILL);
        (void) kill(g_child_pid, SIGKILL);
        (void) waitpid(g_child_pid, NULL, 0);
        return 77;
    }
    if (g_process_ledger_file != NULL && write_process_ledger() != 0) {
        perror("proot_broker: process ledger");
        (void) kill(-g_child_pid, SIGKILL);
        (void) kill(g_child_pid, SIGKILL);
        (void) waitpid(g_child_pid, NULL, 0);
        return 77;
    }

    /* The broker stays alive to supervise the PRoot child, but the child must own the terminal
       foreground group. Otherwise an interactive shell is suspended with SIGTTIN on its first
       read. Ignore the job-control signals while transferring foreground ownership. */
    if (isatty(STDIN_FILENO)) {
        (void) signal(SIGTTOU, SIG_IGN);
        (void) signal(SIGTTIN, SIG_IGN);
        (void) tcsetpgrp(STDIN_FILENO, g_child_pid);
    }

    int status = 0;
    if (wait_for_child(&status) != 0) {
        perror("proot_broker: waitpid");
        return 77;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 77;
}

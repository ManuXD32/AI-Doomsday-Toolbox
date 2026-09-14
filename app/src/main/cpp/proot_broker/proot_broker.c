/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 *
 * Small Android-native process broker for the Agent's Debian PRoot runtime.  It deliberately
 * has no filesystem policy of its own: the Kotlin executor clamps mounts and commands, while
 * this process owns the child process group, resource limits, parent-death signal, and TERM ->
 * KILL cancellation window.
 */

#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

static volatile sig_atomic_t g_stop_requested = 0;
static pid_t g_child_pid = -1;
static int g_term_grace_ms = 3000;

static void request_stop(int signal_number) {
    (void) signal_number;
    g_stop_requested = 1;
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

static int wait_for_child(int *status) {
    const int poll_us = 10 * 1000;
    int elapsed_ms = 0;
    while (true) {
        pid_t waited = waitpid(g_child_pid, status, WNOHANG);
        if (waited == g_child_pid) return 0;
        if (waited < 0 && errno == EINTR) continue;
        if (waited < 0) return -1;
        if (g_stop_requested) {
            signal_process_group(SIGTERM);
            elapsed_ms = 0;
            while (elapsed_ms < g_term_grace_ms) {
                waited = waitpid(g_child_pid, status, WNOHANG);
                if (waited == g_child_pid) return 0;
                if (waited < 0 && errno == EINTR) continue;
                if (waited < 0) return -1;
                usleep((useconds_t) poll_us);
                elapsed_ms += poll_us / 1000;
            }
            signal_process_group(SIGKILL);
            do {
                waited = waitpid(g_child_pid, status, 0);
            } while (waited < 0 && errno == EINTR);
            return waited == g_child_pid ? 0 : -1;
        }
        usleep((useconds_t) poll_us);
    }
}

int main(int argc, char **argv) {
    long long max_processes = 128;
    long long max_open_files = 512;
    long long max_file_bytes = 512LL * 1024LL * 1024LL;
    int command_index = -1;
    for (int i = 1; i < argc; ++i) {
        if (strcmp(argv[i], "--") == 0) {
            command_index = i + 1;
            break;
        }
        const char *value = NULL;
        if (i + 1 < argc) value = argv[++i];
        if (strcmp(argv[i - 1], "--max-processes") == 0) {
            if (value == NULL || parse_positive(value, 1, 512, &max_processes) != 0) return 64;
        } else if (strcmp(argv[i - 1], "--max-open-files") == 0) {
            if (value == NULL || parse_positive(value, 16, 2048, &max_open_files) != 0) return 64;
        } else if (strcmp(argv[i - 1], "--max-file-bytes") == 0) {
            if (value == NULL || parse_positive(value, 1, 4LL * 1024LL * 1024LL * 1024LL,
                                                &max_file_bytes) != 0) return 64;
        } else if (strcmp(argv[i - 1], "--term-grace-ms") == 0) {
            long long grace = 0;
            if (value == NULL || parse_positive(value, 100, 10000, &grace) != 0) return 64;
            g_term_grace_ms = (int) grace;
        } else {
            fprintf(stderr, "proot_broker: unknown option: %s\n", argv[i - 1]);
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
       parent. If that process dies, request the same bounded shutdown path used by Stop. The
       getppid recheck closes the race where the parent exits between the first read and prctl. */
    pid_t launcher_parent_pid = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != launcher_parent_pid) {
        return 143;
    }
    if (g_stop_requested) return 143;

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

    /* The broker stays alive to enforce TERM -> KILL, but the PRoot child must own the terminal
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

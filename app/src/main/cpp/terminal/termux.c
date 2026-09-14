/*
 * Pinned from termux/termux-app v0.118.0 (commit 6e2689f55295fa444be8ac8592c527c2c5ef3253).
 * terminal-emulator is licensed under Apache-2.0. The JNI release call below corrects the
 * upstream tag's cwd/string pairing while preserving the PTY and subprocess behavior.
 */
#include <dirent.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/prctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <unistd.h>

#define TERMUX_UNUSED(x) x __attribute__((__unused__))
#define ADT_ACTIVE_PTY_BIND "__ADT_ACTIVE_PTY_BIND__"

static int throw_runtime_exception(JNIEnv* env, char const* message) {
    jclass ex_class = (*env)->FindClass(env, "java/lang/RuntimeException");
    (*env)->ThrowNew(env, ex_class, message);
    return -1;
}

static int bind_only_active_pty(char** argv, char const* devname) {
    if (argv == NULL) return 0;
    for (char** item = argv; *item != NULL; ++item) {
        if (strcmp(*item, ADT_ACTIVE_PTY_BIND) != 0) continue;
        size_t required = strlen(devname) * 2 + 2;
        char* replacement = (char*) malloc(required);
        if (replacement == NULL) return -1;
        snprintf(replacement, required, "%s:%s", devname, devname);
        free(*item);
        *item = replacement;
    }
    return 0;
}

static int create_subprocess(JNIEnv* env, char const* cmd, char const* cwd,
        char** argv, char** envp, int* process_id, jint rows, jint columns) {
    int ptm = open("/dev/ptmx", O_RDWR | O_CLOEXEC);
    if (ptm < 0) return throw_runtime_exception(env, "Cannot open /dev/ptmx");

    char devname[64];
    if (grantpt(ptm) || unlockpt(ptm) || ptsname_r(ptm, devname, sizeof(devname))) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot grant, unlock, or resolve the terminal PTY");
    }
    if (bind_only_active_pty(argv, devname) != 0) {
        close(ptm);
        return throw_runtime_exception(env, "Cannot prepare the active terminal PTY bind");
    }

    struct termios tios;
    if (tcgetattr(ptm, &tios) == 0) {
        tios.c_iflag |= IUTF8;
        tios.c_iflag &= ~(IXON | IXOFF);
        tcsetattr(ptm, TCSANOW, &tios);
    }

    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    ioctl(ptm, TIOCSWINSZ, &size);

    pid_t pid = fork();
    if (pid < 0) {
        close(ptm);
        return throw_runtime_exception(env, "Fork failed");
    }
    if (pid > 0) {
        *process_id = (int) pid;
        return ptm;
    }

    pid_t launcher_parent = getppid();
    if (prctl(PR_SET_PDEATHSIG, SIGTERM) != 0 || getppid() != launcher_parent) _exit(143);

    sigset_t signals_to_unblock;
    sigfillset(&signals_to_unblock);
    sigprocmask(SIG_UNBLOCK, &signals_to_unblock, NULL);
    close(ptm);
    setsid();

    int pts = open(devname, O_RDWR);
    if (pts < 0) _exit(1);
    ioctl(pts, TIOCSCTTY, 0);
    dup2(pts, STDIN_FILENO);
    dup2(pts, STDOUT_FILENO);
    dup2(pts, STDERR_FILENO);

    DIR* self_dir = opendir("/proc/self/fd");
    if (self_dir != NULL) {
        int self_dir_fd = dirfd(self_dir);
        struct dirent* entry;
        while ((entry = readdir(self_dir)) != NULL) {
            int fd = atoi(entry->d_name);
            if (fd > STDERR_FILENO && fd != self_dir_fd) close(fd);
        }
        closedir(self_dir);
    }

    clearenv();
    if (envp != NULL) {
        for (; *envp; ++envp) putenv(*envp);
    }
    if (chdir(cwd) != 0) perror("chdir");
    execvp(cmd, argv);
    perror("exec");
    _exit(1);
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
        JNIEnv* env, jclass TERMUX_UNUSED(clazz), jstring cmd, jstring cwd,
        jobjectArray args, jobjectArray env_vars, jintArray process_id_array,
        jint rows, jint columns) {
    jsize size = args ? (*env)->GetArrayLength(env, args) : 0;
    char** argv = NULL;
    if (size > 0) {
        argv = (char**) calloc((size_t) size + 1, sizeof(char*));
        if (argv == NULL) return throw_runtime_exception(env, "Could not allocate argv");
        for (int i = 0; i < size; ++i) {
            jstring item = (jstring) (*env)->GetObjectArrayElement(env, args, i);
            char const* utf8 = (*env)->GetStringUTFChars(env, item, NULL);
            if (utf8 == NULL) return throw_runtime_exception(env, "Could not read argv");
            argv[i] = strdup(utf8);
            (*env)->ReleaseStringUTFChars(env, item, utf8);
            (*env)->DeleteLocalRef(env, item);
        }
    }

    size = env_vars ? (*env)->GetArrayLength(env, env_vars) : 0;
    char** envp = NULL;
    if (size > 0) {
        envp = (char**) calloc((size_t) size + 1, sizeof(char*));
        if (envp == NULL) return throw_runtime_exception(env, "Could not allocate environment");
        for (int i = 0; i < size; ++i) {
            jstring item = (jstring) (*env)->GetObjectArrayElement(env, env_vars, i);
            char const* utf8 = (*env)->GetStringUTFChars(env, item, NULL);
            if (utf8 == NULL) return throw_runtime_exception(env, "Could not read environment");
            envp[i] = strdup(utf8);
            (*env)->ReleaseStringUTFChars(env, item, utf8);
            (*env)->DeleteLocalRef(env, item);
        }
    }

    int process_id = 0;
    char const* cmd_utf8 = (*env)->GetStringUTFChars(env, cmd, NULL);
    char const* cwd_utf8 = (*env)->GetStringUTFChars(env, cwd, NULL);
    int ptm = create_subprocess(env, cmd_utf8, cwd_utf8, argv, envp,
        &process_id, rows, columns);
    (*env)->ReleaseStringUTFChars(env, cmd, cmd_utf8);
    (*env)->ReleaseStringUTFChars(env, cwd, cwd_utf8);

    if (argv != NULL) {
        for (char** item = argv; *item; ++item) free(*item);
        free(argv);
    }
    if (envp != NULL) {
        for (char** item = envp; *item; ++item) free(*item);
        free(envp);
    }

    jint* ids = (*env)->GetIntArrayElements(env, process_id_array, NULL);
    if (ids == NULL) return throw_runtime_exception(env, "Could not update process id");
    ids[0] = process_id;
    (*env)->ReleaseIntArrayElements(env, process_id_array, ids, 0);
    return ptm;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd, jint rows, jint columns) {
    struct winsize size = { .ws_row = (unsigned short) rows, .ws_col = (unsigned short) columns };
    ioctl(fd, TIOCSWINSZ, &size);
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyUTF8Mode(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd) {
    struct termios tios;
    if (tcgetattr(fd, &tios) == 0 && (tios.c_iflag & IUTF8) == 0) {
        tios.c_iflag |= IUTF8;
        tcsetattr(fd, TCSANOW, &tios);
    }
}

JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint pid) {
    int status;
    if (waitpid(pid, &status, 0) < 0) return -1;
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return -WTERMSIG(status);
    return 0;
}

JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(
        JNIEnv* TERMUX_UNUSED(env), jclass TERMUX_UNUSED(clazz), jint fd) {
    close(fd);
}

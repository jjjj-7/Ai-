#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <errno.h>

static char *jstr(JNIEnv *env, jstring s) {
    if (s == NULL) return NULL;
    const char *tmp = (*env)->GetStringUTFChars(env, s, NULL);
    if (tmp == NULL) return NULL;
    char *out = strdup(tmp);
    (*env)->ReleaseStringUTFChars(env, s, tmp);
    return out;
}

static char **jarr(JNIEnv *env, jobjectArray arr) {
    if (arr == NULL) return NULL;
    jsize n = (*env)->GetArrayLength(env, arr);
    char **out = calloc((size_t) n + 1, sizeof(char *));
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        out[i] = jstr(env, s);
        if (s != NULL) (*env)->DeleteLocalRef(env, s);
    }
    return out;
}

static void free_arr(char **arr) {
    if (arr == NULL) return;
    for (int i = 0; arr[i] != NULL; i++) free(arr[i]);
    free(arr);
}

JNIEXPORT jint JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_forkPty(
        JNIEnv *env, jclass clazz,
        jstring jcmd, jobjectArray jargv, jobjectArray jenvp,
        jstring jcwd, jintArray jpidOut) {

    char *cmd = jstr(env, jcmd);
    char *cwd = jstr(env, jcwd);
    char **argv = jarr(env, jargv);
    char **envp = jarr(env, jenvp);

    if (cmd == NULL || jpidOut == NULL) {
        free(cmd); free(cwd); free_arr(argv); free_arr(envp);
        return -1;
    }

    pid_t pid = 0;
    int master = forkpty(&pid, NULL, NULL, NULL);

    if (master < 0) {
        free(cmd); free(cwd); free_arr(argv); free_arr(envp);
        return -1;
    }

    if (pid == 0) {
        if (cwd != NULL && chdir(cwd) != 0) { /* keep current dir */ }
        signal(SIGPIPE, SIG_DFL);
        execve(cmd, argv, envp ? envp : environ);
        _exit(127);
    }

    free(cmd); free(cwd); free_arr(argv); free_arr(envp);

    jint jpid = (jint) pid;
    (*env)->SetIntArrayRegion(env, jpidOut, 0, 1, &jpid);
    return master;
}

JNIEXPORT jint JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_waitFor(
        JNIEnv *env, jclass clazz, jint pid, jint timeoutMs) {

    int status = 0;
    int remaining = timeoutMs;
    for (;;) {
        pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
        if (r == (pid_t) pid) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
            return -1;
        }
        if (r < 0) return errno == ECHILD ? 0 : -1;
        if (timeoutMs >= 0 && remaining <= 0) return -2;
        usleep(50000);
        if (timeoutMs >= 0) remaining -= 50;
    }
}

JNIEXPORT void JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_closeFd(
        JNIEnv *env, jclass clazz, jint fd) {
    close(fd);
}

JNIEXPORT jint JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_readFd(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray jbuf) {

    if (fd < 0) return -1;
    jsize cap = (*env)->GetArrayLength(env, jbuf);
    if (cap <= 0) return 0;
    jbyte *buf = (*env)->GetByteArrayElements(env, jbuf, NULL);
    ssize_t n = read(fd, buf, (size_t) cap);
    (*env)->ReleaseByteArrayElements(env, jbuf, buf, 0);
    if (n < 0 && errno == EINTR) n = 0;
    return (jint) n;
}

JNIEXPORT jint JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_writeFd(
        JNIEnv *env, jclass clazz, jint fd, jbyteArray jdata, jint len) {

    if (fd < 0 || jdata == NULL || len <= 0) return -1;
    jbyte *buf = (*env)->GetByteArrayElements(env, jdata, NULL);
    ssize_t written = 0;
    while (written < len) {
        ssize_t n = write(fd, buf + written, (size_t)(len - written));
        if (n < 0) {
            if (errno == EINTR) continue;
            break;
        }
        written += n;
    }
    (*env)->ReleaseByteArrayElements(env, jdata, buf, JNI_ABORT);
    return (jint) written;
}

JNIEXPORT void JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_resize(
        JNIEnv *env, jclass clazz, jint fd, jint cols, jint rows) {
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) cols;
    ws.ws_row = (unsigned short) rows;
    ioctl(fd, TIOCSWINSZ, &ws);
}

JNIEXPORT void JNICALL
Java_dev_autopilot_terminal_terminal_PtyJni_killProcess(
        JNIEnv *env, jclass clazz, jint pid) {
    kill((pid_t) pid, SIGKILL);
}

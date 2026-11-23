// Prism's shell starter: run as the adb shell user (uid 2000), it starts
// Prism's shell server (at.quickme.prism.shell.Server) from Prism's
// own APK with app_process, as a daemon that outlives the adb session.
//
// Derived from Shizuku's starter (manager/src/main/jni/starter.cpp and
// misc.cpp of github.com/RikkaApps/Shizuku v13.6.0, commit 2650830c,
// Apache-2.0, see ../NOTICE). Changes: C instead of C++, Prism's package,
// class and process name; shell only (the root, SELinux, cgroup and
// mount-namespace paths are gone); the APK is found from the starter's own
// path; the starter waits until the server runs before it reports success.
//
// The cluster runs it over adb (android/shizuku/README.md). Exit codes:
//   0 the server runs (pid printed)
//   3 CLASSPATH not set, 4 fork failed, 5 app_process could not start,
//   6 not uid 2000, 7 Prism's APK not found, 8 the server exited at once,
//   9 an old server could not be killed

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#define PACKAGE_NAME "at.quickme.prism"
#define SERVER_NAME "prism_shell"
#define SERVER_CLASS "at.quickme.prism.shell.Server"
#define SHELL_UID 2000

#define EXIT_FATAL_SET_CLASSPATH 3
#define EXIT_FATAL_FORK 4
#define EXIT_FATAL_APP_PROCESS 5
#define EXIT_FATAL_UID 6
#define EXIT_FATAL_PM_PATH 7
#define EXIT_FATAL_SERVER_EXITED 8
#define EXIT_FATAL_KILL 9

// The process's name: the first word of /proc/<pid>/cmdline.
static int get_proc_name(pid_t pid, char *name, size_t size) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd == -1) return -1;
    ssize_t n;
    do {
        n = read(fd, name, size - 1);
    } while (n < 0 && errno == EINTR);
    close(fd);
    if (n < 0) return -1;
    name[n] = '\0';
    return 0;
}

static int is_num(const char *s) {
    if (*s == '\0') return 0;
    for (; *s; s++)
        if (*s < '0' || *s > '9') return 0;
    return 1;
}

// Kills every process called SERVER_NAME (an older server, maybe from an
// older APK).
static void kill_old_servers(void) {
    DIR *dir = opendir("/proc");
    if (!dir) return;
    struct dirent *e;
    pid_t self = getpid();
    while ((e = readdir(dir))) {
        if (e->d_type != DT_DIR || !is_num(e->d_name)) continue;
        pid_t pid = (pid_t) atoi(e->d_name);
        if (pid == self) continue;
        char name[256];
        if (get_proc_name(pid, name, sizeof(name)) != 0 || strcmp(name, SERVER_NAME) != 0) continue;
        if (kill(pid, SIGKILL) == 0) {
            printf("info: killed %d (%s)\n", pid, name);
        } else if (errno == EPERM) {
            fprintf(stderr, "fatal: can't kill %d (%s)\n", pid, name);
            exit(EXIT_FATAL_KILL);
        } else {
            printf("warn: failed to kill %d (%s)\n", pid, name);
        }
    }
    closedir(dir);
}

static int ends_with(const char *s, const char *suffix) {
    size_t a = strlen(s), b = strlen(suffix);
    return a >= b && strcmp(s + a - b, suffix) == 0;
}

// <install dir>/base.apk from this executable's path,
// <install dir>/lib/<abi>/libprism_starter.so.
static int apk_from_self(char *out, size_t size) {
    char self[PATH_MAX];
    ssize_t n = readlink("/proc/self/exe", self, sizeof(self) - 1);
    if (n <= 0) return -1;
    self[n] = '\0';
    for (int i = 0; i < 3; i++) {
        char *slash = strrchr(self, '/');
        if (!slash) return -1;
        *slash = '\0';
    }
    if (snprintf(out, size, "%s/base.apk", self) >= (int) size) return -1;
    return 0;
}

// The same from the package manager, if the starter was copied elsewhere.
static int apk_from_pm(char *out, size_t size) {
    FILE *f = popen("pm path " PACKAGE_NAME, "r");
    if (!f) return -1;
    char line[PATH_MAX];
    int found = -1;
    while (fgets(line, sizeof(line), f)) {
        line[strcspn(line, "\r\n")] = '\0';
        if (strncmp(line, "package:", 8) == 0 && ends_with(line, "/base.apk")) {
            snprintf(out, size, "%s", line + 8);
            found = 0;
            break;
        }
    }
    pclose(f);
    return found;
}

static void run_server(const char *apk) {
    if (setenv("CLASSPATH", apk, 1)) {
        fprintf(stderr, "fatal: can't set CLASSPATH\n");
        _exit(EXIT_FATAL_SET_CLASSPATH);
    }
    char classpath[PATH_MAX + 32];
    snprintf(classpath, sizeof(classpath), "-Djava.class.path=%s", apk);
    char *const argv[] = {
            "/system/bin/app_process",
            classpath,
            "/system/bin",
            "--nice-name=" SERVER_NAME,
            SERVER_CLASS,
            NULL,
    };
    execv(argv[0], argv);
    _exit(EXIT_FATAL_APP_PROCESS);
}

static void sleep_ms(long ms) {
    struct timespec ts = {ms / 1000, (ms % 1000) * 1000000L};
    while (nanosleep(&ts, &ts) == -1 && errno == EINTR) {}
}

// Waits until the child runs as SERVER_NAME and is still alive a moment
// later; returns the exit code for the starter.
static int wait_started(pid_t pid) {
    int named_for = -1;
    for (int i = 0; i < 200; i++) {  // 10 s
        int status;
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) {
            if (named_for < 0 && WIFEXITED(status) && WEXITSTATUS(status) == EXIT_FATAL_APP_PROCESS) {
                fprintf(stderr, "fatal: can't exec app_process\n");
                return EXIT_FATAL_APP_PROCESS;
            }
            if (WIFEXITED(status))
                fprintf(stderr, "fatal: %s exited with %d\n", SERVER_NAME, WEXITSTATUS(status));
            else
                fprintf(stderr, "fatal: %s was killed by signal %d\n", SERVER_NAME, WTERMSIG(status));
            return EXIT_FATAL_SERVER_EXITED;
        }
        char name[256];
        if (named_for < 0 && get_proc_name(pid, name, sizeof(name)) == 0 && strcmp(name, SERVER_NAME) == 0)
            named_for = 0;
        // Alive for 2 s with its name: the runtime is up and found the class.
        if (named_for >= 0 && ++named_for >= 40) return 0;
        sleep_ms(50);
    }
    // Still alive but not yet named: app_process is slow, count it as started.
    return 0;
}

int main(int argc, char *argv[]) {
    char apk[PATH_MAX] = {0};
    for (int i = 1; i < argc; i++) {
        if (strncmp(argv[i], "--apk=", 6) == 0) snprintf(apk, sizeof(apk), "%s", argv[i] + 6);
    }

    uid_t uid = getuid();
    if (uid != SHELL_UID) {
        fprintf(stderr, "fatal: run from the adb shell user (uid %d), not uid %d\n", SHELL_UID, uid);
        return EXIT_FATAL_UID;
    }

    printf("info: starter begin\n");
    fflush(stdout);
    kill_old_servers();

    if (apk[0] == '\0' || access(apk, R_OK) != 0) {
        if (apk_from_self(apk, sizeof(apk)) != 0 || access(apk, R_OK) != 0) {
            if (apk_from_pm(apk, sizeof(apk)) != 0) apk[0] = '\0';
        }
    }
    if (apk[0] == '\0' || access(apk, R_OK) != 0) {
        fprintf(stderr, "fatal: can't find %s's APK\n", PACKAGE_NAME);
        return EXIT_FATAL_PM_PATH;
    }
    printf("info: apk path is %s\n", apk);
    printf("info: starting server...\n");
    fflush(stdout);
    fflush(stderr);

    pid_t pid = fork();
    if (pid == -1) {
        fprintf(stderr, "fatal: can't fork\n");
        return EXIT_FATAL_FORK;
    }
    if (pid == 0) {
        // The daemon: its own session, so the end of the adb session doesn't
        // take it along, and no inherited descriptors.
        setsid();
        if (chdir("/") != 0) _exit(EXIT_FATAL_APP_PROCESS);
        int fd = open("/dev/null", O_RDWR);
        if (fd != -1) {
            dup2(fd, STDIN_FILENO);
            dup2(fd, STDOUT_FILENO);
            dup2(fd, STDERR_FILENO);
            if (fd > 2) close(fd);
        }
        run_server(apk);
    }

    int code = wait_started(pid);
    if (code == 0) printf("info: %s pid is %d\n", SERVER_NAME, pid);
    return code;
}

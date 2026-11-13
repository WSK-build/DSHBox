#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/prctl.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_NOREPLACE
#define RENAME_NOREPLACE 1
#endif

static volatile sig_atomic_t lease_broken;
static void break_lease(int sig) { (void)sig; lease_broken = 1; }

static int recover_empty(const char *name) {
    int fd = open(name, O_RDWR | O_NOFOLLOW | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return 2;
    struct stat opened, current;
    if (fstat(fd, &opened) || !S_ISREG(opened.st_mode) || opened.st_size) {
        close(fd);
        return 2;
    }
    struct sigaction action = {0};
    action.sa_handler = break_lease;
    sigemptyset(&action.sa_mask);
    if (sigaction(SIGIO, &action, NULL) || fcntl(fd, F_SETLEASE, F_WRLCK)) {
        close(fd);
        return 2;
    }
    // A write lease excludes even non-cooperating legacy writers with an open descriptor.
    int removed = !lstat(name, &current) && current.st_dev == opened.st_dev &&
        current.st_ino == opened.st_ino && S_ISREG(current.st_mode) && !current.st_size &&
        !lease_broken && fcntl(fd, F_GETLEASE) == F_WRLCK && !unlink(name);
    fcntl(fd, F_SETLEASE, F_UNLCK);
    close(fd);
    return removed ? 0 : 2;
}

int main(int argc, char **argv) {
    if (argc == 3 && !strcmp(argv[1], "recover-empty")) return recover_empty(argv[2]);
    if (argc == 5 && !strcmp(argv[1], "publish")) {
        long owner = strtol(argv[4], NULL, 10);
        if (prctl(PR_SET_PDEATHSIG, SIGKILL) || owner <= 0 || getppid() != owner) {
            printf("%d\n", ECANCELED);
            return 1;
        }
        // Plain rename and copy fallbacks would break exclusive, fully populated publication.
        if (!syscall(SYS_renameat2, AT_FDCWD, argv[2], AT_FDCWD, argv[3], RENAME_NOREPLACE)) return 0;
        printf("%d\n", errno);
        return 1;
    }
    return 64;
}

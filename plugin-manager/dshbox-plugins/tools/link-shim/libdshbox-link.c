/*
 * libdshbox-link.so — LD_PRELOAD 垫片：把 link(2) / linkat(2) 实现为「复制」。
 *
 * 背景：Android 应用数据文件系统不支持硬链接（真机实测：同目录 `ln` 亦返回
 * Permission denied，而 `cp` / `mv` 正常）。dpkg 在事务末尾创建 status-old 备份时
 * 正是调用 link(2)，于是失败并留下未闭合的账本——此后任何 apt 命令都会拒绝继续，
 * 而按提示执行的 `dpkg --configure -a` 会死在同一步，用户无法自愈。
 *
 * 本垫片只拦截这两个调用：真实 link 失败时退化为「复制」。复制对 dpkg 的备份语义
 * 反而更贴切——得到的是独立副本，而不是同一 inode 的第二个名字。
 *
 * 注入方式：仅由 apt/dpkg 包装脚本按需设置 LD_PRELOAD（不做全局注入，避免影响
 * Node/DSH 等其它程序）。设 DSHBOX_LINK_SHIM_DISABLE=1 可整体停用以做对照排查。
 *
 * 构建见 tools/link-shim/build_link_shim.sh（WSL2 内、用项目自己的 arm64 rootfs 交叉编译）。
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define PATHBUF 4096

static int shim_disabled(void) {
    const char *v = getenv("DSHBOX_LINK_SHIM_DISABLE");
    return v != NULL && v[0] == '1';
}

/* 这些 errno 表示「本环境不支持硬链接」，此时才退化为复制。 */
static int unsupported(int err) {
    return err == EPERM || err == EACCES || err == ENOSYS ||
           err == EMLINK || err == EXDEV || err == EOPNOTSUPP;
}

/* 把 path 解析成绝对路径：dirfd 为 AT_FDCWD 时接当前目录，否则经 /proc/self/fd 解析。 */
static int resolve_at(int dirfd, const char *path, char *out, size_t n) {
    if (path == NULL) {
        errno = EFAULT;
        return -1;
    }
    if (path[0] == '/') {
        if (strlen(path) + 1 > n) {
            errno = ENAMETOOLONG;
            return -1;
        }
        strcpy(out, path);
        return 0;
    }
    if (dirfd == AT_FDCWD) {
        if (getcwd(out, n) == NULL) return -1;
    } else {
        char link[64];
        snprintf(link, sizeof link, "/proc/self/fd/%d", dirfd);
        ssize_t k = readlink(link, out, n - 1);
        if (k <= 0) return -1;
        out[k] = '\0';
    }
    size_t len = strlen(out);
    if (len + 1 + strlen(path) + 1 > n) {
        errno = ENAMETOOLONG;
        return -1;
    }
    out[len] = '/';
    strcpy(out + len + 1, path);
    return 0;
}

/* 复制 src -> dst（不覆盖已存在文件，保持权限位；属主交给调用方/上层处理）。 */
static int copy_path(const char *src, const char *dst) {
    struct stat st;
    if (stat(src, &st) != 0) return -1;
    if (S_ISDIR(st.st_mode)) { /* 目录硬链本就不允许 */
        errno = EPERM;
        return -1;
    }

    int in = open(src, O_RDONLY | O_CLOEXEC);
    if (in < 0) return -1;
    int out = open(dst, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, st.st_mode & 07777);
    if (out < 0) {
        int saved = errno;
        close(in);
        errno = saved;
        return -1;
    }

    char buf[65536];
    for (;;) {
        ssize_t r = read(in, buf, sizeof buf);
        if (r < 0) {
            if (errno == EINTR) continue;
            break;
        }
        if (r == 0) {
            close(in);
            close(out);
            return 0;
        }
        ssize_t off = 0;
        while (off < r) {
            ssize_t w = write(out, buf + off, (size_t)(r - off));
            if (w < 0) {
                if (errno == EINTR) continue;
                break;
            }
            off += w;
        }
        if (off != r) break;
    }

    int saved = errno;
    close(in);
    close(out);
    unlink(dst);
    errno = saved;
    return -1;
}

int link(const char *oldpath, const char *newpath) {
    static int (*real)(const char *, const char *) = NULL;
    if (real == NULL) real = dlsym(RTLD_NEXT, "link");

    int rc = real != NULL ? real(oldpath, newpath) : (errno = ENOSYS, -1);
    if (rc == 0 || shim_disabled() || !unsupported(errno)) return rc;
    return copy_path(oldpath, newpath);
}

int linkat(int olddirfd, const char *oldpath, int newdirfd, const char *newpath, int flags) {
    static int (*real)(int, const char *, int, const char *, int) = NULL;
    if (real == NULL) real = dlsym(RTLD_NEXT, "linkat");

    int rc = real != NULL ? real(olddirfd, oldpath, newdirfd, newpath, flags)
                          : (errno = ENOSYS, -1);
    if (rc == 0 || shim_disabled() || !unsupported(errno)) return rc;
    if (flags != 0) { /* 带 flag 的组合不自行模拟，保持原失败语义 */
        errno = EPERM;
        return -1;
    }

    char src[PATHBUF], dst[PATHBUF];
    if (resolve_at(olddirfd, oldpath, src, sizeof src) != 0) return -1;
    if (resolve_at(newdirfd, newpath, dst, sizeof dst) != 0) return -1;
    return copy_path(src, dst);
}

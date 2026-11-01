/*
 * mod_chmod.c —— chmod / fchmod 一律成功
 *
 * 来源：jni/native_hooks/chmod_hook.c（bytehook 版），这里改成垫片层实现。
 * 差别：
 *   - bytehook 版只在 android_get_device_api_level() < 29 时挂载，且要求 libfcl 先初始化；
 *   - 这一层在符号解析阶段生效，不依赖 bytehook，也不受 API 等级门控——是否启用完全由
 *     FCL_SHIM_MODULES 决定，默认关闭，所以不会改变现有行为。
 *
 * 为什么需要：外部存储（FUSE）上 chmod 返回 EPERM，Java NIO zipfs 落盘与
 * Files.setPosixFilePermissions 会据此判失败，Minecraft 的资源包/整合包解压就会中途抛错。
 * 权限位在 FUSE 上本来就无意义，所以吞掉错误是安全的。
 *
 * 未启用时：照常调用内核，错误原样返回（与 Android 原生一致）。
 */
#include "fclshim.h"
#include "fclshim_log.h"

#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_fchmod
#define SYS_fchmod __NR_fchmod
#endif
#ifndef SYS_fchmodat
#define SYS_fchmodat __NR_fchmodat
#endif

static int g_enabled;
static int g_logged;

/* 首次拦截打一条日志：真机验证垫片是否真的抢到了符号，就靠这行 */
static void log_first_hit(const char *fn, const char *path) {
    if (__atomic_exchange_n(&g_logged, 1, __ATOMIC_ACQ_REL) != 0) return;
    SHIM_LOG("%s 垫片首次拦截：%s（吞掉错误返回成功）", fn, path != NULL ? path : "(null)");
}

FCLSHIM_EXPORT int chmod(const char *path, mode_t mode) {
    long rc;
    /* fchmodat(AT_FDCWD, …) 与 chmod 等价（会跟随符号链接） */
    rc = syscall(SYS_fchmodat, AT_FDCWD, path, (unsigned int) mode, 0);
    if (rc == 0) return 0;
    if (!g_enabled) return -1;          /* 未启用：把真实错误透出去 */
    log_first_hit("chmod", path);
    errno = 0;                          /* 启用：吞掉 EPERM/EROFS，返回成功 */
    return 0;
}

FCLSHIM_EXPORT int fchmod(int fd, mode_t mode) {
    long rc = syscall(SYS_fchmod, fd, (unsigned int) mode);
    if (rc == 0) return 0;
    if (!g_enabled) return -1;
    log_first_hit("fchmod", "(fd)");
    errno = 0;
    return 0;
}

static int enable(void) {
    g_enabled = 1;
    return 0;
}

const fclshim_module_t fclshim_mod_chmod = {
        .name = "chmod",
        .enable = enable,
        .note = "吞掉 FUSE 外部存储上的 chmod/fchmod 失败，供 Java NIO zipfs 落盘",
};

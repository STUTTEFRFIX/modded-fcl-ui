/*
 * test_shim.c —— 垫片层宿主侧验证（不依赖 Android）
 *
 * 用法：
 *   FCL_SHIM_MODULES=            ./test_shim ./libfclshim.so   # 期望：全部保持原生语义
 *   FCL_SHIM_MODULES=futex_waitv,chmod ./test_shim ./libfclshim.so
 *
 * 环境变量必须在 dlopen 之前设好——模块在 .so 的构造函数里就完成注册。
 */
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>
#include <sys/syscall.h>

#ifndef FUTEX_WAKE
#define FUTEX_WAKE 1
#endif
#ifndef FUTEX_PRIVATE_FLAG
#define FUTEX_PRIVATE_FLAG 128
#endif
#ifndef SYS_futex
#define SYS_futex __NR_futex
#endif

/* 与 uapi/linux/futex.h 的 struct futex_waitv 布局一致 */
struct waitv {
    uint64_t val;
    uint64_t uaddr;
    uint32_t flags;
    uint32_t reserved;
};

typedef long (*waitv_fn)(void *, unsigned int, unsigned int, struct timespec *, clockid_t);
typedef int (*api_fn)(void);
typedef const char *(*str_fn)(void);
typedef int (*chmod_fn)(const char *, unsigned int);

static int g_failures;

static void check(int ok, const char *name, const char *fmt, ...) {
    char detail[256];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(detail, sizeof(detail), fmt, ap);
    va_end(ap);
    printf("%s %s — %s\n", ok ? "PASS" : "FAIL", name, detail);
    if (!ok) g_failures++;
}

static int64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

struct wait_ctx {
    waitv_fn fn;
    struct waitv w[2];
    long result;
    int err;
};

static void *waiter_thread(void *arg) {
    struct wait_ctx *c = (struct wait_ctx *) arg;
    c->result = c->fn(c->w, 2, 0, NULL, 0);
    c->err = errno;
    return NULL;
}

int main(int argc, char **argv) {
    const char *lib = (argc > 1) ? argv[1] : "./libfclshim.so";
    const char *modules = getenv("FCL_SHIM_MODULES");
    const int enabled = modules != NULL && modules[0] != '\0' && strcmp(modules, "0") != 0;

    void *h = dlopen(lib, RTLD_NOW | RTLD_GLOBAL);
    if (h == NULL) {
        printf("FAIL dlopen — %s\n", dlerror());
        return 2;
    }

    waitv_fn waitv = (waitv_fn) dlsym(h, "futex_waitv");
    api_fn api = (api_fn) dlsym(h, "fclshim_api_version");
    str_fn active = (str_fn) dlsym(h, "fclshim_active_modules");
    chmod_fn chmodp = (chmod_fn) dlsym(h, "chmod");

    if (waitv == NULL || api == NULL || active == NULL || chmodp == NULL) {
        printf("FAIL dlsym — 导出面不全：waitv=%p api=%p active=%p chmod=%p\n",
               (void *) waitv, (void *) api, (void *) active, (void *) chmodp);
        return 2;
    }

    check(api() == 1, "api_version", "fclshim_api_version() = %d", api());
    printf("     active_modules = \"%s\"（FCL_SHIM_MODULES=\"%s\"）\n",
           active(), modules != NULL ? modules : "(未设置)");

    /* ---- 1. 值不匹配 → EAGAIN（与内核一致） ---- */
    volatile uint32_t f[2] = {1, 2};
    struct waitv w[2];
    memset(w, 0, sizeof(w));
    w[0].val = 2;                       /* 故意写错：实际是 1 */
    w[0].uaddr = (uint64_t) (uintptr_t) &f[0];
    w[0].flags = FUTEX_PRIVATE_FLAG;
    w[1].val = 2;
    w[1].uaddr = (uint64_t) (uintptr_t) &f[1];
    w[1].flags = FUTEX_PRIVATE_FLAG;

    errno = 0;
    long r = waitv(w, 2, 0, NULL, 0);
    int e = errno;
    if (enabled) {
        check(r == -1 && e == EAGAIN, "mismatch→EAGAIN", "rc=%ld errno=%d(%s)", r, e, strerror(e));
    } else {
        check(r == -1 && e == ENOSYS, "disabled→ENOSYS", "rc=%ld errno=%d(%s)", r, e, strerror(e));
    }

    /* ---- 2. 两个等待者，改第 2 个 → 应返回下标 1 ---- */
    if (enabled) {
        struct wait_ctx c;
        memset(&c, 0, sizeof(c));
        c.fn = waitv;
        c.w[0].val = 1;
        c.w[0].uaddr = (uint64_t) (uintptr_t) &f[0];
        c.w[0].flags = FUTEX_PRIVATE_FLAG;
        c.w[1].val = 2;
        c.w[1].uaddr = (uint64_t) (uintptr_t) &f[1];
        c.w[1].flags = FUTEX_PRIVATE_FLAG;

        const int64_t t0 = now_ns();
        pthread_t th;
        pthread_create(&th, NULL, waiter_thread, &c);
        usleep(20 * 1000);              /* 让等待线程真的进入等待 */
        f[1] = 3;                       /* 变值 */
        syscall(SYS_futex, (uint32_t *) &f[1], FUTEX_WAKE, 1, NULL, NULL, 0);
        pthread_join(th, NULL);
        const int64_t elapsed_ms = (now_ns() - t0) / 1000000;

        check(c.result == 1, "wake→index 1", "rc=%ld（期望 1）", c.result);
        check(elapsed_ms < 200, "wake 时延", "%lld ms（期望 < 200）", (long long) elapsed_ms);

        /* ---- 3. 超时 → ETIMEDOUT ---- */
        volatile uint32_t g[2] = {5, 6};
        struct waitv wt[2];
        memset(wt, 0, sizeof(wt));
        wt[0].val = 5;
        wt[0].uaddr = (uint64_t) (uintptr_t) &g[0];
        wt[0].flags = FUTEX_PRIVATE_FLAG;
        wt[1].val = 6;
        wt[1].uaddr = (uint64_t) (uintptr_t) &g[1];
        wt[1].flags = FUTEX_PRIVATE_FLAG;

        struct timespec to = {0, 40 * 1000 * 1000};
        const int64_t t1 = now_ns();
        errno = 0;
        r = waitv(wt, 2, 0, &to, 0);
        e = errno;
        const int64_t timeout_ms = (now_ns() - t1) / 1000000;
        check(r == -1 && e == ETIMEDOUT, "超时→ETIMEDOUT", "rc=%ld errno=%d(%s)", r, e, strerror(e));
        check(timeout_ms >= 30 && timeout_ms < 300, "超时时长", "%lld ms（期望 30–300）",
              (long long) timeout_ms);
    }

    /* ---- 4. chmod ---- */
    errno = 0;
    int cr = chmodp("/definitely/not/here/xyz", 0644);
    e = errno;
    if (enabled) {
        check(cr == 0, "chmod 吞错", "rc=%d（期望 0）", cr);
    } else {
        check(cr == -1, "chmod 透错", "rc=%d errno=%d(%s)", cr, e, strerror(e));
    }

    printf("\n%s：%d 项失败\n", g_failures == 0 ? "整体通过" : "有失败项", g_failures);
    return g_failures == 0 ? 0 : 1;
}

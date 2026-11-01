/*
 * mod_futex_waitv.c —— futex_waitv 的用户态仿真
 *
 * 为什么要垫这一层：
 *   futex_waitv(2) 是 Linux 5.16 才有的系统调用（nr 449）。glibc 2.35+ 起，pthread_cond_wait
 *   在「多等待者」场景会走它；bionic 至今没有这个包装，Android 的 seccomp 也会按内核版本
 *   直接拒掉。FCL 自带的是 bionic 版 JRE，所以**今天用不上**——它的价值在于：
 *   一旦有人导入 glibc 构建的自有 JRE（FCL 的 JavaManager 允许），或者换用更新的运行时，
 *   这条调用会把进程直接打死（SIGSYS），而不是返回 ENOSYS。
 *   有了这一层，无论底层给不给，调用方拿到的语义都一致。
 *
 * 仿真为什么是合法的：
 *   futex_waitv 的契约允许**虚假唤醒**（spurious wakeup）。所以可以退化为
 *   「对第 0 个 futex 做短超时 FUTEX_WAIT_BITSET，醒来后重扫全部等待者」——
 *   语义不变，代价是每次最多多睡一个切片（5ms）。这正是 DroidDeck 垫片层
 *   （libblsession/fsync.c）对 futex_waitv 的处理思路。
 *
 * 返回值契约（照抄内核）：
 *   成功 → 被唤醒的等待者下标（0 起）
 *   失败 → -1，errno 按内核语义（EAGAIN / ETIMEDOUT / EINVAL / ENOSYS）
 */
#include "fclshim.h"
#include "fclshim_log.h"

#include <errno.h>
#include <stdint.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include <sys/syscall.h>

#ifndef FUTEX_WAIT_BITSET
#define FUTEX_WAIT_BITSET 9
#endif
#ifndef FUTEX_PRIVATE_FLAG
#define FUTEX_PRIVATE_FLAG 128
#endif
#ifndef FUTEX_CLOCK_REALTIME
#define FUTEX_CLOCK_REALTIME 256
#endif
#ifndef FUTEX_BITSET_MATCH_ANY
#define FUTEX_BITSET_MATCH_ANY 0xffffffffU
#endif
#ifndef SYS_futex
#define SYS_futex __NR_futex
#endif

/* 与 uapi/linux/futex.h 的 struct futex_waitv 布局一致 */
struct fclshim_futex_waitv {
    uint64_t val;
    uint64_t uaddr;
    uint32_t flags;
    uint32_t reserved;
};

#define FCLSHIM_WAITV_MAX 128U
#define FCLSHIM_SLICE_NS  (5LL * 1000 * 1000)   /* 5ms */

/* 默认关闭：没点名时保持 Android 原生语义（没有这个调用） */
static int g_enabled;

static inline uint32_t load_u32(uint64_t uaddr) {
    return __atomic_load_n((const uint32_t *) (uintptr_t) uaddr, __ATOMIC_ACQUIRE);
}

static int64_t now_ns(clockid_t clk) {
    struct timespec ts;
    if (clock_gettime(clk, &ts) != 0) return -1;
    return (int64_t) ts.tv_sec * 1000000000LL + (int64_t) ts.tv_nsec;
}

static void ns_to_ts(int64_t ns, struct timespec *ts) {
    if (ns < 0) ns = 0;
    ts->tv_sec = (time_t) (ns / 1000000000LL);
    ts->tv_nsec = (long) (ns % 1000000000LL);
}

/* 单点等待：等价于 FUTEX_WAIT_BITSET，返回 0 或 -1+errno */
static int wait_bitset(uint64_t uaddr, uint32_t val, uint32_t private_flag,
                       const struct timespec *abs, int realtime) {
    int op = FUTEX_WAIT_BITSET;
    if (private_flag) op |= FUTEX_PRIVATE_FLAG;
    if (realtime) op |= FUTEX_CLOCK_REALTIME;
    long rc = syscall(SYS_futex, (uint32_t *) (uintptr_t) uaddr, op, val,
                      abs, (void *) 0, FUTEX_BITSET_MATCH_ANY);
    return (int) rc;
}

/*
 * 覆盖 Android 上不存在的 futex_waitv。
 * 注意：函数签名必须与内核/glibc 调用点一致，否则 ABI 不匹配。
 */
FCLSHIM_EXPORT long futex_waitv(void *waiters, unsigned int nr_waiters,
                                unsigned int flags, struct timespec *timeout,
                                clockid_t clockid) {
    if (!g_enabled || waiters == NULL || nr_waiters == 0 || nr_waiters > FCLSHIM_WAITV_MAX) {
        errno = ENOSYS;   /* 未启用时比"假装成功"安全：调用方会走自己的回退分支 */
        return -1;
    }

    const struct fclshim_futex_waitv *w = (const struct fclshim_futex_waitv *) waiters;
    /* 内核两条路都认：flags 里的 FUTEX_CLOCK_REALTIME，或 clockid 直接给 CLOCK_REALTIME */
    const int realtime = (flags & FUTEX_CLOCK_REALTIME) != 0 || clockid == CLOCK_REALTIME;
    const clockid_t clock = (clockid_t) (realtime ? CLOCK_REALTIME : CLOCK_MONOTONIC);
    const uint32_t private_flag = (w[0].flags & FUTEX_PRIVATE_FLAG) ? 1U : 0U;

    /* 起手先比一遍：任一等待者的值已经变了 → 与内核一致，EAGAIN */
    for (unsigned int i = 0; i < nr_waiters; i++) {
        if (load_u32(w[i].uaddr) != (uint32_t) w[i].val) {
            errno = EAGAIN;
            return -1;
        }
    }

    int64_t deadline = 0;
    const int has_deadline = (timeout != NULL);
    if (has_deadline) {
        const int64_t base = now_ns(clock);
        if (base < 0) return -1;
        deadline = base + (int64_t) timeout->tv_sec * 1000000000LL + (int64_t) timeout->tv_nsec;
    }

    for (;;) {
        /* 醒来先重扫：任一等待者变化就是有效唤醒，返回它的下标 */
        for (unsigned int i = 0; i < nr_waiters; i++) {
            if (load_u32(w[i].uaddr) != (uint32_t) w[i].val) {
                return (long) i;
            }
        }

        int64_t slice;
        const int64_t now = now_ns(clock);
        if (now < 0) return -1;
        if (has_deadline) {
            if (now >= deadline) {
                errno = ETIMEDOUT;
                return -1;
            }
            slice = (deadline - now < FCLSHIM_SLICE_NS) ? deadline : now + FCLSHIM_SLICE_NS;
        } else {
            slice = now + FCLSHIM_SLICE_NS;
        }

        struct timespec abs;
        ns_to_ts(slice, &abs);
        const int rc = wait_bitset(w[0].uaddr, (uint32_t) w[0].val, private_flag, &abs, realtime);
        if (rc == 0) continue;                     /* 被唤醒 → 回顶部判定是谁 */
        if (errno == EAGAIN) continue;             /* 第 0 个已变 → 回顶部返回下标 */
        if (errno == ETIMEDOUT) continue;          /* 只是切片到期 → 回顶部判全局超时 */
        if (errno == ENOSYS || errno == EINVAL) {
            /* 内核不认 WAIT_BITSET：退化成纯轮询，语义不变，只是更费 CPU */
            struct timespec nap;
            ns_to_ts(FCLSHIM_SLICE_NS, &nap);
            nanosleep(&nap, NULL);
            continue;
        }
        return -1;                                 /* 其它错误原样透出 */
    }
}

static int enable(void) {
    g_enabled = 1;
    return 0;
}

const fclshim_module_t fclshim_mod_futex_waitv = {
        .name = "futex_waitv",
        .enable = enable,
        .note = "用 FUTEX_WAIT_BITSET + 短切片仿真 futex_waitv，为 glibc 构建的 JRE 兜底",
};

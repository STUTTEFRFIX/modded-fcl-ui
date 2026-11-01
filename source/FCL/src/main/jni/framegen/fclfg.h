/*
 * fclfg.h —— FCL 插帧挂载骨架：宿主 ↔ 管线 的契约（ABI v1）
 *
 * 分工：
 *   宿主（FCL，本目录的 fclfg_core.c）负责 —— 采集、帧槽、倍率、时间预算、超时降级、统计
 *   管线（你）负责 —— interpolate() 一个回调：拿 A、B 两帧，产出 t 处的中间帧
 *
 * 三槽布局（由宿主管理，管线只需按回调给到的 slot 号操作）：
 *   slot 0 = A   真实帧槽（与 slot 1 交替承载相邻两帧）
 *   slot 1 = B   真实帧槽
 *   slot 2 = OUT 中间帧输出槽（每次插值都会重写它）
 *
 * A/B 是**交替**使用的：第 N 帧写 A、第 N+1 帧写 B。
 * 回调里永远收到 (prev, cur) 两个槽号，所以不用猜谁是上一帧、谁是本帧。
 *
 * 调用时序（单渲染线程内同步发生，宿主不另起线程）：
 *
 *   游戏渲染完一帧
 *        │
 *        ▼
 *   fclfg_frame()
 *        ├─ capture(user, B)            把本帧存进 B 槽
 *        ├─ loop i = 1..mult-1:
 *        │     interpolate(user, A, B, i/mult, OUT)   ← 你的管线在这里干活
 *        │     present(user, OUT)                     呈现一个中间帧（阻塞到 vsync）
 *        └─ present(user, B)                          呈现真实帧
 *
 * 硬约束（违反不会崩，但会毁掉体验）：
 *   1. capture / present 必须是**同步**的：present 返回时该帧必须真的已送显（阻塞到 vsync）。
 *      OUT 槽每轮复用，present 没返回就写下一帧会撕裂。
 *   2. interpolate 必须是**非阻塞**的：它占用的是游戏渲染线程，跑久了游戏就掉帧。
 *      超过预算时宿主会直接丢弃本次插帧（见 fclfg_stats_t.dropped），不会等你。
 *   3. 三个回调都在同一个线程里被调用（渲染线程），不需要自己加锁。
 */
#ifndef FCLFG_H
#define FCLFG_H

#include <stdint.h>

#define FCLFG_ABI_VERSION 1

/* 帧槽编号（宿主固定分配） */
#define FCLFG_SLOT_A   0
#define FCLFG_SLOT_B   1
#define FCLFG_SLOT_OUT 2

typedef struct fclfg_backend {
    /* 把刚渲染完的一帧存进 slot。返回 0 成功，负值失败（失败即本帧直通）。 */
    int (*capture)(void *user, int slot);

    /* 把 slot 的画面送显并完成一次 swap/present，**必须阻塞到 vsync**。
       返回 0 成功，负值失败（失败即放弃后续插帧，真实帧照常呈现）。 */
    int (*present)(void *user, int slot);

    /* 插帧：用 A、B 两帧生成 t∈(0,1) 处的中间帧写入 slot_out。
       t 由宿主按倍率均匀给：2× → 0.5；3× → 0.333/0.667；4× → 0.25/0.5/0.75。
       返回 0 成功；负值表示本次放弃（宿主记一次 dropped 并跳过）。 */
    int (*interpolate)(void *user, int slot_a, int slot_b, float t, int slot_out);

    void *user;
} fclfg_backend_t;

typedef struct fclfg_stats {
    uint64_t frames_real;       /* 游戏真实帧数 */
    uint64_t frames_generated;  /* 成功呈现的插值帧数 */
    uint64_t dropped;           /* 因超预算/管线失败被丢弃的插值帧数 */
    double   real_fps;
    double   total_fps;         /* 真实 + 插值 */
    double   last_interp_ms;    /* 最近一次 interpolate 耗时 */
    double   budget_ms;         /* 当前每帧插值预算 */
    int      multiplier;
} fclfg_stats_t;

#ifdef __cplusplus
extern "C" {
#endif

/* 是否启用（FCLFG=1 或 /sdcard/FCL/framegen.conf 里 enabled=1） */
int fclfg_enabled(void);

/* 宿主入口：游戏每渲染完一帧、在 swap 之前调用一次。
   frame_ns：本帧的开始时间戳（单调时钟，纳秒）；传 0 则由宿主自行取时钟。
   返回本帧实际呈现的帧数：1 = 未插帧直通，N = 1 个真实帧 + (N-1) 个插值帧。 */
int fclfg_frame(const fclfg_backend_t *backend, int64_t frame_ns);

/* 统计快照（只读，宿主内部维护） */
const fclfg_stats_t *fclfg_stats(void);

/* 运行期关闭（给「一键关掉」用；不影响配置文件） */
void fclfg_shutdown(void);

/* 测试钩子：替换单调时钟（传 NULL 恢复默认）。只在单测里用。 */
void fclfg_set_clock(int64_t (*fn)(void));

#ifdef __cplusplus
}
#endif

#endif /* FCLFG_H */

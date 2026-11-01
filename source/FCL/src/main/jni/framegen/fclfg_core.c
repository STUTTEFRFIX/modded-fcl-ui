/*
 * fclfg_core.c —— 插帧宿主核心：配置、帧槽、倍率、时间预算、降级、统计
 *
 * 这一层不碰任何图形 API，只做调度决策，所以可以在电脑上完整单测（见 tests/test_fclfg.c）。
 * 图形后端（GL / Vulkan）在阶段 2、3 接进来，只需实现 capture/present 两个回调。
 *
 * 槽的用法：A/B 两槽**交替**承载相邻两帧。
 *   第 N   帧 → 写 A 槽，插值时拿到 (prev=B, cur=A)
 *   第 N+1 帧 → 写 B 槽，插值时拿到 (prev=A, cur=B)
 *   任何时刻 A 与 B 就是"上一帧"和"本帧"，管线不需要关心谁是 A 谁是 B。
 */
#define _GNU_SOURCE   /* clock_gettime under -std=c11 */

#include "fclfg.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#ifdef __ANDROID__
#include <android/log.h>
#define FG_TAG "FCLFG"
#define FG_LOG(...) __android_log_print(ANDROID_LOG_INFO, FG_TAG, __VA_ARGS__)
#else
#define FG_LOG(...) do { fprintf(stderr, "[FCLFG] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif

#define FCLFG_MAX_MULT      4
#define FCLFG_DEFAULT_MULT  2
#define FCLFG_CONF_PATH     "/sdcard/FCL/framegen.conf"

/* 预算：不超过两帧间隔的 90%，夹在 [4ms, 33ms] */
#define FCLFG_BUDGET_MIN_MS 4.0
#define FCLFG_BUDGET_MAX_MS 33.0
#define FCLFG_BUDGET_RATIO  0.9

/* 连续超预算这么多次就自适应停掉插帧（慢管线不该一直白耗） */
#define FCLFG_STREAK_LIMIT  30

typedef struct {
    int enabled;
    int multiplier;
    int slot_cur;            /* 本帧写入的槽：A 或 B，逐帧翻转 */
    int64_t last_frame_ns;
    double  budget_ms;
    int     drop_streak;
    int     adaptive_off;
    int64_t last_stats_log_ns;
    fclfg_stats_t st;
} fclfg_state_t;

static fclfg_state_t g;
static int g_inited;

/* ---- 可替换时钟（单测注入） ---- */
static int64_t default_clock_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t) ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

static int64_t (*g_clock_ns)(void) = default_clock_ns;

void fclfg_set_clock(int64_t (*fn)(void)) {
    g_clock_ns = fn != NULL ? fn : default_clock_ns;
}

static int clamp_int(int v, int lo, int hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/* ---- 配置：环境变量优先，其次 /sdcard/FCL/framegen.conf ---- */

static long conf_get_int(const char *key, long def) {
    FILE *f = fopen(FCLFG_CONF_PATH, "r");
    if (f == NULL) return def;

    char line[256];
    long val = def;
    const size_t klen = strlen(key);
    while (fgets(line, sizeof(line), f) != NULL) {
        char *p = line;
        while (*p == ' ' || *p == '\t') p++;
        if (*p == '#' || *p == '\n' || *p == '\0') continue;
        if (strncmp(p, key, klen) != 0) continue;
        char *q = p + klen;
        while (*q == ' ' || *q == '\t') q++;
        if (*q != '=') continue;
        val = strtol(q + 1, NULL, 10);
        break;
    }
    fclose(f);
    return val;
}

static void load_config(void) {
    const char *env_on = getenv("FCLFG");
    const char *env_mult = getenv("FCLFG_MULT");

    int on;
    if (env_on != NULL) {
        on = !(env_on[0] == '\0' || env_on[0] == '0' ||
               (env_on[0] == 'f' && strcmp(env_on, "false") == 0));
    } else {
        on = (int) conf_get_int("enabled", 0) != 0;
    }
    if (!on) return;

    int mult;
    if (env_mult != NULL && env_mult[0] != '\0') {
        mult = (int) strtol(env_mult, NULL, 10);
    } else {
        mult = (int) conf_get_int("mult", FCLFG_DEFAULT_MULT);
    }

    g.multiplier = clamp_int(mult, 2, FCLFG_MAX_MULT);
    g.enabled = 1;
    g.st.multiplier = g.multiplier;

    FG_LOG("插帧启用：%d×（配置来自 %s）", g.multiplier,
           env_on != NULL ? "环境变量 FCLFG" : FCLFG_CONF_PATH);
}

static void ensure_init(void) {
    if (g_inited) return;
    g_inited = 1;
    memset(&g, 0, sizeof(g));
    g.slot_cur = FCLFG_SLOT_A;
    g.budget_ms = 8.0;
    g.st.multiplier = 1;
    load_config();
}

int fclfg_enabled(void) {
    ensure_init();
    return g.enabled;
}

void fclfg_shutdown(void) {
    if (g.enabled) {
        FG_LOG("插帧运行期关闭（已呈现 %llu 插值帧，丢弃 %llu）",
               (unsigned long long) g.st.frames_generated,
               (unsigned long long) g.st.dropped);
    }
    g.enabled = 0;
}

const fclfg_stats_t *fclfg_stats(void) {
    ensure_init();
    return &g.st;
}

static void update_fps(double dt_ms, int presented) {
    if (dt_ms <= 0.0) return;
    const double inst_real = 1000.0 / dt_ms;
    const double inst_total = inst_real * (double) presented;
    if (g.st.real_fps <= 0.0) {
        g.st.real_fps = inst_real;
        g.st.total_fps = inst_total;
    } else {
        g.st.real_fps = g.st.real_fps * 0.95 + inst_real * 0.05;
        g.st.total_fps = g.st.total_fps * 0.95 + inst_total * 0.05;
    }
}

static void maybe_log_stats(int64_t now_ns) {
    const int64_t window = 2000LL * 1000 * 1000;   /* 2 秒 */
    if (g.last_stats_log_ns != 0 && now_ns - g.last_stats_log_ns < window) return;
    g.last_stats_log_ns = now_ns;
    FG_LOG("real %.1f / total %.1f fps（%d×）· 插值 %.2f ms · 预算 %.2f ms · 丢弃 %llu",
           g.st.real_fps, g.st.total_fps, g.multiplier, g.st.last_interp_ms, g.budget_ms,
           (unsigned long long) g.st.dropped);
}

int fclfg_frame(const fclfg_backend_t *backend, int64_t frame_ns) {
    ensure_init();

    if (backend == NULL || backend->present == NULL) return 1;

    /* 直通：未启用 / 倍率不足 / 没有管线 / 采集能力缺失 / 自适应已停 */
    if (!g.enabled || g.multiplier < 2 || backend->interpolate == NULL ||
        backend->capture == NULL || g.adaptive_off) {
        backend->present(backend->user, g.slot_cur);
        g.st.frames_real++;
        g.slot_cur = (g.slot_cur == FCLFG_SLOT_A) ? FCLFG_SLOT_B : FCLFG_SLOT_A;
        return 1;
    }

    const int64_t now = frame_ns != 0 ? frame_ns : g_clock_ns();
    const int prev_slot = (g.slot_cur == FCLFG_SLOT_A) ? FCLFG_SLOT_B : FCLFG_SLOT_A;
    const int cur_slot = g.slot_cur;
    const int mult = g.multiplier;

    /* 两帧间隔 → 每个插值槽的预算 */
    const int64_t dt_ns = (g.last_frame_ns > 0) ? (now - g.last_frame_ns) : 0;
    g.last_frame_ns = now;
    const double dt_ms = dt_ns > 0 ? (double) dt_ns / 1e6 : 16.6;
    g.budget_ms = dt_ms * FCLFG_BUDGET_RATIO / (double) (mult - 1);
    if (g.budget_ms < FCLFG_BUDGET_MIN_MS) g.budget_ms = FCLFG_BUDGET_MIN_MS;
    if (g.budget_ms > FCLFG_BUDGET_MAX_MS) g.budget_ms = FCLFG_BUDGET_MAX_MS;
    g.st.budget_ms = g.budget_ms;

    /* 采集本帧；失败则本帧直通（真实帧不能丢） */
    if (backend->capture(backend->user, cur_slot) < 0) {
        backend->present(backend->user, cur_slot);
        g.st.frames_real++;
        g.slot_cur = prev_slot;
        return 1;
    }

    int presented = 0;
    int interp_needed = mult - 1;

    for (int i = 1; i < interp_needed + 1; i++) {
        /* 上一轮已经超预算：剩下的插值槽全部记丢弃，不再试探 */
        if (g.drop_streak > 0 && g.st.last_interp_ms > g.budget_ms) {
            g.st.dropped += (uint64_t) (interp_needed - i + 1);
            g.drop_streak += interp_needed - i + 1;
            break;
        }

        const float t = (float) i / (float) mult;
        const int64_t t0 = g_clock_ns();
        const int rc = backend->interpolate(backend->user, prev_slot, cur_slot, t,
                                            FCLFG_SLOT_OUT);
        g.st.last_interp_ms = (double) (g_clock_ns() - t0) / 1e6;

        if (rc < 0) {
            g.st.dropped += (uint64_t) (interp_needed - i + 1);
            g.drop_streak += interp_needed - i + 1;
            break;
        }

        /* 已产出：必须呈现，否则这一帧白算 */
        if (backend->present(backend->user, FCLFG_SLOT_OUT) < 0) break;
        presented++;
        g.st.frames_generated++;

        g.drop_streak = (g.st.last_interp_ms > g.budget_ms) ? g.drop_streak + 1 : 0;
    }

    if (g.drop_streak >= FCLFG_STREAK_LIMIT && !g.adaptive_off) {
        g.adaptive_off = 1;
        FG_LOG("连续 %d 次超预算，自适应关闭插帧（管线跟不上，先去查单帧耗时）",
               g.drop_streak);
    }

    /* 真实帧收尾 */
    backend->present(backend->user, cur_slot);
    presented++;
    g.st.frames_real++;

    g.slot_cur = prev_slot;    /* 本帧变成下一帧的"上一帧" */
    update_fps(dt_ms, presented);
    maybe_log_stats(now);
    return presented;
}

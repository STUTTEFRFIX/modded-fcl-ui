/*
 * fclshim.c —— 垫片层注册表
 *
 * 加一个模块只需两步：
 *   1. 在 modules/ 下新建 mod_xxx.c，导出 fclshim_mod_xxx 描述符（hidden，不用 FCLSHIM_EXPORT）；
 *   2. 在下面的 fclshim_builtin[] 里登记一行。
 */
#include "fclshim.h"
#include "fclshim_log.h"

#include <stdlib.h>
#include <string.h>

/* ---- 内置模块登记表 ---- */
extern const fclshim_module_t fclshim_mod_futex_waitv;
extern const fclshim_module_t fclshim_mod_chmod;

static const fclshim_module_t *const fclshim_builtin[] = {
        &fclshim_mod_futex_waitv,
        &fclshim_mod_chmod,
};

#define FCLSHIM_BUILTIN_COUNT (sizeof(fclshim_builtin) / sizeof(fclshim_builtin[0]))

static int g_entered;
static char g_active[256];

/* 名字匹配：支持 "*"、逗号分隔、名字两侧空格 */
static int name_selected(const char *list, const char *name) {
    if (list == NULL || list[0] == '\0') return 0;
    if (strcmp(list, "*") == 0 || strcmp(list, "all") == 0) return 1;

    const size_t want = strlen(name);
    const char *p = list;
    while (*p != '\0') {
        while (*p == ',' || *p == ' ' || *p == '\t') p++;
        const char *q = p;
        while (*q != '\0' && *q != ',') q++;
        size_t len = (size_t) (q - p);
        while (len > 0 && (p[len - 1] == ' ' || p[len - 1] == '\t')) len--;
        if (len == want && strncmp(p, name, want) == 0) return 1;
        p = q;
    }
    return 0;
}

FCLSHIM_EXPORT int fclshim_api_version(void) {
    return FCLSHIM_API_VERSION;
}

FCLSHIM_EXPORT const char *fclshim_active_modules(void) {
    return g_active;
}

FCLSHIM_EXPORT int fclshim_bootstrap(void) {
    /* 防止构造函数与手动调用重入 */
    if (__atomic_exchange_n(&g_entered, 1, __ATOMIC_ACQ_REL) != 0) {
        return 0;
    }

    const char *list = getenv("FCL_SHIM_MODULES");
    if (list == NULL || list[0] == '\0') {
        SHIM_LOG("垫片层已载入，未点名任何模块（FCL_SHIM_MODULES 为空），保持 Android 原生语义");
        return 0;
    }

    int enabled = 0;
    for (size_t i = 0; i < FCLSHIM_BUILTIN_COUNT; i++) {
        const fclshim_module_t *m = fclshim_builtin[i];
        if (!name_selected(list, m->name)) continue;

        if (m->enable() != 0) {
            SHIM_LOG("模块 %s 启用失败，跳过", m->name);
            continue;
        }
        enabled++;
        if (g_active[0] != '\0') strncat(g_active, ",", sizeof(g_active) - strlen(g_active) - 1);
        strncat(g_active, m->name, sizeof(g_active) - strlen(g_active) - 1);
        SHIM_LOG("模块 %s 已启用：%s", m->name, m->note != NULL ? m->note : "");
    }

    SHIM_LOG("垫片层就绪，启用 %d/%d 个模块：%s", enabled, (int) FCLSHIM_BUILTIN_COUNT,
             g_active[0] != '\0' ? g_active : "(无)");
    return enabled;
}

/* 被 linker 载入即自动铺底——调用方不需要知道模块列表 */
__attribute__((constructor)) static void fclshim_auto_init(void) {
    (void) fclshim_bootstrap();
}

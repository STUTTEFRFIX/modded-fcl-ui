/*
 * fclshim.h —— FCL 垫片层（shim layer）公共契约
 *
 * 定位：DroidDeck 的 ld.so.preload + libblsession 那一套在 FCL 上的等价物。
 * 区别是 Android 没有可写的 rootfs、进程由 zygote fork，环境变量改不了已加载的符号表，
 * 所以铺底方式换成「带 -z global 的 .so 以 RTLD_GLOBAL 进 JVM 自己的 linker 命名空间」，
 * 由 liblinkernsbypass 完成——效果是该命名空间之后加载的库（libjli/libjvm/libjava…）
 * 解析符号时先撞到本层。
 *
 * 硬约束（与 tools/check-shim-symbols.sh 对应）：
 *   1. 所有对外符号必须显式 FCLSHIM_EXPORT，其余一律 hidden；
 *   2. 只能进白名单里的符号，禁止垫 mmap/socket/open/dlsym/malloc 这类地基符号
 *      （HotSpot 的 GC 与 JIT 重度依赖它们，垫住就是随机崩溃）；
 *   3. 模块默认关闭，靠 FCL_SHIM_MODULES 显式点名启用。
 */
#ifndef FCLSHIM_H
#define FCLSHIM_H

#include <stddef.h>
#include <stdint.h>

#define FCLSHIM_API_VERSION 1

/* 对外导出：唯一允许出现在动态符号表里的方式 */
#define FCLSHIM_EXPORT __attribute__((visibility("default"), used))

/* 一个垫片模块：编译进 libfclshim.so，由注册表按 FCL_SHIM_MODULES 点名启用 */
typedef struct fclshim_module {
    const char *name;      /* 环境变量里用的名字 */
    int (*enable)(void);   /* 启用；0 成功，非 0 记为失败（不拖累其它模块） */
    const char *note;      /* 一行说明，只用于日志 */
} fclshim_module_t;

#ifdef __cplusplus
extern "C" {
#endif

/* 层版本，供宿主侧判断是否需要重启 JVM 才生效 */
FCLSHIM_EXPORT int fclshim_api_version(void);

/* 构造函数会自动调用；重复调用幂等。返回实际启用的模块数 */
FCLSHIM_EXPORT int fclshim_bootstrap(void);

/* 已启用模块名（逗号分隔，空串表示一个都没启用） */
FCLSHIM_EXPORT const char *fclshim_active_modules(void);

#ifdef __cplusplus
}
#endif

#endif /* FCLSHIM_H */

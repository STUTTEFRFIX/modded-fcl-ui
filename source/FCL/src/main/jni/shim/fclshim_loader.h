#ifndef FCLSHIM_LOADER_H
#define FCLSHIM_LOADER_H

#ifdef __cplusplus
extern "C" {
#endif

/*
 * 在垫片层自己的 linker 命名空间里加载 libjli.so，并返回句柄。
 *
 * - 垫片未启用（FCL_SHIM 为空/0/false）、创建命名空间失败、垫片或 libjli 加载失败
 *   → 一律返回 NULL，调用方必须回退到普通 dlopen("libjli.so")。
 * - 垫片启用时，libjli 及其之后在该命名空间内加载的库都会先看到垫片层的符号。
 * - 本函数自身不做任何 JVM 初始化，必须在 abort_waiter_setup() 之前调用。
 */
void *fclshim_dlopen_jli(void);

#ifdef __cplusplus
}
#endif

#endif /* FCLSHIM_LOADER_H */

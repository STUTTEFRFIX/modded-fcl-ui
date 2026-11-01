/*
 * fclshim_loader.c —— 把垫片层铺进 JVM 自己的 linker 命名空间
 *
 * 这是整套东西的关键一步，也是「Android 上 ld.so.preload 不存在」的替代方案：
 *
 *   glibc 世界（DroidDeck）              Android 世界（本层）
 *   ─────────────────────────────       ────────────────────────────────────────
 *   /etc/ld.so.preload 写一行 .so        没有可写 rootfs；进程由 zygote fork，
 *                                        环境变量改不了已加载符号表
 *                                        → 只能新建一个 linker 命名空间，
 *                                          把带 -z global 的 .so 以 RTLD_GLOBAL
 *                                          载进去，让它进入该命名空间的预载表
 *   对运行时内所有进程生效                只对「在这个命名空间里加载的库」生效
 *                                        → 所以把 libjli 也搬进同一个命名空间，
 *                                          JVM 整棵子树就都吃到了垫片
 *
 * 覆盖边界（必须写清楚，否则会误判）：
 *   ✅ libjli → libjvm / libjava / libnio / libzip …（JVM 自己 dlopen 的）
 *   ✅ 这些库按 soName 找的依赖（LD_LIBRARY_PATH + nativeLibraryDir 都在搜索路径里）
 *   ❌ Java 侧 System.loadLibrary（走的是 classloader 命名空间 clns-XX）
 *      —— 那批库（LWJGL 等）由 jni/jvm_hooks/lwjgl_dlopen_hook.c 这类 bytehook 负责，
 *         两条路各管一段，不要试图用一条覆盖全部。
 */
#include "fclshim_loader.h"

#include <stddef.h>

#ifdef __ANDROID__

#include <android/log.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "androidnsbypass/nsbypass.h"
#include "androidnsbypass/nsbypass_t.h"   /* ANDROID_NAMESPACE_TYPE_* 枚举 */

#define SHIM_TAG "FCLShim"
#define SHIM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, SHIM_TAG, __VA_ARGS__)

static int shim_requested(void) {
    const char *v = getenv("FCL_SHIM");
    if (v == NULL) return 0;
    if (v[0] == '\0' || v[0] == '0') return 0;
    if (v[0] == 'f' && strcmp(v, "false") == 0) return 0;
    return 1;
}

/* libfcl.so 所在目录 = APK 的 nativeLibraryDir，垫片 .so 与它同级 */
static int self_lib_dir(char *out, size_t cap) {
    Dl_info info;
    if (dladdr((void *) (uintptr_t) fclshim_dlopen_jli, &info) == 0 || info.dli_fname == NULL) {
        return -1;
    }
    const char *slash = strrchr(info.dli_fname, '/');
    if (slash == NULL) return -1;
    const size_t len = (size_t) (slash - info.dli_fname);
    if (len == 0 || len + 1 > cap) return -1;
    memcpy(out, info.dli_fname, len);
    out[len] = '\0';
    return 0;
}

void *fclshim_dlopen_jli(void) {
    if (!shim_requested()) return NULL;

    char libdir[PATH_MAX];
    if (self_lib_dir(libdir, sizeof(libdir)) != 0) {
        SHIM_LOGI("拿不到 nativeLibraryDir，垫片跳过");
        return NULL;
    }

    char shim_path[PATH_MAX];
    int n = snprintf(shim_path, sizeof(shim_path), "%s/libfclshim.so", libdir);
    if (n <= 0 || (size_t) n >= sizeof(shim_path)) return NULL;

    /* JRE 自己的 lib 目录由 FCLauncher 写进 LD_LIBRARY_PATH，直接借来当搜索路径 */
    const char *java_lib_path = getenv("LD_LIBRARY_PATH");

    struct android_namespace_t *ns = private_create_namespace(
            "fcl-shim-NS",
            java_lib_path,          /* ld_library_path：JRE 的 lib / lib/server */
            libdir,                 /* default_library_path：APK natives（libawt_xawt 等替身库） */
            ANDROID_NAMESPACE_TYPE_SHARED_ISOLATED,
            NULL,
            NULL,
            __builtin_return_address(0));
    if (ns == NULL) {
        SHIM_LOGI("创建垫片命名空间失败，回退普通加载");
        return NULL;
    }

    /* 授予系统库访问权（libc/libm/libdl/liblog/EGL/Vulkan…） */
    private_link_namespaces_all_libs(ns, get_escape_namespace());

    /* RTLD_GLOBAL + 编译期 -z global → 进入该命名空间的预载表，这是「垫底」的本体 */
    void *shim = linker_ns_dlopen(shim_path, RTLD_NOW | RTLD_GLOBAL, ns);
    if (shim == NULL) {
        SHIM_LOGI("加载 %s 失败：%s，回退普通加载", shim_path, dlerror());
        return NULL;
    }
    SHIM_LOGI("垫片层已铺进命名空间：%s", shim_path);

    void *jli = linker_ns_dlopen("libjli.so", RTLD_LAZY | RTLD_GLOBAL, ns);
    if (jli == NULL) {
        SHIM_LOGI("libjli.so 在垫片命名空间内加载失败：%s，回退普通加载", dlerror());
    }
    return jli;
}

#else /* !__ANDROID__：宿主侧只做编译与逻辑测试，不参与铺底 */

void *fclshim_dlopen_jli(void) {
    return NULL;
}

#endif

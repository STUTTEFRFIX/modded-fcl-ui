# fclshim —— FCL 的垫片层

DroidDeck 用 `ld.so.preload` + 一个 `libblsession.so` 给整个运行时铺底。Android 上没有
`ld.so.preload`（没有可写 rootfs，进程由 zygote fork，环境变量改不了已加载的符号表），
所以这里换成**同一件事的 Android 形态**：

```
private_create_namespace()            新建一个只给 JVM 用的 linker 命名空间
linker_ns_dlopen(libfclshim.so,       以 -z global 编译的垫片库 RTLD_GLOBAL 载入
                 RTLD_GLOBAL)         → 进入该命名空间的预载表
linker_ns_dlopen(libjli.so, …)        把 libjli 也载进同一个命名空间
                                      → JVM 整棵子树之后解析符号都先撞到垫片
```

入口在 `jni/jre_launcher.c` 的 `launchJVM()`，只有三行，失败一律回退普通 `dlopen`。

## 覆盖边界

| | 覆盖 | 说明 |
|---|---|---|
| ✅ | libjli → libjvm / libjava / libnio / libzip | JVM 自己 `dlopen` 的库，全部走垫片命名空间 |
| ✅ | 上述库按 soName 找的依赖 | 搜索路径 = `LD_LIBRARY_PATH` + APK nativeLibraryDir |
| ❌ | Java 侧 `System.loadLibrary` | 走 classloader 命名空间（clns-XX），归 `jni/jvm_hooks/*` 的 bytehook 管 |

两条路各管一段，不要试图用一条覆盖全部。

## 开关

`/sdcard/FCL/shim.conf` 存在即启用，内容是要加载的模块名：

```
# 一行一个，或逗号分隔；# 开头为注释；* 表示全部
futex_waitv
chmod
```

`FCLauncher.addCommonEnv()` 读这个文件 → 注入 `FCL_SHIM` / `FCL_SHIM_MODULES` →
`fclshim_dlopen_jli()` 决定是否铺底。**文件不存在时行为与上游完全一致。**

## 加一个模块

1. 在 `modules/` 下新建 `mod_xxx.c`：

```c
#include "fclshim.h"
#include "fclshim_log.h"

static int g_enabled;                     /* 默认关闭，这是硬要求 */

FCLSHIM_EXPORT int some_libc_function(...) {   /* 必须 FCLSHIM_EXPORT */
    if (!g_enabled) return real_semantics;     /* 未启用时保持 Android 原生语义 */
    ...
}

static int enable(void) { g_enabled = 1; return 0; }

const fclshim_module_t fclshim_mod_xxx = {   /* 描述符本身不导出 */
        .name = "xxx", .enable = enable, .note = "一句话说明",
};
```

2. 在 `fclshim.c` 的 `fclshim_builtin[]` 里登记一行；
3. 在 `jni/CMakeLists.txt` 的 `fclshim` 目标里加进源文件；
4. 把新符号加进 `check-shim-symbols.sh` 的 `ALLOW` 与 `REQUIRE`。

## 验证

```sh
# 宿主侧（不需要 NDK）：编译 + 符号契约 + 行为测试，两种模式都跑
sh tests/build-and-test.sh
```

禁止垫的符号（脚本里 `FORBID` 已列）：`mmap` `socket` `open` `dlsym` `dlopen` `malloc`
`sigaction` `syscall` 等。HotSpot 的 GC 与 JIT 重度依赖它们，垫住就是随机崩溃且极难定位。

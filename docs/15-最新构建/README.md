# 最新 FCL · 去除地区限制 · 本机构建（2026-10-24）

> 入口文件。读完这篇你应该能回答：**这包是什么、和官方/以前的包有什么不同、怎么复现、哪些坑。
> 上级索引：[00-索引-INDEX.md](../00-索引-INDEX.md)

---

## 0. 一句话结论

用 **FCL 上游 main 分支最新提交 `42f515b0`（2026-10-24 fetch）** 在本机（cccODroid / Android aarch64）从源码完整构建了 arm64 APK，并把剩余的地区相关限制一并去掉。包名改为 `com.tungsten.fcl.noregion`，可与官方 FCL（`com.tungsten.fcl`）和旧垫片版（`com.tungsten.fcl.shim`）**共存**。

| 项 | 值 |
|---|---|
| 产物 | [`APK/FCL-1.3.4.1-noregion-arm64-v8a.apk`](APK/FCL-1.3.4.1-noregion-arm64-v8a.apk) |
| 大小 / MD5 | 171 MB（179,282,498 B）· `f69120bf9ca1ed3c95a35782aa2eee47` |
| 包名 / 版本 | `com.tungsten.fcl.noregion` · versionCode 1341 · versionName `1.3.4.1-noregion` |
| 桌面名称 | `FCL 无地区限制`（与官方图标区分） |
| 架构 | arm64-v8a（单架构） |
| 签名 | 自建密钥，V2+V3（SHA-256 `f0191460…deb386`），**不能覆盖官方 FCL** |
| 源码 | `FCL-Team/FoldCraftLauncher` @ `42f515b0`（origin/main，2026-10-24） |
| 共享目录副本 | `/sdcard/cccodroid/FCL-1.3.4.1-noregion-arm64-v8a.apk` |

---

## 1. 地区限制：上游已经改过两轮，别搞混

| 时间线 | 提交 | 内容 |
|---|---|---|
| 2026-10-20 | tag `1.3.4.1` | **发布版本身没有任何地区限制**（`RegionUtils` 这个类当时还不存在） |
| 2026-10-21 | `a36e1e42` | 引入「中国大陆禁用游戏下载 + 陶瓦联机」限制，新增 `RegionUtils` 网络归属地查询（ipwho.is） |
| 2026-10-23 | `8a39ffc2` | **上游自己移除了整套限制**（PR #1955），删除联网查询、下载页受限提示、陶瓦入口禁用、协议相关文案 |

所以「最新 FCL」本身已经不含法律意义上的地区限制。本构建在此之上又做了一处清理：

| 文件 | 改动 | 原因 |
|---|---|---|
| `FCL/src/main/java/com/tungsten/fcl/terracotta/TerracottaNodeList.java` | 删除按设备地区过滤陶瓦节点（`RegionUtils.isChinaMainlandMachine()` 与节点 `region` 比较） | 否则中国大陆设备只拿到 `region=CN` 的节点；节点表里若没有 CN 节点，联机入口等于不可用 |

**仍然保留的地区相关逻辑（都不是限制，故意不改）：**

- `DownloadProviders`：大陆设备优先 BMCLAPI 镜像、其余优先 Mojang —— 这是下载速度偏好。
- `QuarkPromo`：大陆 + 中文才显示夸克网盘推广 —— 只是推广位。
- `RegionUtils.isChinaMainlandMachine()` 本身保留（时区 + 系统地区判断，不联网）。

验证：成品 dex 里 `ipwho.is` / `chinaMainlandNetwork` / `download_china` 出现次数均为 **0**。

---

## 2. 和以前的包有什么不同

| 包 | 包名 | 内容 |
|---|---|---|
| 官方 1.3.4.1 | `com.tungsten.fcl` | 上游发布版，无地区限制 |
| 本包 | `com.tungsten.fcl.noregion` | **上游 main 最新代码** + 去掉陶瓦节点地区过滤 |
| 旧 shim4 | `com.tungsten.fcl.shim` | 1.3.4.1 + 自研垫片层 + 插帧骨架（`13-…`），**不含**本包 |

本包**没有**自研插帧/垫片改动，是干净的「最新上游 + 地区补丁」。首次启动会像全新安装一样重新解压运行时；游戏目录可手动指向 `/sdcard/FCL/.minecraft`。

---

## 3. 构建环境（这台机器，2026-10-24 实测可用）

设备：Android 16 / aarch64 / 无 root，cccODroid 应用私有目录。**没有用 qemu、没有用 Ubuntu 容器，全部原生 aarch64。**

| 组件 | 版本 / 路径 |
|---|---|
| JDK | Termux openjdk-21 · `/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk` |
| Gradle | 8.14.4 · `/data/user/0/cc.codroid/files/home/workspace/fcl-build/tools/gradle` |
| Android SDK | 手工拼装 · `…/fcl-build/sdk`（platforms/android-35 + build-tools/35.0.0） |
| aapt2 | **Termux 原生** `aapt2 2.20-android-16.0.0`，通过 `android.aapt2FromMavenOverride` 替换（build-tools 里的 x86_64 aapt2 跑不了） |
| NDK | r27 (27.0.12077973)，`…/fcl-build/sdk/ndk/27.0.12077973` |
| NDK 编译器 | **Termux clang 21.1.8 包装成 NDK clang**（详见 §3.2） |
| CMake / Ninja | Termux cmake 4.4.4 · ninja 1.13.2（`local.properties` 的 `cmake.dir` 指向 `$PREFIX`） |
| 构建副本 | `…/fcl-build/src`（ASCII 路径，ext4；**不要在 /sdcard 上构建**） |

### 3.1 下载源（哪个快用哪个）

| 包 | 地址 | 备注 |
|---|---|---|
| Gradle 8.14.4 | `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.4-bin.zip` | 官方源实测 11 KB/s，腾讯镜像 ~5 MB/s |
| SDK platform-35 | `https://dl.google.com/android/repository/platform-35_r02.zip` | 解压后是 `android-35/` |
| build-tools 35 | `https://dl.google.com/android/repository/build-tools_r35_linux.zip` | 解压后是 `android-15/`，改名成 `build-tools/35.0.0` |
| NDK r27 | `https://dl.google.com/android/repository/android-ndk-r27-linux.zip` | 解压后 2.0 GB |

> SDK 里**没有 `package.xml` 也能被 AGP 识别**（靠 `source.properties` 走 legacy loader），实测通过。

### 3.2 NDK 工具链改造（本机构建的核心，换机器要重做）

NDK r27 的 `linux-x86_64` 预编译工具在本机跑不了（aarch64）。做法：保留 NDK 的 sysroot / 头文件 / libc++，把 `toolchains/llvm/prebuilt/linux-x86_64/bin` 整体换成原生包装：

1. `bin` → `bin-real`，新建 `bin`；
2. `llvm-ar / llvm-ranlib / llvm-strip / llvm-nm / llvm-objcopy / llvm-objdump / llvm-readelf / ld.lld` 全部软链到 `$PREFIX/bin` 的原生 LLVM 21；
3. `clang-18` 换成脚本，按调用名分 C/C++：
   ```sh
   exec $PREFIX/bin/clang --driver-mode=g++ \
     -resource-dir=<NDK>/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18 \
     -nostdinc++ -isystem <…>/sysroot/usr/include/c++/v1 \
     --sysroot=<…>/sysroot "$@"
   ```
   **`-resource-dir` 必须指向 NDK 的 clang-18 资源目录**：Termux clang 自己的资源目录里没有 Android 的 `libunwind.a`，不加会报 `unable to find library -l:libunwind.a`。
4. 补丁两个 NDK CMake 工具链文件（`build/cmake/android.toolchain.cmake` 与 `android-legacy.toolchain.cmake`）：CMake 在 Android 上把 `CMAKE_HOST_SYSTEM_NAME` 报成 `Android`，导致 `ANDROID_HOST_TAG` 为空、拼出 `prebuilt/bin/clang` 这种不存在的路径。加：
   ```cmake
   if(NOT ANDROID_HOST_TAG)
     set(ANDROID_HOST_TAG linux-x86_64)
   endif()
   ```
   （r27 默认走 `android-legacy.toolchain.cmake`，两个文件都加最稳。）

### 3.3 构建配置

`src/local.properties`：

```properties
sdk.dir=/data/user/0/cc.codroid/files/home/workspace/fcl-build/sdk
pwd=FCL-Build-2026
arch=arm64
cmake.dir=/data/user/0/cc.codroid/files/usr
```

`src/gradle.properties` 追加：

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.java.home=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk
kotlin.daemon.jvmargs=-Xmx2048m
org.gradle.parallel=false
android.aapt2FromMavenOverride=/data/user/0/cc.codroid/files/usr/bin/aapt2
android.builder.sdkDownload=false
org.gradle.java.installations.auto-download=false
```

> `android.builder.sdkDownload=false` 防止 AGP 试图自己去下载缺失的 SDK 组件；`parallel=false` + `--max-workers=4` 控制内存。

### 3.4 签名

仓库里提交的 `key-store.jks` 是原作者的（密码未知），`git archive` 出来的副本签名会失败。本构建**重新生成**了构建密钥：

```sh
keytool -genkeypair -keystore key-store.jks -alias FCL-Key \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass FCL-Build-2026 -keypass FCL-Build-2026 \
  -dname "CN=FCL Build, OU=cccodroid, O=FCL, L=Beijing, ST=Beijing, C=CN"
```

> `keytool` 会把别名显示成小写 `fcl-key`，Java PKCS12 别名大小写不敏感，`build.gradle.kts` 里的 `FCL-Key` 照样能读。

---

## 4. 源码改动清单（相对 origin/main `42f515b0`）

补丁都在 [`patches/`](patches/)：

| 补丁 | 文件 | 改动 |
|---|---|---|
| `0001-remove-terracotta-node-region-filter.patch` | `TerracottaNodeList.java` | 去掉按设备地区过滤陶瓦节点，所有通过校验的节点都可用 |
| `0002-noregion-build-identity.patch` | `FCL/build.gradle.kts` | `applicationId = com.tungsten.fcl.noregion`；`versionName = 1.3.4.1-noregion` |
| `0003-noregion-res-identity.patch` | `res/values/strings.xml` | `app_name = FCL 无地区限制`；`file_browser_provider = com.tungsten.fcl.noregion.provider`（必须与包名一致，否则 FileProvider 崩） |

> Manifest 里的三个 provider authority 用 `${applicationId}` 自动跟随，不用改；实测输出为
> `com.tungsten.fcl.noregion.document.provider` / `.provider` / `.crashreporterinitprovider`，与官方不冲突。

---

## 5. 构建命令

```sh
B=/data/user/0/cc.codroid/files/home/workspace/fcl-build
JAVA_HOME=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk \
  "$B/tools/gradle/bin/gradle" -p "$B/src" --console=plain \
  :FCL:assembleDebug -Darch=arm64 --max-workers=4
```

- 首次（含配置、下载依赖、CMake 原生编译）：约 20 分钟（配置 4m14s + 构建 14m55s，其中原生 CMake 一次通过）
- 改包名后的增量：4m32s；再改签名后重打包：11s
- 原生产物：`libpojavexec.so`、`libfcl.so`、`libvkshim.so`、`libjsound.so`、`libflite*.so` 等 **全部本机编译**，不是从官方 APK 拆的
- 产物：`$B/src/FCL/build/outputs/apk/debug/FCL-debug-1.3.4.1-noregion-arm64-v8a.apk`

一键重建：[`scripts/rebuild.sh`](scripts/rebuild.sh)（重新导出 origin/main → 打 3 个补丁 → 构建；需要 §3 的工具链已就位）。

---

## 6. 验证记录

| 项 | 结果 |
|---|---|
| 构建 | ✅ `BUILD SUCCESSFUL`（arm64 单架构） |
| 签名 | ✅ `apksigner verify` V3 通过，证书 SHA-256 `f01914602a056dd7c1b1aa1d085904bb9b6ecf7bdfc1c533c10f259de0deb386` |
| 包信息 | ✅ `com.tungsten.fcl.noregion` / 1341 / `1.3.4.1-noregion` / `FCL 无地区限制` / native-code arm64-v8a |
| 原生库 | ✅ 37 个 arm64 .so，含本机编译的 `libpojavexec.so`、`libc++_shared.so`、`libbytehook.so` |
| 地区代码 | ✅ dex 中 `ipwho.is`=0、`chinaMainlandNetwork`=0、`download_china`=0 |
| Provider | ✅ 三个 authority 均带 `.noregion` 前缀 |
| 真机安装/启动 | ❌ **未验证**（没有自动安装；需要你手动点装） |

---

## 7. 已知限制与注意事项

1. **debug 构建**：`debuggable=true`、未跑 checkstyle/lint；release 构建可另行 `:FCL:assembleRelease`，但需要同样的 key-store 配置。
2. **签名独立**：不能覆盖官方 FCL；包名不同所以可以共存。三个 FCL 图标分别对应：官方 `com.tungsten.fcl`、垫片 `…shim`、本包 `…noregion`。
3. **数据独立**：新包名的 `Android/data/com.tungsten.fcl.noregion/` 是全新的，首次启动要重新解压运行时（JRE 等）。要复用旧世界，把游戏目录指到 `/sdcard/FCL/.minecraft`。
4. **只编了 arm64-v8a**：`-Darch=arm64`。需要其他架构改 `-Darch=arm` / `x86` / `x86_64`，但 NDK 包装目前只验证了 arm64。
5. **上游继续更新时**：三个补丁基于 `42f515b0` 生成；如果 `TerracottaNodeList` 或 `strings.xml` 被上游改动，`patch` 可能冲突，需要手动对齐。
6. **不要清理 `…/fcl-build/`**：SDK、NDK 包装、Gradle 都在里面（约 5 GB）。删了要照 §3 重下重配。
7. **内置更新器不认识本包**：它按 versionCode 比较（当前 1341 = 最新），目前不会提示；将来上游发新版本时，它下载的是官方 `com.tungsten.fcl` 安装包，装上去是另一个 App，不会升级本包。到时用 `scripts/rebuild.sh` 重新构建，或在设置里忽略更新。

---

## 8. 目录内容

```
15-最新构建-Latest-Build/
├── README.md                     ← 本文件
├── APK/
│   └── FCL-1.3.4.1-noregion-arm64-v8a.apk
├── patches/                      ← 相对 origin/main 的三个补丁
├── scripts/
│   ├── rebuild.sh                ← 一键重建
│   ├── local.properties.example
│   └── gradle.properties.append
└── (构建日志在 …/fcl-build/logs/)
```

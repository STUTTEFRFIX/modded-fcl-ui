# 构建说明

> 目标：复现 `FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk`。
> 本机是 Android aarch64、无 root，工具链细节见 [`docs/15-最新构建/README.md`](docs/15-最新构建/README.md) §3。

---

## 1. 两种源码基线

| 基线 | 用途 | 是否包含关系图 |
|---|---|---|
| `source/` 快照 | 当前工作树，含插帧/垫片实验 | 是（未提交改动） |
| 干净上游 + 补丁 | 实际发布 APK 使用的基线 | 通过 `patches/feature-mod-graph.patch` 加入 |

发布 APK 的基线：

```text
FCL-Team/FoldCraftLauncher @ origin/main 42f515b0
+ patches/noregion/0001-remove-terracotta-node-region-filter.patch
+ patches/noregion/0002-noregion-build-identity.patch
+ patches/noregion/0003-noregion-res-identity.patch
+ patches/feature-mod-graph.patch
```

`patches/feature-mod-graph.patch` 是相对工作树 `feat/framegen-host` 的 HEAD 生成的；上游 `42f515b0` 在 `ModListPage.java` / `strings.xml` 附近有小改动，必要时手工对齐上下文。`source/` 里已有关系图代码，**不要重复打这个补丁**。

---

## 2. 打补丁

```sh
# 从一个干净的上游检出开始
cd /path/to/FoldCraftLauncher
patch -p1 < /path/to/repo/patches/noregion/0001-remove-terracotta-node-region-filter.patch
patch -p1 < /path/to/repo/patches/noregion/0002-noregion-build-identity.patch
patch -p1 < /path/to/repo/patches/noregion/0003-noregion-res-identity.patch
patch -p1 < /path/to/repo/patches/feature-mod-graph.patch
```

如果关系图补丁在 `ModListPage.java` 或 `strings.xml` 上报 context 冲突，按
[`docs/17-模组关系图/README.md`](docs/17-模组关系图/README.md) §3 的文件清单手工合并；新增文件可直接从补丁里取。

---

## 3. 本机工具链（cccODroid）

已就位的路径：

```text
Gradle 8.14.4 : /data/user/0/cc.codroid/files/home/workspace/fcl-build/tools/gradle
Android SDK   : /data/user/0/cc.codroid/files/home/workspace/fcl-build/sdk
NDK r27       : /data/user/0/cc.codroid/files/home/workspace/fcl-build/sdk/ndk/27.0.12077973
JDK 21        : /data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk
构建副本      : /data/user/0/cc.codroid/files/home/workspace/fcl-build/src
```

`local.properties` / `gradle.properties` 样例在 [`scripts/`](scripts/)。
**不要在 `/sdcard` 上跑 Gradle**，也不要在 `/sdcard` 上 `npm install`/编译；构建副本放在应用私有 ext4 目录。

```sh
B=/data/user/0/cc.codroid/files/home/workspace/fcl-build
JAVA_HOME=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk \
  "$B/tools/gradle/bin/gradle" -p "$B/src" --console=plain \
  :FCL:assembleDebug -Darch=arm64 --max-workers=4
```

输出：

```text
$B/src/FCL/build/outputs/apk/debug/FCL-debug-1.3.4.1-noregion-arm64-v8a.apk
```

---

## 4. 测试

```sh
JAVA_HOME=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk \
  "$B/tools/gradle/bin/gradle" -p "$B/src" --console=plain \
  :FCL:testDebugUnitTest --tests "com.tungsten.fclcore.mod.*" \
  -Darch=arm64 --max-workers=4
```

当前结果：

```text
ModGraphBuilderTest        tests=6  failures=0  errors=0
ModDependencyParsingTest   tests=4  failures=0  errors=0
```

---

## 5. 签名与包名

- 构建类型：`debug`，包名 `com.tungsten.fcl.noregion`，版本 `1.3.4.1-noregion`；
- 使用本机构建密钥（不随仓库分发）；
- 与官方 `com.tungsten.fcl` 签名不同，不能覆盖安装，但可以共存；
- 正式分发前请换 release keystore，并遵守 GPLv3。

---

## 6. 已知构建坑

1. 上游 `debug` 构建的 `applicationId` 通过 `patches/noregion/0002` 改成 `.noregion`；资源标识（`app_name`、FileProvider authority）必须同步，见 `0003`。
2. `d8` 必须包含所有匿名内部类 `.class`（上游脚本已处理）。
3. 本机没有 `android.jar` 时，`aapt2 link` 可用设备自带的 `framework-res.apk` 顶替；完整构建建议用手拼 SDK。
4. 原生库需要 NDK r27 的原生 aarch64 包装；细节见 `docs/15-最新构建/README.md` §3.2。
5. 关系图补丁与上游的 `ModListPage.java` / `strings.xml` 上下文可能冲突，优先手工合并，不要强行 `--fuzz`。

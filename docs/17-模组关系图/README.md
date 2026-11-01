# FCL 模组关系图（Mod Graph）

> 日期：2026-11-01  
> 目标：把 Modpack Studio 的「整合包依赖图」能力接入 FCL，读取玩家正在玩的实例，在模组管理页看到依赖分布。  
> 状态：**代码已实现、编译通过、单元测试通过、APK 已构建并安装**。真机首轮截图显示力导向布局过挤，已改为**分层依赖布局 + 横屏侧栏**并重新出包；最终验证待重新打开关系图确认。

---

## 1. 用户看到什么

打开某个版本的「管理模组」页：

- 左侧按钮栏新增 **「关系图」**（在「检查更新」和「排序」之间）。
- 列表顶部新增一行分布统计：
  `95 个模组 · 71 条关系 · 4 枢纽 · 27 孤立 · 0 缺失前置`
- 点「关系图」打开全屏对话框：
  - 原生 Canvas **分层依赖图**：没有前置的模组在右，依赖它的模组依次向左；节点大小 = 被依赖数；
  - 实线 = 必需前置，虚线 = 可选联动，红色虚线 = 冲突，虚线空心 = 缺失前置/已禁用；
  - 拖动平移、双指缩放、双击适配、点节点高亮邻居并在底部显示详情；
  - 横屏时图形占满左侧，标题/统计/筛选/图例/详情收进右侧 300dp 侧栏；竖屏时上下排布；
  - 「隐藏孤立」筛选；
  - 「Modrinth 补全」默认开启：按 jar 的 SHA-1 反查 Modrinth，补分类、简介、图标，节点按分类着色，底部图例显示分类；
  - 中文界面下节点名优先用 FCL 自带的 mcmod.cn 译名（`ModTranslations`），缺失前置也会尝试查译名表。

数据来源是**实例本身**（`ModManager` 扫描 `mods/` 目录），不是 `.mrpack` 文件；因此手动装的 jar、禁用文件、启动器导出包都能读。

---

## 2. 架构与数据流

```text
ModListPage.loadMods()
  → ModManager.refreshMods(onScanned)
  → 每个 LocalModFile（新增 dependencies 字段）
  → ModGraphBuilder.build(List<Input>)
  → ModGraph { nodes, edges, stats }
        ├─ ModListPage：顶部统计条
        └─ ModGraphDialog
              ├─ ModGraphView（Canvas：布局/绘制/手势）
              │     └─ ModGraphLayout（后台线程分层布局）
              └─ Modrinth 在线补全（分类/简介/图标）
```

### 2.1 依赖数据层

新增 `ModDependency`（targetId / type / versionRange），并让 `LocalModFile` 携带 `List<ModDependency>`。四条解析器补齐：

| 解析器 | 来源 | 处理 |
|---|---|---|
| `FabricModMetadata.kt` | `fabric.mod.json` | `depends` → 必需，`recommends` → 可选，`breaks`/`conflicts` → 冲突；依赖表用宽松序列化器，非对象形态当空表 |
| `QuiltModMetadata.kt` | `quilt.mod.json` | `quilt_loader.depends/recommends/breaks`，元素兼容字符串与 `{id, versions}` 对象 |
| `ForgeNewModMetadata.kt` | `META-INF/(neoforge.)mods.toml` | 直接从 `TomlParseResult` 读 `dependencies.<owner>`；`mandatory=false`/`type=optional` → 可选，`type=incompatible` → 冲突；兼容 `[[dependencies]]` 非标准写法，单条字段异常不拖垮整个模组 |
| `ForgeOldModMetadata.kt` | `mcmod.info` | `dependencies` 字符串数组 → 必需；字符串/数组两种形态都接受 |

老 Forge（1.12 及以前）很多模组不声明依赖，图上会显示为孤立节点，这是数据本身的限制。

### 2.2 图构建规则（`ModGraphBuilder`）

- mod id 统一小写；
- 同一 id 的多个文件（`.jar` / `.jar.disabled` / `.old`）只保留一个，**启用优先**；`.old` 由 UI 过滤；
- 跳过游戏/加载器内置 id：`minecraft`、`java`、`forge`、`neoforge`、`fabricloader`、`quilt_loader`、`javafml` 等；
- 必需前置不在实例里 → 生成「缺失前置」节点；
- 可选/冲突目标不在实例里 → 不画边；
- `(source, target, type)` 三元组去重；
- 度数、枢纽（入度 ≥ 3）、孤立（度为 0）在构建时算好。

`ModGraphBuilder` 不依赖 Android/UI，可在 JVM 单元测试里直接构造输入。

### 2.3 布局（`ModGraphLayout`）

力导向在模组依赖图上很容易挤成一团：一个节点连十几条边，斥力和弹簧互相打架，标签会叠在一起。真机首轮截图就是这个现象，所以改成**按依赖深度分层**：

- 没有前置的节点（包括缺失前置）在第 0 层，放在最右；依赖它的节点依次向左，边从左指向右；
- 每层内先按被依赖数排序，再做 4 轮 barycenter 扫描（前置对齐下游、上游对齐前置），减少连线交叉；
- 孤立节点单独排在主图下方一排，不插进依赖链中间；
- 环按 0 层处理，深度上限 12 层；
- 复杂度 O(V+E)，几百个节点也不需要迭代几百轮。

标签绘制在屏幕坐标里：优先枢纽/选中/搜索命中，按矩形碰撞避让，最多 80 个；缩得很小时只保留枢纽和选中节点。横屏 `layout-land/dialog_mod_graph.xml` 把图形放在左侧主区域，右侧 300dp 侧栏放标题、统计、筛选、图例和详情。

### 2.4 在线补全（Modrinth）

- 打开对话框、布局计算完成后才开始，避免和布局计算抢 CPU；
- 每个非缺失节点调用 `RemoteMod.Type.MODRINTH.remoteModRepository.getRemoteVersionByLocalFile()`，命中后 `getModById()` 拿分类/简介/图标；
- FCL 已有的 `RemoteModCache` 会按 SHA-1 永久缓存版本命中/未命中，详情缓存 24h，第二次打开同一个包基本不再请求；
- 单条失败直接跳过；完全离线时图仍然可用，只是没有分类颜色。

---

## 3. 改动文件

### 工作区源码

`/storage/emulated/0/DeepSeek agent/FCL 库/FoldCraftLauncher/`

新增：

- `FCL/src/main/java/com/tungsten/fclcore/mod/ModDependency.java`
- `FCL/src/main/java/com/tungsten/fclcore/mod/graph/ModGraph.java`
- `FCL/src/main/java/com/tungsten/fclcore/mod/graph/ModGraphBuilder.java`
- `FCL/src/main/java/com/tungsten/fcl/ui/manage/graph/ModGraphDialog.kt`
- `FCL/src/main/java/com/tungsten/fcl/ui/manage/graph/ModGraphLayout.kt`
- `FCL/src/main/java/com/tungsten/fcl/ui/manage/graph/ModGraphView.kt`
- `FCL/src/main/res/layout/dialog_mod_graph.xml`
- `FCL/src/main/res/layout-land/dialog_mod_graph.xml`
- `FCL/src/test/java/com/tungsten/fclcore/mod/graph/ModGraphBuilderTest.java`
- `FCL/src/test/java/com/tungsten/fclcore/mod/modinfo/ModDependencyParsingTest.kt`

修改：

- `FCL/src/main/java/com/tungsten/fclcore/mod/LocalModFile.java`
- `FCL/src/main/java/com/tungsten/fclcore/mod/modinfo/FabricModMetadata.kt`
- `FCL/src/main/java/com/tungsten/fclcore/mod/modinfo/ForgeNewModMetadata.kt`
- `FCL/src/main/java/com/tungsten/fclcore/mod/modinfo/ForgeOldModMetadata.kt`
- `FCL/src/main/java/com/tungsten/fclcore/mod/modinfo/QuiltModMetadata.kt`
- `FCL/src/main/java/com/tungsten/fcl/ui/manage/ModListPage.java`
- `FCL/src/main/res/layout/page_manage_mod.xml`
- `FCL/src/main/res/values/strings.xml`
- `FCL/src/main/res/values-zh/strings.xml`

### 完整补丁

`/storage/emulated/0/DeepSeek agent/17-模组关系图-Mod-Graph/feature-mod-graph.patch`

补丁相对于工作区当前 HEAD（`feat/framegen-host`，含自研插帧/垫片提交）生成，包含新增文件。应用：

```sh
cd /path/to/FoldCraftLauncher
patch -p1 < feature-mod-graph.patch
```

注意：工作区 HEAD 的主分支比上游 `origin/main` 旧；`ModListPage.java` 和 `strings.xml` 在上游有小改动，把补丁应用到更新后的上游时可能需要手工对齐上下文。

---

## 4. 构建与测试

本机构建副本：`/data/user/0/cc.codroid/files/home/workspace/fcl-build/src`  
它由 `origin/main` 导出 + 三个「无地区限制」补丁，再把本功能文件同步进去。

### 4.1 单元测试

```sh
B=/data/user/0/cc.codroid/files/home/workspace/fcl-build
JAVA_HOME=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk \
  "$B/tools/gradle/bin/gradle" -p "$B/src" --console=plain \
  :FCL:testDebugUnitTest --tests "com.tungsten.fclcore.mod.*" \
  -Darch=arm64 --max-workers=4
```

结果（2026-11-01）：

```text
ModGraphBuilderTest        tests=6  failures=0  errors=0
ModDependencyParsingTest   tests=4  failures=0  errors=0
```

覆盖：必需/可选/冲突/缺失边、内置 id 过滤、重复 id/重复边去重、启用优先、枢纽/孤立、大小写归一化、Fabric/Quilt/Forge 依赖解析、Forge 非标准 `[[dependencies]]` 不破坏元数据。

### 4.2 APK

```sh
B=/data/user/0/cc.codroid/files/home/workspace/fcl-build
JAVA_HOME=/data/data/cc.codroid/files/usr/lib/jvm/java-21-openjdk \
  "$B/tools/gradle/bin/gradle" -p "$B/src" --console=plain \
  :FCL:assembleDebug -Darch=arm64 --max-workers=4
```

产物：

```text
/data/user/0/cc.codroid/files/home/workspace/fcl-build/src/FCL/build/outputs/apk/debug/FCL-debug-1.3.4.1-noregion-arm64-v8a.apk
```

共享目录副本：

```text
/storage/emulated/0/cccodroid/FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk
```

已用 `priv pm install -r` 安装，包名 `com.tungsten.fcl.noregion`（与官方 `com.tungsten.fcl` 共存）。

### 4.3 真机测试数据

为了验证图数据，临时的测试 jar 放进了：

```text
/storage/emulated/0/FCL/.minecraft/versions/1.21.4-NeoForge/mods/
```

内容：

| 文件 | 用途 |
|---|---|
| `jei.jar` | 真实 NeoForge 模组；Jade 对它是 optional |
| `jade.jar` | 真实 NeoForge 模组 |
| `appleskin.jar` | 真实 NeoForge 模组 |
| `architectury.jar` | 真实 NeoForge 前置库 |
| `cloth-config.jar` | 真实 NeoForge 前置库 |
| `graph-test-addon.jar` | 自制的合成模组：必需 `jei`、可选 `jade`、必需但不存在的 `not_installed_lib`、内置 `neoforge`/`minecraft` |

删除方法（把临时测试数据清掉，恢复空 mods 目录）：

```sh
rm -f "/storage/emulated/0/FCL/.minecraft/versions/1.21.4-NeoForge/mods/"*.jar
```

---

## 5. 已知限制

1. **老版本依赖不全**：1.12 及以前 Forge 的 `mcmod.info` 常常不写依赖，图上会有较多孤立节点。UI 不应把它当成 bug。
2. **没有分类时是灰的**：离线只能按图角色（枢纽/孤立/缺失/禁用）区分；分类颜色依赖 Modrinth 在线补全。不在 Modrinth 的模组保持灰色。
3. **Modrinth 首次补全可能较慢**：每个模组一次版本查询 + 一次项目查询，95 个模组约十几秒到几十秒；`RemoteModCache` 之后会命中缓存。大包可能触及匿名 API 速率限制，失败会被静默跳过。
4. **分层布局不是力导向**：依赖深度相同的节点会排成一列；链路很深的包会横向拉得很长，需要缩放查看。这是为了可读性做的取舍。
5. **没有分类扇区/树视图**：当前是依赖深度分层 + 分类着色；Modpack Studio 的分类扇区布局和树视图尚未移植。
6. **别名不解析**：少数模组声明依赖 `fabric` 而实际安装的 id 是 `fabric-api`，会显示为缺失前置；`provides` 别名解析留待后续。
7. **没有编辑/导出**：只读视图，不修改实例。

---

## 6. 后续可做

- 分类扇区布局：在线分类返回后把节点向分类扇区中心拉，重排一次（Modpack Studio 的 `recomputeCenters`）。
- 树视图：包 → 分类 → mod，复用已有 `ModGraph` 数据。
- 行内徽标：在 `item_local_mod.xml` 的模组行显示「枢纽/孤立/缺失前置」。
- 别名与 `provides` 解析：Fabric/Quilt 的 `provides` 字段。
- 批量 Modrinth 查询：用 `POST /v2/version_files` 一次查 500 个 SHA-1，减少请求数。
- 导出 PNG / 分享关系图。

---

## 7. 变更记录

| 时间 | 内容 |
|---|---|
| 2026-11-01 上午 | 首版：力导向布局 + 全屏竖排对话框。真机截图显示 60 个模组挤成一小团、标签重叠，用户反馈「图的排版有大问题了」 |
| 2026-11-01 中午 | 改为按依赖深度分层布局；横屏新增 `layout-land` 侧栏布局（图形占满左侧，控件收进右侧 300dp）；标签增加 2dp 碰撞边距、最多 80 个、缩放阈值；重新构建并安装 |


---

## 8. 私有仓库与发布

- 仓库：[`STUTTEFRFIX/modded-fcl-ui`](https://github.com/STUTTEFRFIX/modded-fcl-ui)（private）
- 源码快照：`source/`；完整补丁：`patches/feature-mod-graph.patch`
- Release：
  - [`v1.3.4.1-modgraph`](https://github.com/STUTTEFRFIX/modded-fcl-ui/releases/tag/v1.3.4.1-modgraph)（带关系图）
  - [`v1.3.4.1-noregion`](https://github.com/STUTTEFRFIX/modded-fcl-ui/releases/tag/v1.3.4.1-noregion)（基线）
- 构建与校验：仓库根目录 `BUILD.md`、`builds/SHA256SUMS`

# 魔改 FCLUI 方案（私有仓库）

> **私有仓库，不公开。** 基于 [Fold Craft Launcher](https://github.com/FCL-Team/FoldCraftLauncher) 的私人改造与实验记录。

当前主要内容：

- **FCL 模组关系图**：读取玩家当前实例的 `mods/`，在「管理模组」页显示依赖分布，并提供原生 Canvas 关系图。
- **无地区限制构建**：`com.tungsten.fcl.noregion` / `1.3.4.1-noregion`，arm64。
- 本机（Android aarch64）源码构建工具链与补丁。
- 本地实验分支：插帧宿主、JVM 垫片层（`source/` 快照里包含，但当前 APK 未启用）。

---

## 1. 模组关系图

打开某个版本的「管理模组」页：

- 左侧新增 **「关系图」** 按钮；
- 列表顶部显示 `60 个模组 · 80 条关系 · 8 枢纽 · 12 孤立 · 14 缺失前置`；
- 点开后是原生 Canvas 依赖图：
  - 按依赖深度分层：没有前置的节点在右，依赖它的节点依次向左；
  - 实线 = 必需前置，虚线 = 可选联动，红色虚线 = 冲突，虚线空心 = 缺失/禁用；
  - 拖动、双指缩放、双击适配、点节点高亮邻居；
  - Modrinth 在线补全分类/简介，节点按分类着色；
  - 中文界面优先用 FCL 内置的 mcmod.cn 译名。

详细架构、数据流、改动文件与限制见
[`docs/17-模组关系图/README.md`](docs/17-模组关系图/README.md)。

> 真机首轮截图发现最初的力导向布局会把节点挤成一团；现已改为**按依赖深度分层**，并新增横屏侧栏布局。最终效果待复验。

---

## 2. 仓库结构

```text
.
├── source/                     # FCL 工作树快照（不含 .git/build/签名）
├── docs/
│   ├── 17-模组关系图/          # 本功能架构、实现、测试、限制
│   └── 15-最新构建/            # 无地区限制构建的来龙去脉与本机工具链
├── patches/
│   ├── feature-mod-graph.patch # 模组关系图完整补丁（含新增文件）
│   └── noregion/               # 0001~0003：陶瓦节点过滤/包名/资源标识
├── builds/                     # APK 校验和与 Release 说明（APK 在 Releases）
├── scripts/                    # 本机构建脚本与配置样例
└── test-data/                  # 合成测试模组（关系图数据验证）
```

### 关于 `source/`

`source/` 是 2026-11-01 工作树的**文件快照**，不是 git 历史：

- 基于 FCL `feat/framegen-host`（上游 main + 自研插帧/垫片提交）；
- 包含未提交的模组关系图改动；
- 已排除 `.git`、`build/`、`.gradle/`、`local.properties` 和所有签名密钥。

**当前发布的 modgraph APK 不是直接用 `source/` 编出来的**。它由干净的上游 `origin/main 42f515b0` + `patches/noregion/0001~0003` + `patches/feature-mod-graph.patch` 构建，避免把插帧/垫片实验混进发布包。复现步骤见 [`BUILD.md`](BUILD.md)。

---

## 3. 构建版本

APK 作为 Release 附件发布（不放进 git 树，避免仓库膨胀）：

- `v1.3.4.1-modgraph`：带模组关系图的版本
- `v1.3.4.1-noregion`：上一个基线版本（无关系图）

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk` | 174 MB | `0c51b4d7915d4f080ed07345a06aa1285b3f4b23f5dfca4c3e5a656ac51a46b1` |
| `FCL-1.3.4.1-noregion-arm64-v8a.apk` | 171 MB | `1498f0df0dcee83550dd397a5922891cff79faec91f3a3ee2d36b497ee33ecad` |

- 包名：`com.tungsten.fcl.noregion`（与官方 `com.tungsten.fcl` 共存）
- 版本：`1.3.4.1-noregion`，arm64-v8a，debug 签名
- 下载：[Releases](https://github.com/STUTTEFRFIX/modded-fcl-ui/releases)

---

## 4. 文档

- [模组关系图：架构与实现](docs/17-模组关系图/README.md)
- [无地区限制构建与工具链](docs/15-最新构建/README.md)
- [构建说明](BUILD.md)
- [变更记录](CHANGELOG.md)
- [补丁说明](patches/README.md)
- [测试数据](test-data/graph-test-addon/README.md)

---

## 5. 许可证与来源

- 本仓库基于 **Fold Craft Launcher**，上游以 **GPLv3** 发布；`source/LICENSE` 为上游许可证。
- 模组名称、图标、介绍文本版权归各模组作者所有。
- Modrinth API 与公开元数据按其服务条款使用。
- 本仓库为私有仓库；如需再分发，请遵守 GPLv3 与上游项目的要求。

# 参考项目：Modpack Studio

> 上游：<https://github.com/STUTTEFRFIX/modpack-studio>（public）
> 许可：MIT · Copyright (c) 2026 Modpack Studio contributors
> 参考版本：`05499fe`（`0.4.1-tree-svg`，2026-11-01 记录时的 main）
> 定位：FCL 模组关系图的**思路与规则来源**，不是本仓库的依赖，也不随本仓库分发源码。

---

## 1. 它是什么

面向 Minecraft 整合包作者的策展工作台：读取 `.mrpack`、CurseForge zip 或启动器实例，解析模组、分类、依赖与联动，生成可交互的关系图。

- 技术栈：Go 解析器（`cmd/`、`internal/pack`）+ 内嵌 HTML/JS 图（`internal/webui/graph.html`）+ Android WebView APK + 桌面 HTTP 壳；
- 核心主张「图优先」：对整合包作者来说，依赖/联动关系比分类树更有用——谁是枢纽、谁孤立、谁缺前置，一眼可见；
- 还支持树视图（包 → 分类 → mod）、中文译名、搜索筛选、Modrinth 按哈希补全、`.mrpack` / CurseForge 导入；
- 当前版本 `0.4.1-tree-svg`（alpha）。

---

## 2. 与 FCL 模组关系图的关系

FCL 的「模组关系图」把这个项目的**整合包依赖图思路**移植到启动器内的实例视图：数据源从整合包文件换成玩家已安装实例的 `mods/` 目录，实现换成 Java/Kotlin + Android 原生 Canvas。

| 方面 | Modpack Studio | FCL |
|---|---|---|
| 数据来源 | 整合包文件（`.mrpack` / CurseForge zip / 启动器实例） | 已安装实例的 `mods/`（`ModManager` 扫描） |
| 实现 | Go 解析器 + 浏览器 JS（`graph.html`） | Java/Kotlin + Android 原生 Canvas |
| 布局 | 力导向 + 分类中心吸引；另有树视图 | 按依赖深度分层（FCL 的取舍，见 `docs/17` §2.3） |
| 分类/简介 | Modrinth / CurseForge API | Modrinth 按 SHA-1 反查，走 FCL 的 `RemoteModCache` |
| 中文名 | 人工维护的 `zh_names.json` | FCL 内置 mcmod.cn 译名表（`ModTranslations`） |

**代码层面**：FCL 侧是独立的 Java/Kotlin 实现，没有直接复制 Modpack Studio 的 Go/JS 源码。`ModGraphBuilder` 的类注释写明「规则与 Modpack Studio 的图构建保持一致」；参照的是规则与交互设计，而不是文件级移植。上游仍以 MIT 发布，许可与署名见第 4 节。

### 2.1 已采用的规则与设计

- 节点大小 = 包内被依赖数（入度），枢纽一眼可见；
- 边类型：实线 = 必需前置，虚线 = 可选联动，红色虚线 = 冲突，虚线空心 = 缺失/已禁用；
- 统计口径：`模组 · 关系 · 枢纽 · 孤立 · 缺失前置`；
- 枢纽定义为入度 ≥ 3，孤立定义为度为 0；
- 游戏/加载器内置 id 不建节点；同一 mod id 的多个文件只保留一个（启用优先）；`(source, target, type)` 三元组去重；
- 必需前置缺失时生成「缺失前置」节点，可选/冲突目标缺失时不画边；
- 分类着色 + Modrinth 按文件哈希补全 + 中文译名优先；
- 点节点查看直接前置、前置的前置、被依赖列表。

### 2.2 尚未移植的部分

- 分类扇区布局（在线分类返回后把节点向分类扇区中心拉，上游的 `recomputeCenters`）；
- 树视图（包 → 分类 → mod）；
- `.mrpack` / CurseForge 导入（FCL 直接读实例，不需要导入包文件）；
- 力导向布局：真机首轮截图显示节点挤成一团，FCL 改为按依赖深度分层（`docs/17` §2.3、§7）。

---

## 3. 如何取得参考版本

```sh
git clone https://github.com/STUTTEFRFIX/modpack-studio.git
cd modpack-studio
git checkout 05499fe
```

建议先读的上游文件：

| 文件 | 内容 |
|---|---|
| `internal/webui/graph.html` | 关系图前端实现（图/树切换、筛选、详情、导入） |
| `experiments/graph-demo/` | 图数据管线与 Python 原型 |
| `docs/graph-demo.md` | 「图优先」原型的论证、Create Live 5 数据与结论 |
| `docs/整合包工作台实现计划.md` §14 | 依赖图规则（直接前置、传递闭包、反向依赖、缺失） |
| `internal/model/model.go` | 跨格式数据模型（Mod / Dependency / Stats） |
| `docs/screenshots/` | 桌面、竖屏、横屏、详情截图 |

> 上游仓库不包含示例 `.mrpack` 与模组 jar，只记录下载地址；示例图中的模组名称、图标、介绍版权归各模组作者。

---

## 4. 许可与署名

Modpack Studio 以 **MIT License** 发布，原始版权声明：

```text
MIT License

Copyright (c) 2026 Modpack Studio contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

许可证原文另存为 [`LICENSE-upstream.txt`](LICENSE-upstream.txt)。

- 本仓库整体基于 Fold Craft Launcher（GPLv3）；MIT 与 GPLv3 兼容，可以共存；
- 当前 FCL 侧为独立重写，未复制上游源码；若后续从上游复制代码、数据或大段文案，必须保留上述版权与许可声明，并在 [`THIRD_PARTY.md`](../../THIRD_PARTY.md) 登记；
- 模组元数据、图标、介绍文本版权归各模组作者；Modrinth API 按其服务条款使用。

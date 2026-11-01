# 变更记录

## 2026-11-01

### 模组关系图

- 新增 `ModDependency`，`LocalModFile` 携带依赖列表。
- Fabric / Quilt / Forge 1.13+ / NeoForge / 老 Forge 解析器补齐依赖声明。
- 新增 `ModGraphBuilder`：内置 id 过滤、重复 id 去重、缺失前置节点、度数/枢纽/孤立统计。
- 新增 `ModGraphView` / `ModGraphDialog`：原生 Canvas、平移缩放、点选高亮、标签避让。
- 模组管理页新增「关系图」入口和顶部分布统计。
- Modrinth 在线补全分类/简介/图标（SHA-1 反查，FCL `RemoteModCache` 缓存）。
- 中文界面优先使用 `ModTranslations` 的 mcmod.cn 译名。
- 布局：首版力导向在真机上挤成一团；改为按依赖深度分层，并新增横屏 `layout-land` 侧栏布局。
- 测试：`ModGraphBuilderTest` 6 项 + `ModDependencyParsingTest` 4 项，全部通过。
- 构建：`FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk`（174 MB）。

### 文档

- 记录参考项目 Modpack Studio（MIT，参考提交 `05499fe`）：新增 `docs/18-参考项目-Modpack-Studio/` 与 `THIRD_PARTY.md`，README 补充来源与署名。

## 2026-10-24

- 在本机从源码完整构建 FCL，去掉陶瓦节点地区过滤，包名 `com.tungsten.fcl.noregion`。
- 产物：`FCL-1.3.4.1-noregion-arm64-v8a.apk`（171 MB）。
- 详细过程见 `docs/15-最新构建/README.md`。

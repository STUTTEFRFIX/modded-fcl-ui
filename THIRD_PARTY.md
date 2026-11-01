# 第三方组件与参考项目

本仓库基于 Fold Craft Launcher，并参考了 Modpack Studio 的整合包依赖图设计。这里集中登记许可与来源，便于审计与再分发。

## Fold Craft Launcher

- 上游：<https://github.com/FCL-Team/FoldCraftLauncher>
- 许可证：**GPLv3**；`source/LICENSE` 为上游许可证原文。
- `source/` 是上游工作树的文件快照，版权归 FCL 贡献者；本仓库的修改同样按 GPLv3 分发。

## Modpack Studio

- 上游：<https://github.com/STUTTEFRFIX/modpack-studio>（public）
- 许可证：**MIT** · Copyright (c) 2026 Modpack Studio contributors
- 参考版本：`05499fe`（`0.4.1-tree-svg`，2026-11-01）
- 关系：FCL 模组关系图的交互与数据规则参考该项目（节点大小 = 入度、枢纽/孤立/缺失前置、边类型、Modrinth 哈希补全、中文译名优先等）；FCL 侧为独立的 Java/Kotlin 实现，未复制上游 Go/JS 源码。
- 详细对照与许可证原文：`docs/18-参考项目-Modpack-Studio/`。

## API 与数据

- **Modrinth API** — <https://docs.modrinth.com/api/>。在线补全按文件 SHA-1 反查版本与项目，按其服务条款使用。
- **mcmod.cn 译名** — 中文模组名优先使用 FCL 内置的 `ModTranslations` 译名表。
- 模组名称、图标、介绍文本版权归各模组作者所有。

## Minecraft

Minecraft 是 Mojang Studios / Microsoft 的商标。本仓库与 Mojang / Microsoft 无关联，不包含 Minecraft 游戏文件。

## 再分发

本仓库为私有仓库。如需再分发 `source/`、APK 或补丁，请同时遵守 GPLv3、MIT（Modpack Studio 相关署名）以及各数据来源的服务条款。

# UI 设计系统 · 变更记录

> 版本语义见 [`06-governance-and-checklist.md`](06-governance-and-checklist.md) §3。
> 日期使用绝对日期；破坏性变更标 **BREAKING**。

---

## 2.0.0 — 2026-11-05

### Added

- 新增 **V2 纸质**设计系统：暖灰纸 + 墨线 + 苔墨；明信片主视觉、乳白玻璃灵动岛、纸质底栏。
- 新增 `docs/19-UI设计规范-Design-System/`：
  - `README.md`：总纲、十项已定决策、未决问题、十条硬规则、接手路线。
  - `01-foundations.md`：色彩、字体、间距、圆角、材质、图标、动效、纸纹/插画。
  - `02-components.md`：灵动岛、底栏、明信片、世界行、按钮、chip 的尺寸/状态/无障碍。
  - `03-layout-and-responsive.md`：390×844 基准、纵向节奏、安全区、滚动、断点、横屏策略、字号缩放。
  - `04-accessibility-and-content.md`：对比度实测、触控、焦点、读屏、降级、文案、空/载/错。
  - `05-implementation-handover.md`：现状、Android 映射、构建/验证、风险、下一步、接手清单。
  - `06-governance-and-checklist.md`：真源、变更流程、版本、评审清单、新页面配方、Do/Don't。
- 新增 `design-tokens.css` / `design-tokens.json`：V2 颜色、字体、间距、圆角、布局、动效、组件尺寸、对比度。
- 新增可运行 HTML 原型：
  - `prototypes/home-paper-v2.html`（当前基准）
  - `prototypes/home-dark-v1.html`（V1 存档）
- 新增截图：
  - `screenshots/home-paper-v2.png`（390×844 @2x）
  - `screenshots/home-paper-v2-desktop.png`（1200×900）
  - `screenshots/home-dark-v1.png` / `home-dark-v1-desktop.png`（存档）
- 更新仓库 `README.md` 与 `docs/README.md` 的入口链接。

### Changed

- **BREAKING**：新界面基准从 V1 深色烟玻璃切换为 **V2 纸质**；V1 仅作对比，不得在新界面中使用。
- 底栏从玻璃改为实体纸；整屏只保留灵动岛一处 `backdrop-filter`。
- 主视觉从“文字压全出血风景”改为“明信片：上图画、下纸签”。

### Notes

- 仍未实现进 FCL Android 源码；没有对应 APK。
- 未决：O1 竖屏/横屏、O3 灵动岛展开、O4 深色纸/夜间、真实数据/空态。
- 已用 Chromium 156 无头渲染校验；未在真机 FCL / Android WebView 复验。

---

## 1.0.0 — 2026-11-05

### Added

- V1 深色烟玻璃原型：竖屏主页、玻璃灵动岛、三栏底栏、全出血像素风景。
- V1 截图与原型保留在 `prototypes/home-dark-v1.html` 与 `screenshots/`。

### Notes

- V1 不再作为新界面基准；只用于对比与历史参考。

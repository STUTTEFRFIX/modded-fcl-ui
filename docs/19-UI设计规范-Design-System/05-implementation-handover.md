# 05 · 实现与接手

> 给要把它做进 Android / FCL 的人。
> 当前仓库里只有 **设计规范 + HTML 原型**；**没有**把 V2 实现进 `source/`，也没有对应的 APK。

---

## 1. 现状

| 项 | 状态 |
|---|---|
| 设计规范 | ✅ 本目录 01–06 |
| HTML 原型 | ✅ `prototypes/home-paper-v2.html`（单文件，可运行） |
| 设计 token | ✅ `design-tokens.css` / `design-tokens.json` |
| V1 深色存档 | ✅ `prototypes/home-dark-v1.html`（只对比，不进新界面） |
| Android 实现 | ❌ 未开始 |
| 编译好的 APK | ❌ 本次没有；需要先实现再按 [`BUILD.md`](../../BUILD.md) 出包 |
| 真机验证 | ❌ 未做（只有无头 Chromium 截图） |

> 用户说的“编译好的 HTML”= `prototypes/home-paper-v2.html`。它不需要构建，浏览器直接打开；它不是 APK。

---

## 2. 文件与预览

```text
docs/19-UI设计规范-Design-System/
├── prototypes/home-paper-v2.html   # 当前基准
├── prototypes/home-dark-v1.html    # 存档
├── screenshots/…                   # 390×844 @2x / 1200×900
├── design-tokens.css               # Web/CSS
├── design-tokens.json              # 机器可读
└── 01–06 …                         # 规范
```

### 2.1 打开原型

- 手机/桌面浏览器直接打开 `prototypes/home-paper-v2.html`；
- 单文件、零外部依赖；不需要服务器；
- 桌面端自动套 390×844 手机框；手机端全屏。

### 2.2 重新截图

```bash
chromium-headless --no-sandbox --hide-scrollbars \
  --force-device-scale-factor=2 --window-size=390,844 \
  --screenshot=home-paper-v2.png \
  "file:///…/prototypes/home-paper-v2.html"
```

---

## 3. Token 怎么用

### 3.1 唯一来源

- 设计/Web：`design-tokens.css`。
- Android/脚本：`design-tokens.json`。
- 原型 `:root` 使用同名 token；如果原型与 token 文件不一致，以 token 文件为准并修原型。

### 3.2 不要

- 不要在 XML/Compose 里写 `#4C6143`、`16sp`、`20dp`、`120ms` 这种字面量。
- 不要继续用 FCL 的 `ThemeEngine.autoTint` 自动黑/白；它是按主题色亮度选字色，和纸/墨体系冲突（现有审计已指出阈值 bug）。
- 不要同时维护一套“旧 FCL 主题色”和一套“V2 token”；新界面用新 token，旧界面保持旧主题，边界清楚。

### 3.3 Android 资源建议

| Token 类型 | Android 落点 |
|---|---|
| 颜色 | `res/values/colors.xml` 语义名（如 `ui_paper_0`、`ui_ink_1`）或 Compose `ColorScheme` |
| 字体 | `res/values/styles.xml` / Compose `Typography`；sp |
| 尺寸 | `res/values/dimens.xml`（dp） |
| 圆角 | `ShapeAppearance` / `GradientDrawable` / Compose `RoundedCornerShape` |
| 动效 | `res/values/integers.xml` 或 `UiMotion.kt` 常量 |
| 组件尺寸 | `dimens.xml`（island 64dp、bar 72dp、row 70dp…） |

---

## 4. Android 映射

### 4.1 单位

- 设计稿 390×844 是 **dp 基准**；1 CSS px ≈ 1dp（在 390dp 宽设备上）。
- 文字用 **sp**；不要用 dp 写字号。
- 间距/圆角/组件尺寸用 **dp**。
- 真机宽度常见 360/392/412dp；按 `03-layout` 的断点适配，不要按 390 写死。

### 4.2 颜色

- 纸/墨/苔墨按 token；不要用 `@android:color/white`/`darker_gray`。
- 状态栏：浅纸面 → 深色图标（`isAppearanceLightStatusBars = true`）。
- 导航栏：浅纸面 → 深色图标（`isAppearanceLightNavigationBars = true`，如适用）。
- 弹窗遮罩：`rgba(44,46,40,.28)`，不是纯黑 50%。

### 4.3 字体

- 系统字体；不打包字体文件。
- 字重 400/500/600；不要用 `textStyle=bold` 模拟 600 以外的粗体。
- 中文 `letterSpacing=0`；数字用 `FontFeatureSetting("tnum")` 或等宽数字。
- 行高按 `02`/`01` 的表；不要用默认 `includeFontPadding` 造成偏差（可关）。

### 4.4 形状

- 12/16/20/28dp 圆角 + pill；用 `ShapeAppearance` 统一定义，不要每个布局写 `cornerRadius`。
- 启动按钮 44dp 视觉（48dp 热区）；不要用 40dp 视觉 + 40dp 热区。
- 底栏上沿 28dp；底部随屏幕裁切。

### 4.5 纸/玻璃

- 纸卡/底栏：实色 + 1px 边框 + 暖灰阴影 + 内侧 1px 高光；不要 `blur`。
- 灵动岛：
  - API 31+ 可尝试 `RenderEffect.createBlurEffect(26f, 26f, CLAMP)` 对“岛后面的内容快照”做模糊；
  - 或使用经过评估的 BlurView 依赖；
  - **必须**有实色 fallback `#F7F4EC`；模糊是渐进增强，不是功能前提；
  - 不要对整页/视频做实时模糊；性能风险高。
- 底栏不要用 `RenderEffect`/BlurView。

### 4.6 安全区

- `WindowInsetsCompat`：
  - 状态栏：内容顶部 + top inset；
  - 底栏：高度 + bottom inset；标签离底 ≥8dp；
  - 内容底部：bar + island + 24 + bottom inset。
- 不要写死 60/80dp 的“安全区”。
- 刘海/挖孔/手势条都测。

### 4.7 组件落点

| 组件 | 建议实现 |
|---|---|
| 灵动岛 | 新自定义 View / Compose 容器；不要复用旧 `FCLDynamicIsland` 的 13sp 文字和画笔尺寸 |
| 底栏 | 新 `BottomNavigationView`（3 项）或自定义 View；不要用 FCLMenuView 改 |
| 明信片 | 自定义 View / Compose `Card`；图画 + 纸签；不要嵌套 Card |
| 世界行 | `RecyclerView` + 自定义 item / Compose `LazyColumn`；item 70dp |
| 启动 | 自定义圆形按钮；48dp 热区；苔墨底 + 奶油 play |
| 图标 | VectorDrawable；24/1.8、16/2.0；不要用 PNG 图标 |
| 弹层 | `BottomSheetDialog` / Compose `ModalBottomSheet` + 纸面样式；不用玻璃 |

### 4.8 状态与数据

- 世界列表：`WorldItem`（id、名称、版本、加载器、上次游玩、图标、状态）。
- 空态/加载/错误/损坏：见 `04` §6.3；不要把空列表当正常态。
- 启动：`LaunchState`（idle / launching / failed / done）；岛和按钮共享；失败可重试。
- 长按：`setOnLongClickListener` + 菜单；同时保留 more 按钮；不要只靠长按。
- 删除：确认对话框 + 说明后果；用 `--error` 语义色，不用霓虹红。

### 4.9 动效

- 按压 120ms：`ValueAnimator` / `animate().scaleX/Y`；行用背景色 state list/`Ripple` 的纸色版本。
- 入场 480/520ms：property animator；只做 opacity/translationY；不要做 blur/宽高。
- 底栏指示：200/260ms；不要用 `AnimatedVectorDrawable` 做无限循环。
- reduced motion：读 `Settings.Global.ANIMATOR_DURATION_SCALE` / `ValueAnimator.areAnimatorsEnabled()`；为 0 时关闭位移/缩放。
- 未来手势：`SpringAnimation`（damping 1.0、response 0.3–0.4 默认；只有速度交接才轻微回弹）。

### 4.10 触觉

- 只在启动提交/成功/错误等关键节点触发（`HapticFeedbackConstants.CONFIRM` / `REJECT`）。
- 不要每次点击都震动；不要用震动替代视觉反馈。

### 4.11 字符串

- 全部进 `strings.xml`；格式用占位符：
  - `island_launch_cd` = `启动 %1$s：Minecraft %2$s，%3$s`
  - `world_meta` = `%1$s · %2$s · 上次游玩 %3$s`
- 相对时间用格式化函数；不要拼接英文/中文。
- 空/错文案见 `04`；不要硬编码在 View 里。

### 4.12 资源

- 图标：VectorDrawable。
- 纸雕插画：SVG → VectorDrawable，或 2x/3x WebP；注意路径复杂度。
- 纸纤维：小尺寸噪点 PNG 平铺（2x），或自定义 Shader；不要全屏大图。
- 缩略图：本地资源；1x/2x/3x；不要网络加载。
- 不打包 Minecraft 官方纹理/皮肤。

---

## 5. 构建与验证

### 5.1 构建

- 本仓库的构建流程见 [`BUILD.md`](../../BUILD.md)；本设计不改变构建链。
- 当前 `source/` 是 2026-11-01 工作树快照，不等于最新 APK 源码；实现时从干净上游 + 补丁开分支，不要把设计改动混进发布补丁。
- 建议分支：`feature/ui-paper-v2`；先做独立 Home/Theme，再接线。

### 5.2 验证矩阵

| 项 | 要求 |
|---|---|
| 尺寸 | 360×740、390×844、412×915；320×640 至少不崩 |
| 字号 | 1.0 / 1.3 / 2.0 |
| 主题 | 系统浅色/深色下，浅纸界面状态栏图标正确 |
| 动效 | reduced-motion 开/关 |
| 透明 | reduced-transparency / 不支持 blur 的 fallback |
| 对比度 | 真机截图取色复测正文/辅助/玻璃 |
| 触控 | 启动 48、图标 44、底栏 54、行 70；无重叠 |
| 读屏 | TalkBack 走一遍账户→more→全部→行→启动→底栏 |
| 长文本 | 超长世界名、长加载器、空名、emoji |
| 性能 | 滚动 60fps；玻璃一处；无每帧分配 |

### 5.3 视觉对比

- 用 390×844 截图与 `screenshots/home-paper-v2.png` 叠图对比；
- 允许字体/抗锯齿差异，不允许间距/字号/圆角/颜色角色漂移；
- 发现漂移：先改实现；如果是设计问题，按 `06` 变更 token/文档，不要私改一边。

---

## 6. 风险与已知坑

| 风险 | 说明 | 处理 |
|---|---|---|
| **竖屏 vs 横屏** | FCL 上游 `sensorLandscape`；本规范竖屏 | 先定 O1；横屏另出布局，不要拉宽 |
| FCL 旧 UI 体系 | 1368 个自造控件、ThemeEngine 自动取色、8sp 字号 | 新界面独立 Theme/View；不要逐项改旧控件 |
| `autoTint` 阈值 bug | 现有代码按主题色亮度选黑/白，阈值 0.5 有误 | 新界面不用 autoTint；显式 ink token |
| 玻璃性能 | 实时 backdrop blur 在 Android 上成本高/API 限制 | 快照 blur 或实色 fallback；只一处 |
| 视频壁纸 | 动态背景让对比度不可控 | 静态纸面；如坚持视频，60%+ scrim + 静态 fallback |
| OEM 字体 | 字宽/字重不同，截断位置变 | 真机测 360/390/412；用 sp；不写死宽度 |
| 纸纹过重 | 低 DPI 下发糊/脏 | 噪点 5%→3%；帘纹/水印可关；正文优先 |
| 浅纸夜间 | 夜间偏亮 | O4 深色纸变体；不要反相 |
| `source/` 快照 | 不是最新 APK 源码 | 从干净上游 + 补丁开分支；见 BUILD.md |
| 设计未实现 | 没有 APK | 先实现再出包；不要把 HTML 当 APK |

---

## 7. 下一步（建议顺序）

1. **定 O1**：竖屏还是横屏。没有这个决定，不要写 Home 布局。
2. 开 `feature/ui-paper-v2`；先加 `UiTokens`（颜色/字体/尺寸/圆角/动效）和新 Theme，不改旧页面。
3. 实现 App Shell：背景纸面、内容滚动、岛/底栏固定、安全区。
4. 实现灵动岛（先实色 fallback，再加 blur）、底栏、明信片、世界行。
5. 补状态：按压/焦点/禁用/加载/空/错；补 strings。
6. 按 §5 验证矩阵跑真机；与截图对比；修漂移。
7. 出 APK（`BUILD.md`）；在 Release 记录设计版本（V2.0.0）与截图。
8. 如果做横屏/深色纸/展开态：先补规范与决策记录，再实现。

---

## 8. 接手清单

- [ ] 我读完了 `README` 的 §4/§5/§6，知道哪些已定、哪些未决。
- [ ] 我能打开 HTML 原型；知道它不是 APK。
- [ ] 我找到了 token 文件，知道 Android 怎么映射。
- [ ] 我知道 FCL 旧 UI 是横屏/自造控件，不能靠改颜色完成 V2。
- [ ] 我知道只有灵动岛可以模糊；底栏/纸卡是实色。
- [ ] 我知道启动 48/视觉 44、底栏 54、行 70。
- [ ] 我知道 O1/O3/O4 未决，不能擅自实现。
- [ ] 我知道改设计要走 `06` 的流程，并更新截图/CHANGELOG。
- [ ] 我知道构建看 `BUILD.md`，不要把设计 HTML 当 APK。
- [ ] 我知道示例数据是演示，发布版必须替换。

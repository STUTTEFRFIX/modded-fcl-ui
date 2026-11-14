# 首次启动引导（Onboarding）

> 版本：0.2.0 · 2026-11-14
> 状态：**设计 + 可运行 HTML 原型**；尚未实现进 `source/`。
> 视觉基线：[`../19-UI设计规范-Design-System/`](../19-UI设计规范-Design-System/README.md) V2 纸质（2.0.0）。

---

## 1. 结构

三屏 + 进入主界面：

| 屏 | 内容 |
|---|---|
| 1 | 插图 + 一句话 |
| 2 | 插图 + 一句话 |
| 3 | 插图 + 一句话（人员名单）+ 名单骨架 |
| 收尾 | 点「开始使用」直接落到 V2 首页原型 |

```
SplashActivity → 用户协议 → 运行时解压
      → 【本引导 3 屏】→ enterLauncher → MainActivity
```

引导排在用户协议与运行时解压之后：这两步有法律与解压的硬前置；引导本身可跳过。

**方向：竖屏 UI，横屏暂不考虑。** 本引导只做 390×844 竖屏，不提供横屏布局；
实机应锁定竖屏（见 §7 的 screenOrientation）。

---

## 2. 文案

### 2.1 正文（**原文，逐字不得改动**）

| 屏 | 原文 |
|---|---|
| 1 | `欢迎使用 fclui 魔改` |
| 2 | `我们是来自社区自发组织的修改` |
| 3 | `人员名单` |

- 大小写、空格、标点一律照抄。例如 `fclui` 保持小写，不要写成 `FCLUI`。
- 一个字都不许增删。需要补充说明时，**先加到这里并确认**，再改进原型。

### 2.2 控件标签（功能必需，非产品文案）

`下一步` / `上一步` / `开始使用`；跳过用纯图形按钮（×），不占正文。

### 2.3 名单（待填）

第 3 屏只画了 **4 行骨架**（虚线头像 + 灰条），**没有任何文字**，等你给真实姓名后再填。
在给出名单之前，原型里不会出现任何人名或头衔。

---

## 3. 文件

```text
docs/20-首次启动引导/
├── README.md                              # 本文件
├── prototype/
│   ├── onboarding-paper-v1.html           # 引导原型；?s=1|2|3 直达
│   └── home-paper-v2.html                 # 收尾落点：docs/19 首页原型的副本
└── screenshots/
    ├── onboarding-1.png                   # 390×844 @2x
    ├── onboarding-2.png
    └── onboarding-3.png
```

`home-paper-v2.html` 是 **docs/19 的副本**（单一来源仍是 docs/19），只为让「开始使用」有落点；
docs/19 更新后重新拷一次即可。

---

## 4. 排版与布局（可自由调整）

文案固定，剩下的都是版式决定：

- **字号**：唯一正文用 `--text-display-size` 32/40、字重 600。不新增字号 token（要新字号先走 `06-governance`）。
- **断行**：`max-width:280px` + `text-wrap:balance`。第 1 屏自然断成「欢迎使用 / fclui 魔改」，
  第 2 屏断成「我们是来自社区 / 自发组织的修改」。断行只是排版，不改字符。
- **构图**：三屏统一为「插图 + 一句话」的居中组，整体垂直居中；第 3 屏在下面接名单骨架。
- **插图**：手绘 SVG，只用 `--art-*` 插画色与苔墨，单屏 150–200px 宽。
- **名单骨架**：不写字的占位，虚线上框 + 灰条，行宽 68%–96% 制造自然错落。
- **顶部**：仿制状态栏（仅设计稿）+ 纯图形关闭按钮（CSS 画的两根斜线，避免小尺寸 SVG 在部分渲染器不出像素）。

---

### 4.1 纸色：米黄（偏离 V2 token，需登记）

按「背景要米黄、贴近纸的质感」调整。**这不是 V2 规范里的数值**，V2 是暖灰纸；如果这套米黄被采纳，需要回 `docs/19` 改 token 并补一条决策记录。

| 变量 | V2 原值（暖灰） | 本原型（米黄） |
|---|---|---|
| `--paper-0` | `#F1EEE6` | `#F8F2E3` |
| `--paper-1` | `#EAE6DB` | `#F2EAD7` |
| `--paper-2` | `#E2DDD1` | `#E8DDC4` |
| `--card` | `#F8F6EF` | `#FCF8EE` |
| `--line` / `--line-strong` | `rgba(74,68,54,…)` | `rgba(122,104,72,…)`（线随纸转暖，否则发灰） |

墨 `#2C2E28`、苔墨 `#4C6143` 未改，对比度不受影响：
墨/纸顶 ≈ 12.2:1，辅助墨 `#5F6256` / 纸底 `#E8DDC4` ≈ 4.6:1，仍过 4.5。

### 4.2 纸的质感：三层叠加

1. **织纹**：两个方向的 `repeating-linear-gradient`（0° 间距 3px、90° 间距 5px），透明度 5% / 2.2%，模拟纸的经纬。
2. **颗粒**：`feTurbulence`（fractalNoise，baseFrequency 0.85，4 个八度）做成 SVG data-URI 平铺，`mix-blend-mode:multiply`、整体不透明度 20%，去掉平涂的塑料感。
3. **起伏**：顶部 `radial-gradient` 提亮、底部压暗，让整块面像一页纸而不是纯色块。

---

## 5. 交互与状态

| 元素 | 行为 |
|---|---|
| 下一步 / 开始使用 | 前进；第 3 屏的「开始使用」跳 `home-paper-v2.html` |
| 上一步 | 后退；第 1 屏隐藏 |
| 关闭（×） | 直接跳首页 |
| 进度点 | 3 个，当前 24×8 胶囊 |
| 键盘 | `←` `→` 翻页，`Esc` 跳过，`Enter` 前进 |

状态：默认 / 按压（`scale .985` + 底色加深）/ 焦点（2px 苔墨描边、偏移 3px）。
`prefers-reduced-motion` 下取消位移，只保留淡入。

---

## 6. 设计约束

- 只用 token，不写死颜色/字号/圆角/时长。
- 只有一个强调色：苔墨 `#4C6143`；苔绿只出现在插图。
- 不用纯黑纯白。
- 正文 16 / 辅助 14 为下限；触控 ≥44（主按钮 52、关闭 44）。
- 只动 `transform` / `opacity`。

---

## 7. Android 实现映射

| 原型 | Android |
|---|---|
| 容器 | 独立 `OnboardingActivity`，不要塞进 `UIManager` 的 ViewPager2（页面会被回收） |
| 插入点 | `SplashActivity` 的 `enterLauncher` 之前 |
| 首启标记 | `SharedPreferences("launcher")` 存 `onboarding_done_version = versionCode` |
| 分页 | `ViewPager2` + 3 个 Fragment，或 `ViewFlipper`（只有按钮翻页） |
| 文案 | `strings.xml`：`onboarding_1` / `onboarding_2` / `onboarding_3`，值就是 §2.1 三句原文 |
| 颜色尺寸 | `colors.xml` 语义名 `ui_paper_0` …、`dimens.xml`、`ShapeAppearance` |
| 名单 | 先不渲染内容；给了名单后改 `RecyclerView` + `res/raw/credits.json` |

---

## 8. 实时预览

工作区的 `@local/dsh-live-preview` 插件服务 `<工作区>/ui-preview/`，保存即刷新。

- `ui-preview/index.html`：三屏并排评审板（窄面板自动竖排）。
- `ui-preview/onboarding.html`、`ui-preview/home-paper-v2.html`：上面两个文件的**副本**。

> 预览目录只能访问自身，所以必须是副本。改了 `prototype/` 下的文件要同步过去。

---

## 9. 待定项

| # | 问题 | 现状 |
|---|---|---|
| O1 | 第 3 屏的名单：写谁、什么头衔、几行 | **待你提供** |
| O3 | 跳过是否直达主界面 | 是 |

**已定**：

| # | 决策 | 结论 |
|---|---|---|
| D1 | 方向 | **竖屏**。横屏暂不考虑，本引导不出横屏布局 |

---

## 10. 变更记录

| 日期 | 内容 |
|---|---|
| 2026-11-14 | 首版 |
| 2026-11-14 | **撤回自行编写的一切文案**：三屏正文只保留原文三句；名单改无字骨架；收尾改为跳转 V2 首页 |
| 2026-11-14 | 纸色改米黄并加三层纸感；三屏插图重画；**确定竖屏、不做横屏** |

---

## 11. Android 实现（已落地）

补丁：patches/feature-onboarding.patch（相对上游 2f10d62 生成，含新增文件）。

### 11.1 接入点

SplashActivity.enterLauncher()：运行时初始化完成后判断是否首启。

`
SplashActivity → 协议 → 权限 → 运行时 → 【OnboardingActivity】→ MainActivity
                                              ↑ 仅首次
`

- 判断：OnboardingActivity.shouldShow(context) 读 SharedPreferences("launcher").onboarding_done
- 看完（或点 ×）写入 	rue，再拉起 MainActivity
- Splash 原本要塞给 MainActivity 的 extras（如 modpack_cache_path）通过 EXTRA_NEXT_EXTRAS 原样转发
- 想看第二次：db shell pm clear com.tungsten.fcl.noregion，或系统里清除应用数据

### 11.2 新增文件

| 类型 | 文件 |
|---|---|
| 页面 | ctivity/OnboardingActivity.kt |
| 布局 | layout/activity_onboarding.xml、onboarding_page_{welcome,community,credits}.xml |
| 插画 | drawable/onboarding_art_{welcome,community,credits}.xml（由原型 SVG 自动转 VectorDrawable） |
| 纸面 | drawable/bg_onboarding_paper.xml（渐变）、g_onboarding_texture.xml + drawable-nodpi/onb_texture.png（平铺颗粒） |
| 控件 | g_onboarding_primary.xml、g_onboarding_ghost.xml、onboarding_dot_{on,off}.xml、g_roster_{avatar,line}.xml、ic_onboarding_close.xml |
| 数值 | alues/{colors,dimens,strings}_onboarding.xml |
| 动效 | nim/onboarding_{in,out}.xml |

### 11.3 与原型的差异（受平台限制）

1. **社区的虚线弧形**：VectorDrawable 不支持 stroke-dasharray，落成一条低透明度细实线。
2. **纸纹**：Android 没有 eTurbulence，改用一张 200×200 的透明颗粒 PNG 平铺（	ileMode=repeat，整体 alpha 0.55）。
3. **字重**：Android 无 600，标题用 sans-serif-medium（500）。
4. **状态栏**：真机用系统状态栏（浅纸面 + 深色图标），不用原型里那套仿制图形。

### 11.4 验证状态

- 构建：:FCL:assembleDebug -Darch=arm64 通过，产物 173.4 MB。
- 资源：AndroidManifest 已含 OnboardingActivity 且 screenOrientation=portrait；三张矢量图与纸纹已进包。
- 插画：把生成的 VectorDrawable 反解回 SVG 渲染核对，几何与原型一致（修正过一处圆角矩形的 pathData 错误）。
- **未做**：真机/模拟器的整体渲染核对 —— 本机模拟器（ApkStart）启动四次均崩溃（code=3221225477），未能出图。

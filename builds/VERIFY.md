# 发布验证记录

日期：2026-11-01

## 仓库

- 地址：https://github.com/STUTTEFRFIX/modded-fcl-ui
- 可见性：**PRIVATE**
- 默认分支：`main`
- 初始提交：`cce20f5`（源码 + 文档 + 补丁 + 测试数据）

## Release

| Tag | 文件 | 大小（字节） | SHA-256 |
|---|---|---:|---|
| `v1.3.4.1-modgraph` | `FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk` | 181,708,236 | `0c51b4d7915d4f080ed07345a06aa1285b3f4b23f5dfca4c3e5a656ac51a46b1` |
| `v1.3.4.1-noregion` | `FCL-1.3.4.1-noregion-arm64-v8a.apk` | 179,282,498 | `1498f0df0dcee83550dd397a5922891cff79faec91f3a3ee2d36b497ee33ecad` |

- `v1.3.4.1-modgraph` 为 latest release；
- `SHA256SUMS` 也作为 modgraph release 附件上传；
- 两个 release 均非 draft / 非 prerelease。

## 测试

- `ModGraphBuilderTest`：6 项通过
- `ModDependencyParsingTest`：4 项通过
- 构建：`:FCL:assembleDebug -Darch=arm64` 成功

## 待复验

- 关系图分层布局在真机上的最终观感（首版力导向截图过挤，已改为分层布局 + 横屏侧栏）。

# 构建产物

APK 不放进 git 树（避免仓库膨胀），作为 GitHub Release 附件发布。

## Release

- [`v1.3.4.1-modgraph`](https://github.com/STUTTEFRFIX/modded-fcl-ui/releases/tag/v1.3.4.1-modgraph) — 带模组关系图
- `v1.3.4.1-noregion` — 基线版本（无关系图）

## 文件

| 文件 | 大小 | SHA-256 |
|---|---:|---|
| `FCL-1.3.4.1-noregion-modgraph-arm64-v8a.apk` | 174 MB | `0c51b4d7915d4f080ed07345a06aa1285b3f4b23f5dfca4c3e5a656ac51a46b1` |
| `FCL-1.3.4.1-noregion-arm64-v8a.apk` | 171 MB | `1498f0df0dcee83550dd397a5922891cff79faec91f3a3ee2d36b497ee33ecad` |

校验：

```sh
sha256sum -c SHA256SUMS
```

## 安装信息

- 包名：`com.tungsten.fcl.noregion`
- 版本：`1.3.4.1-noregion`
- 架构：arm64-v8a
- 签名：debug（与官方 FCL 不同，不能覆盖安装，可以共存）
- 首次启动需要重新解压运行时；游戏目录可以指到 `/sdcard/FCL/.minecraft`

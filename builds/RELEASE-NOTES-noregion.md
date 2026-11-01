# FCL 1.3.4.1 noregion（arm64，基线）

无地区限制的基线构建，**不包含**模组关系图。用于对比/回退。

## 安装信息

- 包名：`com.tungsten.fcl.noregion`
- 版本：`1.3.4.1-noregion`
- 架构：arm64-v8a
- 大小：171 MB
- SHA-256：`1498f0df0dcee83550dd397a5922891cff79faec91f3a3ee2d36b497ee33ecad`
- 签名：debug（与官方 FCL 不同，不能覆盖安装，可与官方共存）

## 内容

- 基于 FCL 上游 `origin/main 42f515b0`；
- 去掉陶瓦节点按设备地区过滤；
- 包名改为 `com.tungsten.fcl.noregion`，可与官方 `com.tungsten.fcl` 共存；
- 详细构建过程见 `docs/15-最新构建/README.md`。

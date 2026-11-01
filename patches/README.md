# 补丁说明

从 FCL 仓库根目录用 `patch -p1` 应用。

## 应用顺序

```text
1. noregion/0001-remove-terracotta-node-region-filter.patch   陶瓦节点不再按设备地区过滤
2. noregion/0002-noregion-build-identity.patch                 applicationId = com.tungsten.fcl.noregion
3. noregion/0003-noregion-res-identity.patch                  app_name / FileProvider authority
4. feature-mod-graph.patch                                    模组关系图（含新增文件）
```

## 注意

- `feature-mod-graph.patch` 基于工作树 `feat/framegen-host` 的 HEAD 生成；上游 `42f515b0` 在
  `FCL/src/main/java/com/tungsten/fcl/ui/manage/ModListPage.java` 和
  `FCL/src/main/res/values/strings.xml` 附近有小改动，可能需要手工对齐上下文。
- `source/` 快照里已经包含关系图代码，**不要**在 `source/` 上重复应用 `feature-mod-graph.patch`。
- 详细文件清单见 `docs/17-模组关系图/README.md` §3。

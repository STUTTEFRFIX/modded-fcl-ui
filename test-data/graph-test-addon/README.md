# 关系图测试数据

## 合成 addon

`META-INF/neoforge.mods.toml` 定义了一个最小的 NeoForge 模组 `graph_test_addon`：

| 依赖 | 类型 | 预期 |
|---|---|---|
| `neoforge` | 必需 | 内置 id，图上忽略 |
| `minecraft` | 必需 | 内置 id，图上忽略 |
| `jei` | 必需 | 安装 JEI 后画实线 |
| `jade` | 可选 | 安装 Jade 后画虚线 |
| `not_installed_lib` | 必需 | 未安装，生成「缺失前置」节点 |

打包：

```sh
cd test-data/graph-test-addon
zip -q -r ../graph-test-addon.jar META-INF
```

放进实例：

```sh
mkdir -p "<实例目录>/mods"
cp ../graph-test-addon.jar "<实例目录>/mods/"
```

## 真实模组（可选）

为了验证真实依赖和 Modrinth 分类，可再从 Modrinth 下载（2026-11-01 验证过的 NeoForge 1.21.4 文件）：

| 模组 | 文件 |
|---|---|
| JEI | `https://cdn.modrinth.com/data/u6dRKJwZ/versions/V5vS77Gp/jei-1.21.4-neoforge-20.0.0.4.jar` |
| Jade | `https://cdn.modrinth.com/data/nvQzSEkH/versions/Rpe14jmy/Jade-1.21.4-NeoForge-17.3.0.jar` |
| AppleSkin | `https://cdn.modrinth.com/data/EsAfCjCV/versions/6OIEi4xY/appleskin-neoforge-mc1.21.3-3.0.8.jar` |
| Architectury API | `https://cdn.modrinth.com/data/lhGA9TYQ/versions/qOJoTaPQ/architectury-15.0.3-neoforge.jar` |
| Cloth Config | `https://cdn.modrinth.com/data/9s6osm5g/versions/dWfheG9X/cloth-config-17.0.144-neoforge.jar` |

Jade 对 JEI 是 optional，合成 addon 对 JEI 是 required、对 Jade 是 optional；这组数据可以同时验证实线、虚线和缺失前置。

> 这些文件版权归各自作者；仓库不提交 jar，只记录下载地址。验证完可以删除实例里的测试 jar。

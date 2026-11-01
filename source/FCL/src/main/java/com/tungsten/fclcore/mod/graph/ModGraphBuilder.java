/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026  contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tungsten.fclcore.mod.graph;

import com.tungsten.fclcore.mod.LocalModFile;
import com.tungsten.fclcore.mod.ModDependency;
import com.tungsten.fclcore.mod.ModLoaderType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从实例里已解析的模组构建依赖图。
 *
 * <p>规则与 Modpack Studio 的图构建保持一致：</p>
 * <ul>
 *   <li>必需前置缺失 → 生成“缺失前置”节点；可选/冲突目标缺失 → 不画边</li>
 *   <li>游戏、加载器本身不是模组，不建节点也不建边</li>
 *   <li>同一 mod id 的多个文件（旧版本、启用/禁用副本）只保留一个，优先启用的那个</li>
 *   <li>三元组 (source, target, type) 去重，避免同一依赖被重复声明</li>
 * </ul>
 */
public final class ModGraphBuilder {

    /**
     * 这些 id 是游戏/加载器组件，不是可安装的模组。
     * 注意不要把 fabric-api / fabric_api 放进来：它是真实模组，很多包会装。
     */
    private static final Set<String> BUILTIN_IDS = Set.of(
            "minecraft", "java",
            "forge", "neoforge", "cleanroom",
            "fabricloader", "fabric-loader",
            "quilt_loader", "quilt-loader",
            "javafml", "lowcodefml",
            "mixinextras", "mixin", "openloader"
    );

    private ModGraphBuilder() {
    }

    /**
     * 图构建器的输入。与 LocalModFile 解耦，方便单元测试直接构造。
     */
    public record Input(String id, String name, String version, String fileName,
                        ModLoaderType loaderType, boolean active,
                        List<ModDependency> dependencies) {

        public Input {
            id = normalize(id);
            name = name == null ? "" : name;
            version = version == null ? "" : version;
            fileName = fileName == null ? "" : fileName;
            loaderType = loaderType == null ? ModLoaderType.UNKNOWN : loaderType;
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        }

        public static Input from(LocalModFile file) {
            return new Input(file.getId(), file.getName(), file.getVersion(), file.getFileName(),
                    file.getModLoaderType(), file.isActive(), file.getDependencies());
        }
    }

    public static ModGraph build(Collection<Input> inputs) {
        Map<String, Input> chosen = new LinkedHashMap<>();
        if (inputs != null) {
            for (Input input : inputs) {
                if (input == null || input.id().isEmpty() || BUILTIN_IDS.contains(input.id())) {
                    continue;
                }
                Input previous = chosen.get(input.id());
                // 同一 mod id 可能有 .jar 和 .jar.disabled 两个文件；启用优先，否则保留先出现的
                if (previous == null || (!previous.active() && input.active())) {
                    chosen.put(input.id(), input);
                }
            }
        }

        Map<String, ModGraph.Node> nodes = new LinkedHashMap<>();
        for (Input input : chosen.values()) {
            nodes.put(input.id(), new ModGraph.Node(input.id(), input.name(), input.version(),
                    input.fileName(), input.loaderType(), input.active(), false));
        }

        List<ModGraph.Edge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();

        for (Input input : chosen.values()) {
            ModGraph.Node source = nodes.get(input.id());
            if (source == null) {
                continue;
            }
            for (ModDependency dependency : input.dependencies()) {
                if (dependency == null) {
                    continue;
                }
                String targetId = dependency.targetId();
                if (targetId.isEmpty() || targetId.equals(source.getId())) {
                    continue;
                }
                if (BUILTIN_IDS.contains(targetId)) {
                    continue;
                }

                ModGraph.Node target = nodes.get(targetId);
                if (target == null) {
                    if (dependency.type() != ModDependency.Type.REQUIRED) {
                        continue;
                    }
                    target = new ModGraph.Node(targetId, targetId, "", "",
                            ModLoaderType.UNKNOWN, true, true);
                    nodes.put(targetId, target);
                }

                String key = source.getId() + '\u0000' + target.getId() + '\u0000' + dependency.type().name();
                if (!edgeKeys.add(key)) {
                    continue;
                }
                ModGraph.Edge edge = new ModGraph.Edge(source, target, dependency.type());
                edges.add(edge);
                source.addOutgoing(edge);
                target.addIncoming(edge);
            }
        }

        List<ModGraph.Node> nodeList = new ArrayList<>(nodes.values());
        int required = 0;
        int optional = 0;
        int incompatible = 0;
        int embedded = 0;
        for (ModGraph.Edge edge : edges) {
            switch (edge.getType()) {
                case REQUIRED -> required++;
                case OPTIONAL -> optional++;
                case INCOMPATIBLE -> incompatible++;
                case EMBEDDED -> embedded++;
            }
        }
        int hubs = 0;
        int isolated = 0;
        int missing = 0;
        int disabled = 0;
        for (ModGraph.Node node : nodeList) {
            if (node.isHub()) {
                hubs++;
            }
            if (node.isIsolated()) {
                isolated++;
            }
            if (node.isMissing()) {
                missing++;
            }
            if (!node.isActive() && !node.isMissing()) {
                disabled++;
            }
        }

        ModGraph.Stats stats = new ModGraph.Stats(nodeList.size(), edges.size(), required, optional,
                incompatible, embedded, hubs, isolated, missing, disabled);
        return new ModGraph(nodeList, edges, stats);
    }

    static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }
}

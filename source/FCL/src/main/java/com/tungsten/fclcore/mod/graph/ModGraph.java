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

import com.tungsten.fclcore.mod.ModDependency;
import com.tungsten.fclcore.mod.ModLoaderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个实例的模组依赖图：节点是已安装（或缺失）的模组，边是 jar 元数据声明的依赖。
 *
 * <p>这个类只描述结构，不含任何 Android / UI 类型，可以在 JVM 单元测试里直接构造。</p>
 */
public final class ModGraph {

    private static final int HUB_IN_DEGREE = 3;

    public static final class Node {
        private final String id;
        private final String name;
        private final String version;
        private final String fileName;
        private final ModLoaderType loaderType;
        private final boolean active;
        private final boolean missing;
        private int inDegree;
        private int outDegree;
        private final List<Edge> outgoing = new ArrayList<>();
        private final List<Edge> incoming = new ArrayList<>();

        Node(String id, String name, String version, String fileName,
             ModLoaderType loaderType, boolean active, boolean missing) {
            this.id = id;
            this.name = name == null || name.isBlank() ? id : name;
            this.version = version == null ? "" : version;
            this.fileName = fileName == null ? "" : fileName;
            this.loaderType = loaderType == null ? ModLoaderType.UNKNOWN : loaderType;
            this.active = active;
            this.missing = missing;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getVersion() {
            return version;
        }

        public String getFileName() {
            return fileName;
        }

        public ModLoaderType getLoaderType() {
            return loaderType;
        }

        /** 模组文件当前是否启用（缺失节点恒为 true，避免被当成“已禁用”）。 */
        public boolean isActive() {
            return active;
        }

        /** 依赖声明指向但实例里没有安装的模组。 */
        public boolean isMissing() {
            return missing;
        }

        public int getInDegree() {
            return inDegree;
        }

        public int getOutDegree() {
            return outDegree;
        }

        public int getDegree() {
            return inDegree + outDegree;
        }

        /** 仅由 ModGraphBuilder 在装配阶段调用。 */
        void addOutgoing(Edge edge) {
            outgoing.add(edge);
            outDegree++;
        }

        /** 仅由 ModGraphBuilder 在装配阶段调用。 */
        void addIncoming(Edge edge) {
            incoming.add(edge);
            inDegree++;
        }

        /** 被至少 3 个模组依赖：通常是前置库或核心玩法模组。 */
        public boolean isHub() {
            return !missing && inDegree >= HUB_IN_DEGREE;
        }

        /** 没有任何依赖关系：玩家可能装了但没连上，值得检查。 */
        public boolean isIsolated() {
            return !missing && inDegree == 0 && outDegree == 0;
        }

        public List<Edge> getOutgoing() {
            return Collections.unmodifiableList(outgoing);
        }

        public List<Edge> getIncoming() {
            return Collections.unmodifiableList(incoming);
        }

        @Override
        public String toString() {
            return "Node{" + id + ", in=" + inDegree + ", out=" + outDegree + (missing ? ", missing" : "") + '}';
        }
    }

    public static final class Edge {
        private final Node source;
        private final Node target;
        private final ModDependency.Type type;

        Edge(Node source, Node target, ModDependency.Type type) {
            this.source = source;
            this.target = target;
            this.type = type;
        }

        public Node getSource() {
            return source;
        }

        public Node getTarget() {
            return target;
        }

        public ModDependency.Type getType() {
            return type;
        }
    }

    public record Stats(int nodes, int edges, int required, int optional, int incompatible,
                        int embedded, int hubs, int isolated, int missing, int disabled) {
    }

    private final List<Node> nodes;
    private final List<Edge> edges;
    private final Stats stats;
    private final Map<String, Node> nodeById;

    ModGraph(List<Node> nodes, List<Edge> edges, Stats stats) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
        this.stats = stats;
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node node : nodes) {
            byId.put(node.getId(), node);
        }
        this.nodeById = Collections.unmodifiableMap(byId);
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Edge> getEdges() {
        return edges;
    }

    public Stats getStats() {
        return stats;
    }

    public Node getNode(String id) {
        return id == null ? null : nodeById.get(id);
    }

    public boolean isEmpty() {
        return nodes.isEmpty();
    }
}

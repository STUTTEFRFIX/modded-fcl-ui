package com.tungsten.fclcore.mod.graph;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tungsten.fclcore.mod.ModDependency;
import com.tungsten.fclcore.mod.ModLoaderType;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class ModGraphBuilderTest {

    private static ModGraphBuilder.Input mod(String id, boolean active, ModDependency... deps) {
        return new ModGraphBuilder.Input(id, id, "1.0", id + ".jar",
                ModLoaderType.FABRIC, active, Arrays.asList(deps));
    }

    private static long count(ModGraph graph, ModDependency.Type type) {
        return graph.getEdges().stream().filter(e -> e.getType() == type).count();
    }

    @Test
    public void buildsRequiredOptionalIncompatibleAndMissingEdges() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                mod("a", true,
                        ModDependency.required("b", ">=1.0"),
                        ModDependency.optional("x", ""),
                        ModDependency.incompatible("c", ""),
                        ModDependency.required("minecraft", ""),
                        ModDependency.required("z", "")),
                mod("b", true, ModDependency.required("c", "")),
                mod("c", true),
                mod("d", true),
                mod("e", false, ModDependency.required("a", ""))
        ));

        assertEquals(6, graph.getNodes().size()); // a b c d e + 缺失的 z；可选目标 x 不建节点
        assertEquals(5, graph.getEdges().size());
        assertEquals(4, count(graph, ModDependency.Type.REQUIRED));
        assertEquals(0, count(graph, ModDependency.Type.OPTIONAL));
        assertEquals(1, count(graph, ModDependency.Type.INCOMPATIBLE));

        ModGraph.Node missing = graph.getNode("z");
        assertNotNull(missing);
        assertTrue(missing.isMissing());
        assertTrue(missing.isActive());
        assertEquals(1, missing.getInDegree());

        assertNull(graph.getNode("x"));
        assertNull(graph.getNode("minecraft"));

        ModGraph.Node c = graph.getNode("c");
        assertNotNull(c);
        assertEquals(2, c.getInDegree());
        assertEquals(0, c.getOutDegree());
        assertFalse(c.isHub()); // 入度 2，未到枢纽阈值

        ModGraph.Node a = graph.getNode("a");
        assertNotNull(a);
        assertEquals(3, a.getOutDegree());
        assertEquals(1, a.getInDegree()); // 只来自已禁用模组 e，也要画出来
        assertTrue(graph.getNode("d").isIsolated());
        assertFalse(graph.getNode("e").isActive());

        ModGraph.Stats stats = graph.getStats();
        assertEquals(6, stats.nodes());
        assertEquals(5, stats.edges());
        assertEquals(1, stats.missing());
        assertEquals(1, stats.disabled());
        assertEquals(1, stats.isolated());
        assertEquals(0, stats.hubs());
    }

    @Test
    public void dedupesDuplicateInputsAndEdgesAndPrefersActiveFile() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                mod("same", false, ModDependency.required("dep", "")),
                mod("same", true,
                        ModDependency.required("dep", ""),
                        ModDependency.required("dep", "")), // 同一依赖重复声明
                mod("dep", true)
        ));

        assertEquals(2, graph.getNodes().size());
        assertEquals(1, graph.getEdges().size());
        assertTrue(graph.getNode("same").isActive()); // 启用副本胜出
        assertEquals(1, count(graph, ModDependency.Type.REQUIRED));
    }

    @Test
    public void marksHubsAndIsolatedNodes() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                mod("hub", true),
                mod("a", true, ModDependency.required("hub", "")),
                mod("b", true, ModDependency.required("hub", "")),
                mod("c", true, ModDependency.required("hub", "")),
                mod("lonely", true)
        ));

        assertTrue(graph.getNode("hub").isHub());
        assertEquals(3, graph.getNode("hub").getInDegree());
        assertTrue(graph.getNode("lonely").isIsolated());
        assertEquals(1, graph.getStats().hubs());
        assertEquals(1, graph.getStats().isolated());
    }

    @Test
    public void ignoresBuiltinLoaderIdsEvenIfPassedAsInput() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                mod("minecraft", true),
                mod("a", true, ModDependency.required("minecraft", ""))
        ));

        assertEquals(1, graph.getNodes().size());
        assertNotNull(graph.getNode("a"));
        assertNull(graph.getNode("minecraft"));
        assertEquals(0, graph.getEdges().size());
    }

    @Test
    public void normalizesModIdCase() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                new ModGraphBuilder.Input("MyMod", "My Mod", "", "", ModLoaderType.FORGE, true,
                        List.of(ModDependency.required("OTHER", ""))),
                new ModGraphBuilder.Input("Other", "Other", "", "", ModLoaderType.FORGE, true,
                        List.of())
        ));

        assertEquals(2, graph.getNodes().size());
        assertNotNull(graph.getNode("mymod"));
        assertNotNull(graph.getNode("other"));
        assertEquals(1, graph.getEdges().size());
        assertEquals("mymod", graph.getEdges().get(0).getSource().getId());
        assertEquals("other", graph.getEdges().get(0).getTarget().getId());
    }

    @Test
    public void keepsOptionalEdgeWhenTargetInstalled() {
        ModGraph graph = ModGraphBuilder.build(List.of(
                mod("a", true, ModDependency.optional("b", "")),
                mod("b", true)
        ));

        assertEquals(1, graph.getEdges().size());
        assertEquals(ModDependency.Type.OPTIONAL, graph.getEdges().get(0).getType());
        assertEquals(1, graph.getStats().optional());
        assertFalse(graph.getNode("a").isIsolated());
        assertFalse(graph.getNode("b").isIsolated());
    }
}

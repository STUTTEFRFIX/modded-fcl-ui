package com.tungsten.fcl.ui.manage.graph

import com.tungsten.fclcore.mod.graph.ModGraph
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 依赖图布局：按依赖深度分层的有向图。
 *
 * 力导向在模组依赖图上很容易挤成一团（一个节点连十几条边，斥力和弹簧互相打架），
 * 标签也会叠在一起。依赖关系天然有方向，所以改成「分层」：
 *
 * - 没有前置的节点在右（第 0 层），依赖它的节点依次向左；
 * - 每层内按邻居的平均位置排序（barycenter），减少交叉；
 * - 孤立节点单独排在主图下方；
 * - 复杂度 O(V + E)，几百个节点也不需要迭代几百轮。
 *
 * 这个布局在后台线程计算，[ModGraphView] 负责缩放/平移。
 */
internal object ModGraphLayout {

    class Result(
        val x: FloatArray,
        val y: FloatArray,
        val radius: FloatArray,
        val index: Map<String, Int>
    )

    private const val X_GAP = 280f
    private const val Y_GAP = 90f
    private const val MAX_LAYER = 12

    fun compute(graph: ModGraph): Result {
        val nodes = graph.nodes
        val n = nodes.size
        val index = HashMap<String, Int>(n * 2)
        val radius = FloatArray(n)
        for (i in 0 until n) {
            val node = nodes[i]
            index[node.id] = i
            radius[i] = when {
                node.isMissing -> 7f
                node.isIsolated -> 6f
                else -> min(30f, 6.5f + sqrt(node.inDegree.toFloat()) * 4.4f)
            }
        }

        // 邻接表：out = 自己依赖谁，in = 谁依赖自己
        val out = Array(n) { ArrayList<Int>(4) }
        val inc = Array(n) { ArrayList<Int>(4) }
        for (edge in graph.edges) {
            val a = index[edge.source.id] ?: continue
            val b = index[edge.target.id] ?: continue
            out[a].add(b)
            inc[b].add(a)
        }

        val connected = ArrayList<Int>(n)
        val isolated = ArrayList<Int>()
        for (i in 0 until n) {
            if (out[i].isEmpty() && inc[i].isEmpty()) isolated.add(i) else connected.add(i)
        }

        // 依赖深度：叶子（没有前置）为 0，每往上一层 +1；环按 0 处理，上限压到 MAX_LAYER
        val layer = IntArray(n) { -1 }
        val visiting = BooleanArray(n)
        fun depth(i: Int): Int {
            if (layer[i] >= 0) return layer[i]
            if (visiting[i]) return 0
            visiting[i] = true
            var d = 0
            for (child in out[i]) {
                d = max(d, 1 + depth(child))
            }
            visiting[i] = false
            d = min(d, MAX_LAYER)
            layer[i] = d
            return d
        }
        for (i in connected) depth(i)

        val maxLayer = if (connected.isEmpty()) 0 else connected.maxOf { layer[it] }
        val layers = Array(maxLayer + 1) { ArrayList<Int>() }
        for (i in connected) {
            layers[layer[i]].add(i)
        }
        // 初始顺序：被依赖多的排前面
        for (list in layers) {
            list.sortByDescending { inc[it].size }
        }

        val pos = FloatArray(n)
        fun assignY() {
            for (list in layers) {
                if (list.isEmpty()) continue
                val half = (list.size - 1) / 2f
                for ((idx, node) in list.withIndex()) {
                    pos[node] = (idx - half) * Y_GAP
                }
            }
        }
        assignY()

        // barycenter 扫描：先让前置对齐下游，再让上游对齐前置，重复几轮减少交叉
        repeat(4) {
            for (l in 1..maxLayer) {
                val list = layers[l]
                if (list.isEmpty()) continue
                val desired = HashMap<Int, Float>(list.size * 2)
                for (node in list) {
                    val ys = out[node].filter { layer[it] < l }.map { pos[it] }
                    desired[node] = if (ys.isEmpty()) pos[node] else ys.average().toFloat()
                }
                list.sortBy { desired[it] ?: pos[it] }
                val half = (list.size - 1) / 2f
                for ((idx, node) in list.withIndex()) {
                    pos[node] = (idx - half) * Y_GAP
                }
            }
            for (l in maxLayer - 1 downTo 0) {
                val list = layers[l]
                if (list.isEmpty()) continue
                val desired = HashMap<Int, Float>(list.size * 2)
                for (node in list) {
                    val ys = inc[node].filter { layer[it] > l }.map { pos[it] }
                    desired[node] = if (ys.isEmpty()) pos[node] else ys.average().toFloat()
                }
                list.sortBy { desired[it] ?: pos[it] }
                val half = (list.size - 1) / 2f
                for ((idx, node) in list.withIndex()) {
                    pos[node] = (idx - half) * Y_GAP
                }
            }
        }

        val x = FloatArray(n)
        val y = FloatArray(n)
        // 依赖放在右侧：第 0 层（没有前置）在最右，依赖它的节点依次往左
        for (l in 0..maxLayer) {
            val columnX = (maxLayer - l) * X_GAP
            for (node in layers[l]) {
                x[node] = columnX
                y[node] = pos[node]
            }
        }

        // 孤立节点：主图下方单独一排，避免插进依赖链中间
        if (isolated.isNotEmpty()) {
            var maxY = 0f
            for (i in connected) {
                maxY = max(maxY, y[i])
            }
            val rowTop = if (connected.isEmpty()) 0f else maxY + Y_GAP * 2f
            val cols = min(isolated.size, 8)
            val half = (cols - 1) / 2f
            for ((idx, node) in isolated.withIndex()) {
                val row = idx / cols
                val col = idx % cols
                x[node] = (col - half) * X_GAP * 0.65f
                y[node] = rowTop + row * Y_GAP
            }
        }

        return Result(x, y, radius, index)
    }
}

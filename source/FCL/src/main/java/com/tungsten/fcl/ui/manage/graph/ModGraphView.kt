package com.tungsten.fcl.ui.manage.graph

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.tungsten.fcl.R
import com.tungsten.fclcore.mod.ModDependency
import com.tungsten.fclcore.mod.graph.ModGraph
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 原生 Canvas 依赖关系图：平移、双指缩放、点选高亮、标签避让。
 *
 * 节点/边在屏幕坐标里画，线宽和字号不随缩放变化；布局计算在后台线程完成。
 */
class ModGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onNodeSelected: ((ModGraph.Node?) -> Unit)? = null
    var onLayoutStateChanged: ((Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    private var graph: ModGraph? = null
    private var positions: ModGraphLayout.Result? = null
    private var pendingLayout = false
    private var layoutThread: Thread? = null
    private var layoutGeneration = 0

    private var selectedId: String? = null
    private var hideIsolated = false
    private val categoryColorById = HashMap<String, Int>()
    private val displayNameById = HashMap<String, String>()

    private var viewScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val arrowPath = Path()

    private val optionalDash by lazy { DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f) }
    private val incompatibleDash by lazy { DashPathEffect(floatArrayOf(dp(3f), dp(3f)), 0f) }
    private val embeddedDash by lazy { DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f) }
    private val missingDash by lazy { DashPathEffect(floatArrayOf(dp(3f), dp(2f)), 0f) }
    private val disabledDash by lazy { DashPathEffect(floatArrayOf(dp(4f), dp(3f)), 0f) }

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val old = viewScale
                if (old <= 0f) return false
                val next = (old * detector.scaleFactor).coerceIn(0.08f, 4f)
                if (next == old) return true
                val focusX = detector.focusX
                val focusY = detector.focusY
                val worldX = (focusX - offsetX) / old
                val worldY = (focusY - offsetY) / old
                viewScale = next
                offsetX = focusX - worldX * next
                offsetY = focusY - worldY * next
                invalidate()
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTap(e.x, e.y)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                fit()
                return true
            }
        })

    private var lastTouchX = 0f
    private var lastTouchY = 0f

    init {
        isClickable = true
    }

    fun setGraph(graph: ModGraph) {
        this.graph = graph
        positions = null
        selectedId = null
        categoryColorById.clear()
        invalidate()
        layoutThread?.interrupt()
        if (graph.isEmpty()) {
            onLayoutStateChanged?.invoke(false)
            return
        }
        if (width > 0 && height > 0) {
            startLayout()
        } else {
            pendingLayout = true
        }
    }

    fun setHideIsolated(hide: Boolean) {
        if (hideIsolated == hide) return
        hideIsolated = hide
        invalidate()
    }

    fun setNodeCategoryColor(nodeId: String, color: Int) {
        categoryColorById[nodeId] = color
        invalidate()
    }

    /** 中文译名等显示名；不改变图数据里的原始 mod 名称。 */
    fun setDisplayNames(names: Map<String, String>) {
        displayNameById.clear()
        displayNameById.putAll(names)
        invalidate()
    }

    fun selectNode(id: String?) {
        if (selectedId == id) return
        selectedId = id
        val node = id?.let { graph?.getNode(it) }
        onNodeSelected?.invoke(node)
        invalidate()
    }

    fun fit() {
        val g = graph ?: return
        val layout = positions ?: return
        val indices = g.nodes.asSequence()
            .filter { isVisible(it) }
            .mapNotNull { layout.index[it.id] }
            .toList()
        if (indices.isEmpty() || width == 0 || height == 0) return
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in indices) {
            minX = min(minX, layout.x[i] - layout.radius[i])
            maxX = max(maxX, layout.x[i] + layout.radius[i])
            minY = min(minY, layout.y[i] - layout.radius[i])
            maxY = max(maxY, layout.y[i] + layout.radius[i])
        }
        val contentW = max(1f, maxX - minX)
        val contentH = max(1f, maxY - minY)
        val fitScale = min(width / contentW, height / contentH) * 0.88f
        viewScale = fitScale.coerceIn(0.08f, 2.5f)
        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        offsetX = width / 2f - cx * viewScale
        offsetY = height / 2f - cy * viewScale
        invalidate()
    }

    private fun startLayout() {
        val g = graph ?: return
        pendingLayout = false
        layoutGeneration++
        val generation = layoutGeneration
        onLayoutStateChanged?.invoke(true)
        val thread = Thread {
            val result = ModGraphLayout.compute(g)
            post {
                if (generation != layoutGeneration) return@post
                positions = result
                fit()
                onLayoutStateChanged?.invoke(false)
                invalidate()
            }
        }
        thread.isDaemon = true
        layoutThread = thread
        thread.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (pendingLayout && w > 0 && h > 0) {
            startLayout()
        } else if (positions != null && w > 0 && h > 0) {
            // 旋转/分屏后重新适配，避免图停在旧的视口位置
            fit()
        }
    }

    override fun onDetachedFromWindow() {
        layoutGeneration++
        layoutThread?.interrupt()
        layoutThread = null
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    if (hypot(dx, dy) > dp(1.5f)) {
                        offsetX += dx
                        offsetY += dy
                        invalidate()
                    }
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // 双指缩放结束后剩下一根手指：把起点重置到剩余手指，避免下一帧跳一下
                val upIndex = event.actionIndex
                val remainIndex = if (upIndex == 0) 1 else 0
                if (remainIndex < event.pointerCount) {
                    lastTouchX = event.getX(remainIndex)
                    lastTouchY = event.getY(remainIndex)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
        }
        return true
    }

    private fun handleTap(x: Float, y: Float) {
        val g = graph ?: return
        val layout = positions ?: return
        var best: ModGraph.Node? = null
        var bestDistance = Float.MAX_VALUE
        for (node in g.nodes) {
            if (!isVisible(node)) continue
            val i = layout.index[node.id] ?: continue
            val sx = layout.x[i] * viewScale + offsetX
            val sy = layout.y[i] * viewScale + offsetY
            val radius = max(layout.radius[i] * viewScale, dp(14f))
            val distance = hypot(x - sx, y - sy)
            if (distance <= radius && distance < bestDistance) {
                best = node
                bestDistance = distance
            }
        }
        selectNode(best?.id)
    }

    private fun isVisible(node: ModGraph.Node): Boolean {
        return !(hideIsolated && node.isIsolated)
    }

    private fun inkColor(): Int = ThemeEngine.getInstance().getTheme().getColor2()

    private fun accentColor(): Int = ThemeEngine.getInstance().getTheme().getColor()

    private fun background(): Int = ContextCompat.getColor(context, R.color.dialog_background)

    private fun alpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val g = graph ?: return
        val layout = positions ?: return
        val ink = inkColor()
        val accent = accentColor()
        val selected = selectedId?.let { g.getNode(it) }
        val near: Set<String> = if (selected != null) {
            buildSet {
                add(selected.id)
                selected.incoming.forEach { add(it.source.id) }
                selected.outgoing.forEach { add(it.target.id) }
            }
        } else {
            emptySet()
        }

        drawEdges(canvas, g, layout, ink, selected)
        drawNodes(canvas, g, layout, ink, accent, selected, near)
        drawLabels(canvas, g, layout, ink, accent, selected, near)
    }

    private fun drawEdges(
        canvas: Canvas,
        graph: ModGraph,
        layout: ModGraphLayout.Result,
        ink: Int,
        selected: ModGraph.Node?
    ) {
        for (edge in graph.edges) {
            if (!isVisible(edge.source) || !isVisible(edge.target)) continue
            val a = layout.index[edge.source.id] ?: continue
            val b = layout.index[edge.target.id] ?: continue
            val sx = layout.x[a] * viewScale + offsetX
            val sy = layout.y[a] * viewScale + offsetY
            val tx = layout.x[b] * viewScale + offsetX
            val ty = layout.y[b] * viewScale + offsetY
            val sr = layout.radius[a] * viewScale
            val tr = layout.radius[b] * viewScale
            val dx = tx - sx
            val dy = ty - sy
            val distance = hypot(dx, dy)
            if (distance < 1f) continue
            val ux = dx / distance
            val uy = dy / distance
            val x1 = sx + ux * sr
            val y1 = sy + uy * sr
            val x2 = tx - ux * (tr + dp(3f))
            val y2 = ty - uy * (tr + dp(3f))

            val touchesSelection = selected != null &&
                    (edge.source.id == selected.id || edge.target.id == selected.id)
            val dim = selected != null && !touchesSelection
            val color = when (edge.type) {
                ModDependency.Type.INCOMPATIBLE -> 0xFFE05252.toInt()
                else -> ink
            }
            edgePaint.color = alpha(color, when {
                dim -> 0.06f
                touchesSelection -> 0.95f
                edge.type == ModDependency.Type.REQUIRED -> 0.42f
                else -> 0.55f
            })
            edgePaint.strokeWidth = dp(if (touchesSelection) 1.8f else 1.1f)
            edgePaint.pathEffect = when (edge.type) {
                ModDependency.Type.REQUIRED -> null
                ModDependency.Type.OPTIONAL -> optionalDash
                ModDependency.Type.INCOMPATIBLE -> incompatibleDash
                ModDependency.Type.EMBEDDED -> embeddedDash
            }
            canvas.drawLine(x1, y1, x2, y2, edgePaint)

            if (viewScale >= 0.55f &&
                (edge.type == ModDependency.Type.REQUIRED || edge.type == ModDependency.Type.INCOMPATIBLE)
            ) {
                val head = dp(4.5f)
                arrowPath.reset()
                arrowPath.moveTo(x2, y2)
                arrowPath.lineTo(x2 - ux * head - uy * head * 0.55f, y2 - uy * head + ux * head * 0.55f)
                arrowPath.lineTo(x2 - ux * head + uy * head * 0.55f, y2 - uy * head - ux * head * 0.55f)
                arrowPath.close()
                edgePaint.pathEffect = null
                edgePaint.style = Paint.Style.FILL
                canvas.drawPath(arrowPath, edgePaint)
                edgePaint.style = Paint.Style.STROKE
            }
        }
    }

    private fun drawNodes(
        canvas: Canvas,
        graph: ModGraph,
        layout: ModGraphLayout.Result,
        ink: Int,
        accent: Int,
        selected: ModGraph.Node?,
        near: Set<String>
    ) {
        for (node in graph.nodes) {
            if (!isVisible(node)) continue
            val i = layout.index[node.id] ?: continue
            val sx = layout.x[i] * viewScale + offsetX
            val sy = layout.y[i] * viewScale + offsetY
            val radius = max(layout.radius[i] * viewScale, dp(3f))
            val dim = selected != null && node.id != selected.id && node.id !in near
            val category = categoryColorById[node.id]
            val base = category ?: alpha(ink, if (node.isHub) 0.95f else 0.55f)

            nodePaint.style = Paint.Style.FILL
            nodePaint.color = when {
                node.isMissing -> Color.TRANSPARENT
                dim -> alpha(base, 0.15f)
                !node.isActive -> alpha(base, 0.28f)
                else -> base
            }
            canvas.drawCircle(sx, sy, radius, nodePaint)

            strokePaint.color = when {
                dim -> alpha(ink, 0.12f)
                node.id == selectedId -> accent
                node.isMissing -> alpha(ink, 0.9f)
                node.isHub -> accent
                !node.isActive -> alpha(ink, 0.45f)
                else -> alpha(ink, 0.4f)
            }
            strokePaint.strokeWidth = when {
                node.id == selectedId -> dp(2.4f)
                node.isHub -> dp(1.8f)
                else -> dp(1.0f)
            }
            strokePaint.pathEffect = when {
                node.isMissing -> missingDash
                !node.isActive -> disabledDash
                else -> null
            }
            canvas.drawCircle(sx, sy, radius, strokePaint)
        }
    }

    private fun drawLabels(
        canvas: Canvas,
        graph: ModGraph,
        layout: ModGraphLayout.Result,
        ink: Int,
        accent: Int,
        selected: ModGraph.Node?,
        near: Set<String>
    ) {
        val placed = ArrayList<RectF>()
        val ordered = graph.nodes.sortedByDescending { labelPriority(it) }
        val bg = background()
        var drawn = 0
        for (node in ordered) {
            if (drawn >= 80) break
            if (!isVisible(node)) continue
            if (selected != null && node.id != selected.id && node.id !in near) continue
            val show = node.id == selectedId ||
                    (node.isHub && viewScale >= 0.15f) ||
                    (node.inDegree >= 2 && viewScale >= 0.45f) ||
                    viewScale >= 0.85f
            if (!show) continue
            val i = layout.index[node.id] ?: continue
            val sx = layout.x[i] * viewScale + offsetX
            val sy = layout.y[i] * viewScale + offsetY
            val sr = max(layout.radius[i] * viewScale, dp(3f))
            val bold = node.isHub || node.id == selectedId
            val textSize = dp(if (bold) 11.5f else 10.5f)
            labelPaint.textSize = textSize
            labelPaint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            labelPaint.color = if (node.id == selectedId) accent else ink
            labelStrokePaint.textSize = textSize
            labelStrokePaint.typeface = labelPaint.typeface
            labelStrokePaint.strokeWidth = dp(3f)
            labelStrokePaint.color = bg

            val text = labelOf(node)
            val textWidth = labelPaint.measureText(text)
            val textHeight = textSize
            val candidates = arrayOf(
                Triple(sx, sy + sr + textHeight * 1.15f, Paint.Align.CENTER),
                Triple(sx, sy - sr - textHeight * 0.35f, Paint.Align.CENTER),
                Triple(sx + sr + dp(4f), sy + textHeight * 0.35f, Paint.Align.LEFT),
                Triple(sx - sr - dp(4f), sy + textHeight * 0.35f, Paint.Align.RIGHT)
            )
            var chosenX = 0f
            var chosenY = 0f
            var chosenAlign: Paint.Align? = null
            var chosenRect: RectF? = null
            for ((x, baseline, align) in candidates) {
                val left = when (align) {
                    Paint.Align.CENTER -> x - textWidth / 2f
                    Paint.Align.LEFT -> x
                    else -> x - textWidth
                }
                val rect = RectF(left, baseline - textHeight * 0.85f, left + textWidth, baseline + textHeight * 0.25f)
                // 外扩 2dp：文字描边和 CJK 字形的实际高度都比测量值略大
                val padded = RectF(rect)
                padded.inset(-dp(2f), -dp(1f))
                if (padded.left < 2f || padded.right > width - 2f ||
                    padded.top < 2f || padded.bottom > height - 2f
                ) {
                    continue
                }
                if (placed.any { RectF.intersects(it, padded) }) {
                    continue
                }
                chosenX = x
                chosenY = baseline
                chosenAlign = align
                chosenRect = padded
                break
            }
            if (chosenRect == null || chosenAlign == null) {
                continue
            }
            labelPaint.textAlign = chosenAlign
            labelStrokePaint.textAlign = chosenAlign
            canvas.drawText(text, chosenX, chosenY, labelStrokePaint)
            canvas.drawText(text, chosenX, chosenY, labelPaint)
            placed.add(chosenRect)
            drawn++
        }
    }

    private fun labelPriority(node: ModGraph.Node): Int {
        if (node.id == selectedId) return 10000
        return (if (node.isHub) 5000 else 0) + node.inDegree * 10 + node.degree
    }

    private fun labelOf(node: ModGraph.Node): String {
        val name = displayNameById[node.id] ?: node.name
        return when {
            node.isMissing -> context.getString(R.string.mods_graph_missing) + " · " + name
            !node.isActive -> name + " (" + context.getString(R.string.mods_graph_disabled) + ")"
            else -> name
        }
    }
}

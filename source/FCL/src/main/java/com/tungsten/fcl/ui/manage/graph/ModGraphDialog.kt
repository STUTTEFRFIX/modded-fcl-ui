package com.tungsten.fcl.ui.manage.graph

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.tungsten.fcl.R
import com.tungsten.fclcore.mod.LocalModFile
import com.tungsten.fclcore.mod.RemoteMod
import com.tungsten.fcl.util.ModTranslations
import com.tungsten.fclcore.mod.graph.ModGraph
import com.tungsten.fclcore.mod.graph.ModGraphBuilder
import com.tungsten.fcllibrary.component.dialog.FCLDialog
import com.tungsten.fcllibrary.component.theme.ThemeEngine
import com.tungsten.fcllibrary.component.view.FCLCheckBox
import com.tungsten.fcllibrary.component.view.FCLImageButton
import com.tungsten.fcllibrary.component.view.FCLProgressBar
import com.tungsten.fcllibrary.component.view.FCLTextView
import com.tungsten.fcllibrary.util.LocaleUtils
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 模组关系图对话框：离线依赖图立即显示，Modrinth 分类在后台补全。
 *
 * 数据来源分两层：
 * 1. 本地 jar 元数据（fabric.mod.json / mods.toml / ...）→ 节点与依赖边；
 * 2. 打开后按 SHA-1 反查 Modrinth → 分类、简介、图标（仅用于着色和详情，不阻塞本地图）。
 */
class ModGraphDialog(
    context: Context,
    private val mods: List<LocalModFile>,
    private val knownRemote: Map<String, RemoteMod> = emptyMap()
) : FCLDialog(context), View.OnClickListener {

    private lateinit var graphView: ModGraphView
    private lateinit var closeButton: FCLImageButton
    private lateinit var summaryView: FCLTextView
    private lateinit var hideIsolated: FCLCheckBox
    private lateinit var online: FCLCheckBox
    private lateinit var progress: FCLProgressBar
    private lateinit var emptyView: FCLTextView
    private lateinit var onlineStatus: FCLTextView
    private lateinit var legend: LinearLayout
    private lateinit var detailName: FCLTextView
    private lateinit var detailMeta: FCLTextView
    private lateinit var detailDeps: FCLTextView
    private lateinit var detailDependents: FCLTextView
    private lateinit var detailStatus: FCLTextView

    private var graph: ModGraph? = null
    private var selectedNode: ModGraph.Node? = null
    private val inputs = ArrayList<ModGraphBuilder.Input>()
    private val fileByNodeId = HashMap<String, LocalModFile>()
    private val remoteById = HashMap<String, RemoteMod>()
    private val categoryById = HashMap<String, String>()
    private val categoryColors = HashMap<String, Int>()
    private val displayNames = HashMap<String, String>()

    private val executor = Executors.newFixedThreadPool(3)

    @Volatile
    private var enrichGeneration = 0

    @Volatile
    private var destroyed = false

    private var lastLegendSignature = ""
    private var enrichmentStarted = false

    private val isChinese: Boolean by lazy { LocaleUtils.isChinese(context) }

    private val density = context.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

    init {
        setCancelable(true)
        setContentView(R.layout.dialog_mod_graph)
        window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )

        graphView = findViewById<ModGraphView>(R.id.graph_view)!!
        closeButton = findViewById<FCLImageButton>(R.id.close)!!
        summaryView = findViewById<FCLTextView>(R.id.summary)!!
        hideIsolated = findViewById<FCLCheckBox>(R.id.hide_isolated)!!
        online = findViewById<FCLCheckBox>(R.id.online)!!
        progress = findViewById<FCLProgressBar>(R.id.progress)!!
        emptyView = findViewById<FCLTextView>(R.id.empty)!!
        onlineStatus = findViewById<FCLTextView>(R.id.online_status)!!
        legend = findViewById<LinearLayout>(R.id.legend)!!
        detailName = findViewById<FCLTextView>(R.id.detail_name)!!
        detailMeta = findViewById<FCLTextView>(R.id.detail_meta)!!
        detailDeps = findViewById<FCLTextView>(R.id.detail_deps)!!
        detailDependents = findViewById<FCLTextView>(R.id.detail_dependents)!!
        detailStatus = findViewById<FCLTextView>(R.id.detail_status)!!

        closeButton.setOnClickListener(this)
        hideIsolated.setOnCheckedChangeListener { _, checked -> graphView.setHideIsolated(checked) }
        online.setOnCheckedChangeListener { _, checked ->
            if (checked) enrichOnline() else stopEnrichment()
        }
        graphView.onNodeSelected = { node -> showDetail(node) }
        graphView.onLayoutStateChanged = { running ->
            progress.visibility = if (running) View.VISIBLE else View.GONE
            // 布局算完再开始网络补全，避免力导向计算和 100+ 个 SHA-1/HTTP 请求抢 CPU
            if (!running && online.isChecked) {
                enrichOnline()
            }
        }

        buildGraph()
    }

    private fun buildGraph() {
        for (file in mods) {
            if (file.isOld) continue
            val input = ModGraphBuilder.Input.from(file)
            if (input.id.isBlank()) continue
            inputs.add(input)
            // 同一 mod id 可能同时有 .jar 和 .jar.disabled；和 ModGraphBuilder 一样启用优先
            val previous = fileByNodeId[input.id]
            if (previous == null || (!previous.isActive && file.isActive)) {
                fileByNodeId[input.id] = file
                knownRemote[file.fileName]?.let { remoteById[input.id] = it }
            }
        }
        val built = ModGraphBuilder.build(inputs)
        graph = built
        graphView.setGraph(built)
        // setGraph 会清空节点分类色，所以已知的远程信息要在它之后应用
        for ((id, remote) in remoteById.toList()) {
            applyRemote(id, remote, updateUi = true)
        }
        applyChineseNames(built)
        if (built.isEmpty) {
            emptyView.visibility = View.VISIBLE
            progress.visibility = View.GONE
        }
        val stats = built.stats
        summaryView.text = context.getString(
            R.string.mods_graph_summary,
            stats.nodes, stats.edges, stats.hubs, stats.isolated, stats.missing
        )
        rebuildLegend()
        showDetail(null)
    }

    override fun onClick(v: View) {
        if (v === closeButton) {
            dismiss()
        }
    }

    private fun showDetail(node: ModGraph.Node?) {
        selectedNode = node
        if (node == null) {
            detailName.text = context.getString(R.string.mods_graph_no_selection)
            detailMeta.text = ""
            detailDeps.text = ""
            detailDependents.text = ""
            detailStatus.text = ""
            return
        }
        val category = categoryLabel(categoryById[node.id])
        detailName.text = buildString {
            append(displayName(node))
            if (node.version.isNotBlank()) {
                append(' ').append(node.version)
            }
        }
        detailMeta.text = buildString {
            append(category)
            append(" · ")
            append(context.getString(R.string.mods_graph_direct_deps)).append(' ').append(node.outDegree)
            append(" · ")
            append(context.getString(R.string.mods_graph_dependents)).append(' ').append(node.inDegree)
        }
        detailDeps.text = context.getString(R.string.mods_graph_direct_deps) + ": " +
                names(node.outgoing.map { it.target })
        detailDependents.text = context.getString(R.string.mods_graph_dependents) + ": " +
                names(node.incoming.map { it.source })
        detailStatus.text = when {
            node.isMissing -> context.getString(R.string.mods_graph_missing)
            !node.isActive -> context.getString(R.string.mods_graph_disabled)
            node.isHub -> context.getString(R.string.mods_graph_hub)
            node.isIsolated -> context.getString(R.string.mods_graph_isolated)
            else -> ""
        }
    }

    private fun names(nodes: List<ModGraph.Node>): String {
        if (nodes.isEmpty()) {
            return context.getString(R.string.mods_graph_no_deps)
        }
        val head = nodes.take(8).joinToString(", ") { displayName(it) }
        return if (nodes.size > 8) "$head …" else head
    }

    private fun displayName(node: ModGraph.Node): String {
        return displayNames[node.id] ?: node.name
    }

    /**
     * 用 FCL 自带的 mcmod.cn 译名替换图上的英文名。
     * 缺失前置节点只有 mod id，也尝试查一次译名表。
     */
    private fun applyChineseNames(graph: ModGraph) {
        if (!isChinese) return
        displayNames.clear()
        for ((id, file) in fileByNodeId) {
            val name = ModTranslations.MOD.getMod(file.id, file.name)?.name
            if (!name.isNullOrBlank()) {
                displayNames[id] = name
            }
        }
        for (node in graph.nodes) {
            if (node.isMissing) {
                val name = ModTranslations.MOD.getMod(node.id, "")?.name
                if (!name.isNullOrBlank()) {
                    displayNames[node.id] = name
                }
            }
        }
        graphView.setDisplayNames(displayNames)
    }

    private fun categoryLabel(key: String?): String {
        if (key == null) {
            return context.getString(R.string.mods_graph_uncategorized)
        }
        val entry = CATEGORY_ORDER.firstOrNull { it.first == key }
        return when {
            entry == null -> context.getString(R.string.mods_graph_uncategorized)
            isChinese -> entry.second
            else -> entry.third
        }
    }

    private fun applyRemote(id: String, remote: RemoteMod, updateUi: Boolean = true) {
        remoteById[id] = remote
        val (key, _) = primaryCategory(remote.categories ?: emptyList())
        categoryById[id] = key
        val color = colorFor(key)
        categoryColors[id] = color
        if (updateUi) {
            graphView.setNodeCategoryColor(id, color)
            if (selectedNode?.id == id) {
                showDetail(selectedNode)
            }
            rebuildLegendIfNeeded()
        }
    }

    private fun primaryCategory(categories: List<String>): Pair<String, String> {
        for (entry in CATEGORY_ORDER) {
            if (categories.contains(entry.first)) {
                return entry.first to entry.third
            }
        }
        return "uncategorized" to context.getString(R.string.mods_graph_uncategorized)
    }

    private fun colorFor(key: String): Int {
        val index = CATEGORY_ORDER.indexOfFirst { it.first == key }
        return if (index >= 0) PALETTE[index % PALETTE.size] else PALETTE.last()
    }

    private fun enrichOnline() {
        if (enrichmentStarted) return
        val targets = fileByNodeId.entries
            .filter { (id, _) -> graph?.getNode(id)?.isMissing == false && !remoteById.containsKey(id) }
            .toList()
        if (targets.isEmpty()) {
            onlineStatus.visibility = View.GONE
            progress.visibility = View.GONE
            return
        }
        enrichmentStarted = true
        val generation = ++enrichGeneration
        val total = targets.size
        val done = AtomicInteger(0)
        progress.visibility = View.VISIBLE
        onlineStatus.visibility = View.VISIBLE
        onlineStatus.text = context.getString(R.string.mods_graph_online_progress, 0, total)
        val repository = RemoteMod.Type.MODRINTH.remoteModRepository

        for ((id, file) in targets) {
            executor.execute {
                if (generation != enrichGeneration || destroyed) return@execute
                var remote: RemoteMod? = null
                try {
                    val version = repository.getRemoteVersionByLocalFile(file, file.file)
                    if (version.isPresent) {
                        remote = repository.getModById(version.get().modid())
                    }
                } catch (ignored: Throwable) {
                    // 单个模组查询失败不影响其它节点；玩家自制的 jar 本来就不在 Modrinth
                }
                val found = remote
                graphView.post {
                    if (generation != enrichGeneration || destroyed) return@post
                    if (found != null) {
                        applyRemote(id, found)
                    }
                    val current = done.incrementAndGet()
                    if (current >= total) {
                        progress.visibility = View.GONE
                        onlineStatus.text = context.getString(
                            R.string.mods_graph_online_done, remoteById.size, fileByNodeId.size
                        )
                        rebuildLegendIfNeeded()
                        graphView.invalidate()
                    } else if (current % 5 == 0) {
                        onlineStatus.text = context.getString(
                            R.string.mods_graph_online_progress, current, total
                        )
                    }
                }
            }
        }
    }

    private fun stopEnrichment() {
        enrichGeneration++
        enrichmentStarted = false
        progress.visibility = View.GONE
        onlineStatus.visibility = View.GONE
    }

    private fun rebuildLegendIfNeeded() {
        val signature = categoryById.values.distinct().sorted().joinToString("|")
        if (signature == lastLegendSignature) {
            return
        }
        rebuildLegend()
    }

    private fun rebuildLegend() {
        legend.removeAllViews()
        lastLegendSignature = categoryById.values.distinct().sorted().joinToString("|")

        addLegendMark("—", inkColor(), context.getString(R.string.mods_graph_legend_required))
        addLegendMark("--", inkColor(), context.getString(R.string.mods_graph_legend_optional))
        addLegendMark("--", 0xFFE05252.toInt(), context.getString(R.string.mods_graph_legend_incompatible))
        addLegendMark("○", inkColor(), context.getString(R.string.mods_graph_legend_missing))
        addLegendMark("○", inkColor(), context.getString(R.string.mods_graph_legend_disabled))

        val keys = categoryById.values.distinct()
            .sortedBy { key -> CATEGORY_ORDER.indexOfFirst { it.first == key }.let { if (it < 0) Int.MAX_VALUE else it } }
        for (key in keys) {
            addLegendDot(colorFor(key), categoryLabel(key))
        }
    }

    private fun addLegendMark(mark: String, color: Int, label: String) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val markView = TextView(context).apply {
            text = mark
            setTextColor(color)
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        val labelView = TextView(context).apply {
            text = label
            setTextColor(inkColor())
            textSize = 10f
        }
        item.addView(markView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(3f).toInt() })
        item.addView(labelView)
        legend.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12f).toInt() })
    }

    private fun addLegendDot(color: Int, label: String) {
        val item = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val size = dp(8f).toInt()
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
        }
        item.addView(dot, LinearLayout.LayoutParams(size, size).apply {
            marginEnd = dp(4f).toInt()
        })
        item.addView(TextView(context).apply {
            text = label
            setTextColor(inkColor())
            textSize = 10f
        })
        legend.addView(item, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(12f).toInt() })
    }

    private fun inkColor(): Int = ThemeEngine.getInstance().getTheme().getColor2()

    override fun dismiss() {
        destroyed = true
        enrichGeneration++
        executor.shutdownNow()
        super.dismiss()
    }

    companion object {
        private val CATEGORY_ORDER = listOf(
            Triple("technology", "科技", "Technology"),
            Triple("magic", "魔法", "Magic"),
            Triple("adventure", "冒险", "Adventure"),
            Triple("worldgen", "世界生成", "Worldgen"),
            Triple("decoration", "建筑装饰", "Decoration"),
            Triple("building", "建筑", "Building"),
            Triple("storage", "存储物流", "Storage"),
            Triple("transportation", "运输", "Transport"),
            Triple("food", "农业食物", "Food"),
            Triple("agriculture", "农业", "Agriculture"),
            Triple("equipment", "装备工具", "Equipment"),
            Triple("combat", "战斗", "Combat"),
            Triple("mobs", "生物", "Mobs"),
            Triple("quests", "任务", "Quests"),
            Triple("game-mechanics", "机制", "Mechanics"),
            Triple("management", "管理", "Management"),
            Triple("utility", "工具", "Utility"),
            Triple("optimization", "优化", "Optimization"),
            Triple("library", "前置库", "Library")
        )

        private val PALETTE = intArrayOf(
            0xFF4E79A7.toInt(),
            0xFFF28E2B.toInt(),
            0xFFE15759.toInt(),
            0xFF76B7B2.toInt(),
            0xFF59A14F.toInt(),
            0xFFB6992D.toInt(),
            0xFFB07AA1.toInt(),
            0xFFE377C2.toInt(),
            0xFF9C755F.toInt(),
            0xFF7F7F7F.toInt()
        )
    }
}

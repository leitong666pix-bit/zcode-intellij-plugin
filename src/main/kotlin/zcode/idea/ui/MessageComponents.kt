package zcode.idea.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import zcode.idea.core.ToolCallInfo
import zcode.idea.core.ToolStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.JComponent
import javax.swing.Box

/**
 * 用户消息行：整行宽度的透明容器，气泡靠右（EAST），宽度按“最宽一行”自适应（上限 70% 视口宽）。
 * [contextBlock] 非空时在气泡下方整行展示一个默认收起的“IDE 上下文”折叠区，
 * 点击可展开查看（历史会话与实时发送统一走这里，不再把大段上下文糊在气泡里）。
 */
class UserMessagePanel(text: String, contextBlock: String?) : JPanel(BorderLayout(0, 2)) {

    private val area = readOnlyArea().apply { append(text) }

    /** 宽度必须显式覆盖：气泡父容器（右列）宽度由气泡自身决定，不覆盖会形成循环取默认整行宽。 */
    private val bubble = object : BubblePanel(ChatColors.userBubble, arc = 14) {
        override fun getPreferredSize(): Dimension {
            val ins = border.getBorderInsets(this)
            val availW = viewportWidthOf(this) - ins.left - ins.right
            // stringWidth 对含换行的整串求和会把自然宽度算爆，这里按最宽一行取值
            val natural = area.text.lines().maxOfOrNull { area.getFontMetrics(area.font).stringWidth(it) } ?: 0
            val maxW = (availW * 0.7f).toInt().coerceAtLeast(120)
            val innerW = (natural + 2).coerceAtMost(maxW).coerceAtLeast(40)
            return Dimension(innerW + ins.left + ins.right, area.heightAt(innerW) + ins.top + ins.bottom)
        }

        override fun getMaximumSize(): Dimension = Dimension(preferredSize.width, preferredSize.height)
    }.apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8, 12)
        add(area, BorderLayout.CENTER)
    }

    init {
        isOpaque = false
        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            isOpaque = false
        }
        bubble.alignmentX = Component.RIGHT_ALIGNMENT
        column.add(bubble)
        if (contextBlock != null) {
            // 气泡行 + 整行宽的折叠上下文：气泡仍在右上，上下文独占下方一行便于阅读长文本
            val top = JPanel(BorderLayout()).apply {
                isOpaque = false
                add(column, BorderLayout.EAST)
            }
            val contextArea = readOnlyArea(ChatColors.dim).apply {
                font = font.deriveFont(font.size2D - 1f)
                this.text = contextBlock
            }
            val section = CollapsibleSection("IDE 上下文 · ${contextBlock.length} 字", contextArea)
            add(top, BorderLayout.CENTER)
            add(section.apply { border = JBUI.Borders.empty(2, 10, 0, 0) }, BorderLayout.SOUTH)
        } else {
            add(column, BorderLayout.EAST)
        }
    }

    /** 整行宽度参与纵向流式布局，高度按内容。 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/**
 * 助手消息：头部 + 可折叠思考区（思考文本与工具调用按到达顺序交错的时间线） + Markdown 正文 + 脚注。
 * 正文（结论）始终在思考区之后；思考区默认折叠（标题实时显示"思考中 · N 字 · N 个工具"进度），
 * 点击可随时展开查看，[done] 后标题定格为摘要、未手动点开过则保持折叠。
 */
class AssistantMessagePanel(
    onOpenFileRef: (FileReference) -> Unit = {},
    private val highlightCode: ((lang: String, code: String) -> String?)? = null,
    onOpenSymbol: (String) -> Unit = {},
    private val decorateSymbols: ((String, (String) -> Unit) -> Unit)? = null,
) : JPanel(BorderLayout(0, 3)) {

    private val bodyBuf = StringBuilder()
    private val reasoningBuf = StringBuilder()
    private var toolCount = 0
    private val bodyPane = createChatHtmlPane(onOpenFileRef, onOpenSymbol)
    private var renderVersion = 0L
    private var completed = false

    /** 思考区时间线：思考文本段（灰字左竖线）与工具行按真实到达顺序交错堆叠。 */
    private val timeline = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
        isOpaque = false
    }

    /** 当前思考文本段：工具行插入后置空，后续思考另起一段，保住时间顺序。 */
    private var currentSegment: WrappingTextArea? = null

    private val thinking = CollapsibleSection("思考过程", timeline).apply { isVisible = false }
    private val footer = JBLabel("").apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
        isVisible = false
    }
    private val flushTimer = Timer(120) { flushBody() }.apply { isRepeats = false }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(6, 10, 2, 10)
        val header = pluginIcon("/icons/zcode.png", AssistantMessagePanel::class.java)
            ?.let { JBLabel("ZCode", it, SwingConstants.LEFT) }
            ?: JBLabel("ZCode")
        header.font = JBFont.label().asBold()
        add(header, BorderLayout.NORTH)
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.PAGE_AXIS)
            isOpaque = false
        }
        thinking.alignmentX = JComponent.LEFT_ALIGNMENT
        bodyPane.alignmentX = JComponent.LEFT_ALIGNMENT
        center.add(thinking)
        center.add(Box.createVerticalStrut(3))
        center.add(bodyPane)
        add(center, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
    }

    fun appendReasoning(delta: String) {
        reasoningBuf.append(delta)
        val seg = currentSegment ?: newReasoningSegment().also {
            currentSegment = it
            addTimelineChild(it)
        }
        seg.append(delta)
        showThinking()
    }

    /** 工具行进思考区时间线：此后的思考文本另起一段，保持“思考→工具→思考”的真实顺序。 */
    fun addToolCall(row: JComponent) {
        toolCount++
        currentSegment = null
        addTimelineChild(row)
        showThinking()
    }

    private fun addTimelineChild(child: JComponent) {
        if (timeline.componentCount > 0) timeline.add(Box.createVerticalStrut(3))
        child.alignmentX = Component.LEFT_ALIGNMENT
        timeline.add(child)
        revalidate()
    }

    /** 新思考文本段：灰字 + 左侧竖线引用样式，与正文（正常前景色）视觉区分。 */
    private fun newReasoningSegment(): WrappingTextArea = readOnlyArea(ChatColors.dim).apply {
        font = font.deriveFont(font.size2D - 1f)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 2, 0, 0, JBColor.border()),
            JBUI.Borders.empty(0, 8),
        )
    }

    private fun showThinking() {
        thinking.isVisible = true
        // 默认折叠：标题实时更新进度给反馈即可；用户点开后（userToggled）保持其选择，收尾也不强收
        thinking.setTitle(streamingTitle())
    }

    private fun streamingTitle(): String = buildString {
        append("思考中")
        if (reasoningBuf.isNotEmpty()) append(" · ${reasoningBuf.length} 字")
        if (toolCount > 0) append(" · $toolCount 个工具")
    }

    private fun doneTitle(): String = buildString {
        append("已深度思考")
        if (reasoningBuf.isNotEmpty()) append(" · ${reasoningBuf.length} 字")
        if (toolCount > 0) append(" · $toolCount 个工具")
    }

    fun appendText(delta: String) {
        completed = false
        bodyBuf.append(delta)
        if (!flushTimer.isRunning) flushTimer.start()
    }

    private fun flushBody() {
        val version = ++renderVersion
        val html = "<html><body>" + Markdown.toHtml(bodyBuf.toString(), highlightCode) + "</body></html>"
        bodyPane.text = html
        if (completed) decorateSymbols?.invoke(html) { decorated ->
            if (version == renderVersion) bodyPane.text = decorated
        }
    }

    /** turn 结束：停止刷新、收起思考区、显示脚注。 */
    fun done(summary: String?) {
        completed = true
        if (flushTimer.isRunning) flushTimer.stop()
        flushBody()
        if (reasoningBuf.isNotEmpty() || toolCount > 0) {
            if (!thinking.userToggled) thinking.setExpanded(false)
            thinking.setTitle(doneTitle())
        }
        if (!summary.isNullOrBlank()) {
            footer.text = summary
            footer.isVisible = true
        }
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 工具调用卡片：状态图标 + 工具名 + 目标摘要（点击打开文件，tooltip 显示完整入参）。 */
class ToolCallPanel(private val info: ToolCallInfo, private val onOpenFile: (String) -> Unit) :
    BubblePanel(ChatColors.card, arc = 10) {

    private val iconLabel = JBLabel()
    private val nameLabel = JBLabel().apply { font = JBFont.label().asBold() }
    private val targetLabel = dimLabel("")

    init {
        layout = BorderLayout(6, 0)
        border = JBUI.Borders.empty(5, 10)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = info.inputJson?.toString() ?: ""
        add(iconLabel, BorderLayout.WEST)
        add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(nameLabel)
            add(targetLabel)
        }, BorderLayout.CENTER)
        refresh()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                info.filePath?.let(onOpenFile)
            }
        })
    }

    fun refresh() {
        iconLabel.icon = statusIcon()
        nameLabel.text = info.name
        val target = displayTarget()
        targetLabel.text = if (target.isBlank()) "" else "· $target"
        nameLabel.foreground = when (info.status) {
            ToolStatus.FAILED, ToolStatus.DENIED -> JBColor.RED
            else -> JBColor.foreground()
        }
    }

    private fun statusIcon(): Icon = when (info.status) {
        ToolStatus.PENDING, ToolStatus.RUNNING -> AnimatedIcon.Default()
        ToolStatus.DONE -> AllIcons.General.InspectionsOK
        ToolStatus.FAILED -> AllIcons.General.Error
        ToolStatus.DENIED -> AllIcons.Actions.Suspend
    }

    private fun displayTarget(): String {
        info.filePath?.let { return shortenPath(it) }
        info.inputJson?.let { input ->
            for (key in listOf("command", "pattern", "query", "url", "prompt", "path")) {
                input.get(key)?.takeIf { !it.isJsonNull }?.asString?.let { return shortenPath(it) }
            }
        }
        return ""
    }

    private fun shortenPath(path: String): String {
        val normalized = path.replace('\\', '/')
        val parts = normalized.split('/')
        return if (parts.size <= 3) normalized else parts.takeLast(3).joinToString("/")
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        fun fileNameOf(path: String): String = File(path).name
    }
}

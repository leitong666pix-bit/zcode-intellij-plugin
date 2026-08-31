package zcode.idea.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent
import javax.swing.text.View
import javax.swing.text.html.HTMLEditorKit

/** 聊天 UI 共用配色（自动跟随 IDE 亮/暗主题）。 */
object ChatColors {
    val userBubble = JBColor(0xE7EEF9, 0x2F3B4D)
    val card = JBColor(0xF2F3F7, 0x33363D)
    val codeBlock = JBColor(0xECEDF1, 0x2B2E34)
    val dim: Color get() = UIUtil.getContextHelpForeground()
}

/** 圆角底色卡片：用户消息气泡、工具调用卡片、输入框。 */
open class BubblePanel(private val bg: JBColor, private val arc: Int = 12, private val outline: Boolean = false) : JPanel() {
    init {
        layout = BorderLayout()
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = bg
            g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            if (outline) {
                g2.color = JBColor.border()
                g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
        } finally {
            g2.dispose()
        }
    }
}

/**
 * 取组件所在滚动视口的实际宽度，作为换行排版宽度。
 * 消息组件的直接父容器可能是窄列（用户气泡的右列、折叠区等），甚至首帧前还没有宽度，
 * 一律向上找到 JViewport 才是消息区的真实可用宽度；拿不到时用兜底值，
 * 避免按“整行不换行”的超宽排版导致高度算错/出现横向滚动。
 */
fun viewportWidthOf(c: Component, fallback: Int = 560): Int {
    val vp = SwingUtilities.getAncestorOfClass(javax.swing.JViewport::class.java, c) as? javax.swing.JViewport
    return vp?.width?.takeIf { it > 0 } ?: fallback
}

/**
 * 会按容器实际宽度换行计算高度的只读文本区。
 * JTextArea 自带的换行高度计算在纵向 BoxLayout 里不可靠（首次布局时宽度未知，会按“整行不换行”算高度），
 * 这里在 preferredSize 里用当前父容器宽度重新排版计算。
 */
class WrappingTextArea : JBTextArea() {
    private var cacheKey = -1L
    private var cachedHeight = 0

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (!lineWrap) return base
        if (parent == null) return base
        val outer = layoutWidth()
        return Dimension(base.width.coerceAtMost(outer), heightAt(outer))
    }

    /** 换行排版宽度：优先父容器宽度，其次向上找滚动视口宽度。 */
    private fun layoutWidth(): Int {
        val p = parent
        if (p != null && p.width > 0) return p.width
        return viewportWidthOf(this)
    }

    /** 按指定排版宽度计算换行后的高度（消息气泡按内容宽度测高时也复用这里）。 */
    fun heightAt(width: Int): Int {
        val key = width.toLong() * 1_000_003L + text.length * 31L + text.hashCode()
        if (key != cacheKey) {
            val view = (ui as javax.swing.plaf.TextUI).getRootView(this)
            view.setSize(width.coerceAtLeast(20).toFloat(), 0f)
            cachedHeight = view.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
            cacheKey = key
        }
        return cachedHeight
    }
}

fun readOnlyArea(foreground: Color? = null): WrappingTextArea = WrappingTextArea().apply {
    isEditable = false
    lineWrap = true
    wrapStyleWord = true
    autoscrolls = false
    isOpaque = false
    border = JBUI.Borders.empty()
    foreground?.let { setForeground(it) }
}

/**
 * 按容器实际宽度重排计算高度的 HTML 正文面板。
 * JEditorPane 在纵向 BoxLayout 里首选高度不可靠（按“整行不换行”的超宽排版，实际渲染宽度窄得多，
 * 分到的高度装不下内容，表现为正文被裁剪甚至完全不可见），这里在 preferredSize 里按父容器宽度重新排版。
 */
class WrappingHtmlPane : JBHtmlPane() {
    private var cacheKey = -1L
    private var cachedHeight = 0

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (parent == null) return base
        val outer = if (parent.width > 0) parent.width else viewportWidthOf(this)
        return Dimension(base.width.coerceAtMost(outer), heightAt(outer))
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    private fun heightAt(width: Int): Int {
        val key = width.toLong() * 1_000_003L + text.length * 31L + text.hashCode()
        if (key != cacheKey) {
            val view = (ui as javax.swing.plaf.TextUI).getRootView(this)
            view.setSize(width.coerceAtLeast(20).toFloat(), 0f)
            cachedHeight = view.getPreferredSpan(View.Y_AXIS).toInt() + insets.top + insets.bottom
            cacheKey = key
        }
        return cachedHeight
    }
}

/** 渲染 Markdown 的正文面板（主题自适应、链接可点击、代码块带底色）。 */
fun createChatHtmlPane(): WrappingHtmlPane {
    val pane = WrappingHtmlPane()
    pane.isEditable = false
    pane.isOpaque = false
    pane.border = JBUI.Borders.empty()
    pane.foreground = UIUtil.getLabelForeground()
    val base = JBFont.label()
    val codeBg = String.format("#%06x", ChatColors.codeBlock.rgb and 0xFFFFFF)
    val fg = String.format("#%06x", UIUtil.getLabelForeground().rgb and 0xFFFFFF)
    (pane.editorKit as? HTMLEditorKit)?.styleSheet?.apply {
        // 显式固定正文颜色为正常前景色，保证与思考区（灰字）区分明显，不依赖 HTML 文档默认色
        addRule("body { font-family: '${base.family}'; font-size: ${base.size}pt; color: $fg; }")
        addRule("code, pre { font-family: 'monospaced'; font-size: ${base.size - 1}pt; }")
        addRule("pre { background: $codeBg; }")
    }
    pane.addHyperlinkListener { e ->
        if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) {
            runCatching { BrowserUtil.browse(e.url?.toString() ?: e.description) }
        }
    }
    return pane
}

/** 标题行可点击的折叠小节（思考过程等）。 */
class CollapsibleSection(title: String, private val content: JComponent) : JPanel(BorderLayout(0, 2)) {
    private val header = JBLabel("▸ $title").apply {
        foreground = ChatColors.dim
        font = JBFont.label().biggerOn(-1f)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        border = JBUI.Borders.empty(3, 0)
    }

    var expanded = false
        private set

    /** 用户是否手动点击过（流式期间不再强制展开）。 */
    var userToggled = false
        private set

    init {
        isOpaque = false
        add(header, BorderLayout.NORTH)
        header.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                userToggled = true
                setExpanded(!expanded)
            }
        })
    }

    fun setTitle(title: String) {
        header.text = (if (expanded) "▾ " else "▸ ") + title
    }

    fun setExpanded(value: Boolean) {
        if (expanded == value) return
        expanded = value
        header.text = (if (value) "▾ " else "▸ ") + header.text.substring(2)
        if (value) add(content, BorderLayout.CENTER) else remove(content)
        revalidate()
        repaint()
    }

    /** 防止纵向 BoxLayout 把多余垂直空间分给折叠区（否则会出现空白长条）。 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
}

/** 轻量 Markdown → Swing HTML。覆盖：围栏代码块/行内代码、标题、列表、引用、粗斜体、链接、分隔线。 */
object Markdown {

    fun toHtml(source: String): String {
        val sb = StringBuilder()
        var listOpen: String? = null
        fun closeList() {
            listOpen?.let { sb.append("</").append(it).append('>'); listOpen = null }
        }
        val lines = source.replace("\r\n", "\n").split('\n')
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val t = raw.trimStart()
            when {
                t.startsWith("```") -> {
                    closeList()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        buf.append(lines[i]).append('\n'); i++
                    }
                    i++
                    sb.append("<pre>").append(escape(buf.toString().trimEnd('\n'))).append("</pre>")
                }
                t.isEmpty() -> { closeList(); sb.append("<br>") }
                t.startsWith("#") -> {
                    closeList()
                    val level = t.takeWhile { it == '#' }.length
                    val body = inline(t.dropWhile { it == '#' }.trim())
                    sb.append(
                        when {
                            level <= 1 -> "<h3>$body</h3>"
                            level == 2 -> "<h4>$body</h4>"
                            else -> "<b>$body</b>"
                        }
                    ).append("<br>")
                }
                t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                    if (listOpen != "ul") { closeList(); sb.append("<ul>"); listOpen = "ul" }
                    sb.append("<li>").append(inline(t.substring(2).trim())).append("</li>")
                }
                REGEX_OL.containsMatchIn(t) -> {
                    if (listOpen != "ol") { closeList(); sb.append("<ol>"); listOpen = "ol" }
                    sb.append("<li>").append(inline(t.substringAfter(". ", "").trim())).append("</li>")
                }
                t.startsWith("> ") -> {
                    closeList()
                    sb.append("<blockquote>").append(inline(t.substring(2).trim())).append("</blockquote>")
                }
                REGEX_HR.containsMatchIn(t) -> { closeList(); sb.append("<hr>") }
                else -> { closeList(); sb.append(inline(raw.trimEnd())).append("<br>") }
            }
            i++
        }
        closeList()
        return sb.toString()
    }

    private val REGEX_OL = Regex("^\\d{1,3}\\. ")
    private val REGEX_HR = Regex("^-{3,}$")
    private val REGEX_BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val REGEX_ITALIC = Regex("(?<![\\w*])\\*([^*\n]+)\\*(?![\\w*])")
    private val REGEX_LINK = Regex("\\[([^]]+)]\\(([^)\\s]+)\\)")

    private fun inline(src: String): String {
        val sb = StringBuilder()
        // 行内代码段先切出来，代码内容不再参与其他格式转换
        val parts = escape(src).split('`')
        parts.forEachIndexed { idx, part ->
            if (idx % 2 == 1) {
                sb.append("<code>").append(part).append("</code>")
            } else {
                var s = REGEX_LINK.replace(part) { m -> "<a href=\"${m.groupValues[2]}\">${m.groupValues[1]}</a>" }
                s = REGEX_BOLD.replace(s) { m -> "<b>${m.groupValues[1]}</b>" }
                s = REGEX_ITALIC.replace(s) { m -> "<i>${m.groupValues[1]}</i>" }
                sb.append(s)
            }
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}

fun dimLabel(text: String): JBLabel = JBLabel(text).apply { foreground = ChatColors.dim }

/**
 * 安全加载插件内图标。注意必须显式传入插件自有类（如 Foo::class.java）——
 * 在 `apply {}` 块里写 `javaClass` 拿到的是接收者的类（如 JPanel），JDK 类的 getClassLoader() 返回 null，
 * 会让 IconLoader 直接抛 NPE 导致整个工具窗口初始化失败。
 */
fun pluginIcon(path: String, origin: Class<*>): Icon? = runCatching {
    IconLoader.getIcon(path, origin)
}.getOrNull()

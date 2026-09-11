package zcode.idea.ui

import com.intellij.ide.BrowserUtil
import com.intellij.lang.Language
import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBHtmlPane
import com.intellij.ui.components.JBHtmlPaneConfiguration
import com.intellij.ui.components.JBHtmlPaneStyleConfiguration
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

    /**
     * 表格：GitHub 风格配色。暗色值刻意与面板底色拉开档位（新 UI 暗底约 #1E1F22~#2B2D30），
     * 分隔线至少 +0x20 亮度、表头底/斑马纹也要可辨——初版暗值（0x3C3F44 线/0x2B2E34 表头）"深上加深"不可见，实测翻车点。
     */
    val tableZebra = JBColor(0xF4F6F9, 0x353B43)
    val tableHair = JBColor(0xC7CCD4, 0x50555E)
    val tableHeadBg = JBColor(0xECEFF4, 0x3F454D)

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
    // 只读流式区域：文档更新永远不要移动/滚动 caret（部分场景 append 会触发
    // DefaultCaret.adjustVisibility → scrollRectToVisible，直接操作视口绕过贴底守卫）
    (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
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
class WrappingHtmlPane : JBHtmlPane(JBHtmlPaneStyleConfiguration(), JBHtmlPaneConfiguration()) {
    // 不能用 252 新增的无参构造 JBHtmlPane()：2024.2~2024.4 只有两参构造
    // （242 官方源码核对 + 243 jar javap 实证），编译基线是 2024.2，必须走共有构造路径
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

/** 渲染 Markdown 的正文面板（主题自适应、链接可点击、代码块带底色）。[onOpenFileRef] 接收 `zcodefile:` 引用链接的点击。 */
fun createChatHtmlPane(
    onOpenFileRef: ((FileReference) -> Unit)? = null,
    onOpenSymbol: ((String) -> Unit)? = null,
): WrappingHtmlPane {
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
        val linkColor = String.format("#%06x", JBColor(0x0066CC, 0x6EA8F5).rgb and 0xFFFFFF)
        addRule("a, a code { color: $linkColor; text-decoration: underline; }")
        // GFM 表格：GitHub 风格——无竖线边框，仅横向细线分隔 + 表头浅底加粗下划 + 偶数行斑马纹。
        // HTMLEditorKit 无 border-collapse，四边框会 1px 重叠显厚重，故只用 border-bottom 画单侧横线
        val hairHex = String.format("#%06x", ChatColors.tableHair.rgb and 0xFFFFFF)
        val zebraHex = String.format("#%06x", ChatColors.tableZebra.rgb and 0xFFFFFF)
        val headBgHex = String.format("#%06x", ChatColors.tableHeadBg.rgb and 0xFFFFFF)
        addRule("th, td { padding: 5px 12px; font-size: ${base.size - 1}pt; }")
        addRule("th { font-weight: bold; background: $headBgHex; border-bottom: 2px solid $hairHex; }")
        addRule("td { border-bottom: 1px solid $hairHex; }")
        addRule("td.alt { background-color: $zebraHex; }")
    }
    pane.addHyperlinkListener { e ->
        val desc = e.url?.toString() ?: e.description ?: return@addHyperlinkListener
        val target = FileRefs.parseTarget(desc)
        val symbol = SymbolRefs.parseHref(desc)
        when (e.eventType) {
            HyperlinkEvent.EventType.ENTERED -> {
                pane.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                val anchor = e.sourceElement?.attributes?.getAttribute(javax.swing.text.html.HTML.Tag.A)
                    as? javax.swing.text.AttributeSet
                val title = anchor?.getAttribute(javax.swing.text.html.HTML.Attribute.TITLE)?.toString()
                pane.toolTipText = title ?: when {
                    target != null -> target.tooltip
                    symbol != null -> "跳转到定义：" + symbol
                    else -> desc
                }
            }
            HyperlinkEvent.EventType.EXITED -> {
                pane.cursor = Cursor.getDefaultCursor()
                pane.toolTipText = null
            }
            HyperlinkEvent.EventType.ACTIVATED -> when {
                target != null -> onOpenFileRef?.invoke(target)
                symbol != null -> onOpenSymbol?.invoke(symbol)
                desc.startsWith("https://", true) || desc.startsWith("http://", true) ->
                    runCatching { BrowserUtil.browse(desc) }
            }
        }
    }
    return pane
}

/**
 * 围栏代码块的 IDE 语法着色（[Markdown.toHtml] 的 highlight 回调实现）：
 * 语言名/扩展名 → 平台 Language → SyntaxHighlighter 的 Lexer 切 token → 全局配色方案取
 * token 前景色 → 相邻同色合并后包 <span>。与编辑器同款配色、自动跟随主题；
 * 不认识的语言、超大块（>20KB）、Lexer 异常一律返回 null 降级为纯文本。
 */
fun ideCodeHighlighter(project: Project?): (lang: String, code: String) -> String? = { lang, code ->
    when {
        lang.isBlank() || code.length > 20_000 -> null
        else -> runCatching { highlightCodeBlock(resolveLanguage(lang), code, project) }.getOrNull()
    }
}

/** 围栏语言名 → Language：常见别名 → 语言 ID（大小写容错，如 java→JAVA）→ 按文件扩展名。解析不到返回 null。 */
private fun resolveLanguage(lang: String): Language? {
    val aliases = mapOf(
        "py" to "Python", "python" to "Python",
        "sh" to "Shell Script", "bash" to "Shell Script", "shell" to "Shell Script",
        "yml" to "YAML", "md" to "Markdown", "cpp" to "C++", "c++" to "C++",
    )
    val id = aliases[lang.lowercase()] ?: lang
    return Language.findLanguageByID(id)
        ?: Language.findLanguageByID(id.uppercase())
        ?: (FileTypeManager.getInstance().getFileTypeByExtension(lang) as? LanguageFileType)?.language
}

private fun highlightCodeBlock(language: Language?, code: String, project: Project?): String? {
    if (language == null || language == PlainTextLanguage.INSTANCE) return null
    val hl = SyntaxHighlighterFactory.getSyntaxHighlighter(language, project, null) ?: return null
    val scheme = EditorColorsManager.getInstance().globalScheme
    val lexer: Lexer = hl.highlightingLexer
    val sb = StringBuilder()
    var pendingColor: Color? = null
    var run = StringBuilder()
    fun flush() {
        if (run.isEmpty()) return
        val c = pendingColor
        if (c == null) {
            sb.append(Markdown.escape(run.toString()))
        } else {
            sb.append("<span style=\"color:#").append("%06x".format(c.rgb and 0xFFFFFF))
                .append("\">").append(Markdown.escape(run.toString())).append("</span>")
        }
        run = StringBuilder()
    }
    lexer.start(code)
    while (lexer.tokenType != null) {
        val fg = hl.getTokenHighlights(lexer.tokenType)
            .firstNotNullOfOrNull { scheme.getAttributes(it)?.foregroundColor }
        if (fg != pendingColor) {
            flush()
            pendingColor = fg
        }
        run.append(lexer.tokenText)
        lexer.advance()
    }
    flush()
    return sb.toString()
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

    /** 用户是否手动点击过（此后流式/收尾都不再强制改变其展开状态）。 */
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

/**
 * 贴底跟踪器：区分"用户主动滚离底部"与"内容增高导致的离底"，供流式期间决定是否跟随滚动。
 * 只在滚动条 value 变化时重判——内容增高只动 maximum 不动 value；拖动未松手期间一律暂停吸附，
 * 避免程序贴底与用户拖拽互相打架（拖动结束后的首个事件总是重判，即便值回到原位）。
 * 程序贴底走 setValue 同样触发 value 变化，天然把状态收回 true。
 */
class StickyBottomTracker(private val tolerance: Int) {

    var stuck = true
        private set

    private var lastValue = 0
    private var wasAdjusting = false

    fun attach(bar: javax.swing.JScrollBar) {
        bar.addAdjustmentListener { e ->
            if (e.valueIsAdjusting) {
                wasAdjusting = true
                stuck = false
                return@addAdjustmentListener
            }
            val v = bar.value
            if (v == lastValue && !wasAdjusting) return@addAdjustmentListener
            wasAdjusting = false
            lastValue = v
            stuck = v + bar.visibleAmount >= bar.maximum - tolerance
        }
    }
}

/** 轻量 Markdown → Swing HTML。覆盖：围栏代码块（可注入语法着色）/行内代码、标题、列表、任务列表、引用、粗斜体、删除线、GFM 表格、链接、分隔线。 */
object Markdown {

    /**
     * @param highlight 围栏代码块的着色回调：入参（语言名, 代码），返回可直接放进 `<pre>` 的 HTML
     *（已转义、已带颜色 span），返回 null 则回退为转义纯文本。保持注入式让本对象 headless 可测。
     */
    fun toHtml(source: String, highlight: ((lang: String, code: String) -> String?)? = null): String {
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
                    val lang = t.removePrefix("```").trim()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        buf.append(lines[i]).append('\n'); i++
                    }
                    // 此刻停在闭栏行（或越过末尾），由循环尾统一 i++ 前进：
                    // 旧实现这里多推进一次，闭栏后的第一行会被吞掉
                    val code = buf.toString().trimEnd('\n')
                    val colored = highlight?.let { h -> runCatching { h(lang, code) }.getOrNull() }
                    sb.append("<pre>").append(colored ?: escape(code)).append("</pre>")
                }
                // GFM 表格：含 | 的行 + 下一行分隔行（|:-:|-:|…）触发；空行/无 | 行结束
                t.contains('|') && i + 1 < lines.size && isTableDelimiter(lines[i + 1]) -> {
                    closeList()
                    val aligns = tableAlignsOf(lines[i + 1])
                    sb.append("<table width=\"100%\" cellspacing=\"0\"><tr>")
                    splitTableRow(t).forEachIndexed { idx, cell ->
                        sb.append(cellOpen("th", aligns, idx)).append(inline(cell)).append("</th>")
                    }
                    sb.append("</tr>")
                    i += 2
                    var rowIndex = 0
                    while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                        val zebra = rowIndex % 2 == 1
                        sb.append("<tr>")
                        splitTableRow(lines[i]).forEachIndexed { idx, cell ->
                            sb.append(cellOpen("td", aligns, idx, zebra)).append(inline(cell)).append("</td>")
                        }
                        sb.append("</tr>")
                        i++; rowIndex++
                    }
                    sb.append("</table>")
                    // 停在终止行上交回主循环（循环尾 i++ 前进到真正的下一行）；消费到末尾时同样安全
                    i--
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
                    val (glyph, item) = taskListGlyph(t.substring(2).trim())
                    sb.append("<li>").append(glyph).append(inline(item)).append("</li>")
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

    /** 任务列表项：`- [ ] x` / `- [x] x` → (☐/☑ 前缀, 正文)；普通列表项 → ("", 正文)。 */
    private fun taskListGlyph(item: String): Pair<String, String> = when {
        item.startsWith("[ ] ") -> "☐ " to item.substring(4)
        item.length > 4 && (item.startsWith("[x] ") || item.startsWith("[X] ")) -> "☑ " to item.substring(4)
        else -> "" to item
    }

    /** GFM 表格分隔行：仅由 | - : 空格组成，至少一个 - 且含 |（避免吞掉 --- 分隔线）。 */
    private fun isTableDelimiter(line: String): Boolean {
        val s = line.trim()
        if (!s.contains('-') || !s.contains('|')) return false
        return s.all { it == '-' || it == ':' || it == '|' || it == ' ' }
    }

    /** 分隔行 → 各列对齐（left/right/center）。 */
    private fun tableAlignsOf(delimiter: String): List<String> =
        splitTableRow(delimiter).map { cell ->
            val left = cell.startsWith(':')
            val right = cell.endsWith(':')
            when {
                left && right -> "center"
                right -> "right"
                else -> "left"
            }
        }

    /** 表格行 → 单元格列表：按未转义 | 切分（`\|` 还原字面管道）、去首尾边缘竖线、trim。 */
    private fun splitTableRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.substring(0, s.length - 1)
        val cells = mutableListOf<String>()
        val cur = StringBuilder()
        var j = 0
        while (j < s.length) {
            val c = s[j]
            if (c == '\\' && j + 1 < s.length && s[j + 1] == '|') {
                cur.append('|'); j += 2
            } else if (c == '|') {
                cells.add(cur.toString().trim()); cur.setLength(0); j++
            } else {
                cur.append(c); j++
            }
        }
        cells.add(cur.toString().trim())
        return cells
    }

    private fun cellOpen(tag: String, aligns: List<String>, idx: Int, zebra: Boolean = false): String {
        val a = aligns.getOrNull(idx) ?: "left"
        val attrs = buildString {
            if (a != "left") append(" align=\"").append(a).append('"')
            if (zebra) append(" class=\"alt\"")
        }
        return "<$tag$attrs>"
    }

    private val REGEX_OL = Regex("^\\d{1,3}\\. ")
    private val REGEX_HR = Regex("^-{3,}$")
    private val REGEX_BOLD = Regex("\\*\\*(.+?)\\*\\*")
    private val REGEX_ITALIC = Regex("(?<![\\w*])\\*([^*\n]+)\\*(?![\\w*])")
    private val REGEX_STRIKE = Regex("~~(.+?)~~")
    private val REGEX_LINK = Regex("\\[([^]]+)]\\(([^)\\s]+)\\)")

    private fun inline(src: String): String {
        val sb = StringBuilder()
        // 行内代码段先切出来，代码内容不再参与其他格式转换
        val parts = escape(src).split('`')
        parts.forEachIndexed { idx, part ->
            if (idx % 2 == 1) {
                // 内容整体形如"文件[:行号]"时升级为 IDE 内跳转链接（如 `Foo.java:141`）
                val refHref = FileRefs.hrefForCodeSpan(FileRefs.unescapeHtml(part))
                if (refHref != null) {
                    sb.append("<a href=\"").append(refHref).append("\"><code>").append(part).append("</code></a>")
                } else {
                    sb.append("<code>").append(part).append("</code>")
                }
            } else {
                var s = REGEX_LINK.replace(part) { m ->
                    val url = m.groupValues[2]
                    val local = FileRefs.hrefForCodeSpan(FileRefs.unescapeHtml(url))
                    when {
                        url.startsWith("http://", true) || url.startsWith("https://", true) ->
                            "<a href=\"" + url + "\">" + m.groupValues[1] + "</a>"
                        local != null ->
                            "<a href=\"" + local + "\">" + m.groupValues[1] + "</a>"
                        else -> m.groupValues[1] + " (" + url + ")"
                    }
                }
                // 纯文本里的路径引用（src/Foo.kt:42）也链成 IDE 内跳转；已有 <a> 锚点保护不重复处理
                s = FileRefs.linkifyPlainText(s)
                s = REGEX_BOLD.replace(s) { m -> "<b>${m.groupValues[1]}</b>" }
                s = REGEX_ITALIC.replace(s) { m -> "<i>${m.groupValues[1]}</i>" }
                s = REGEX_STRIKE.replace(s) { m -> "<span style=\"text-decoration:line-through\">${m.groupValues[1]}</span>" }
                sb.append(s)
            }
        }
        return sb.toString()
    }

    /** HTML 转义（着色器构造 span 时也复用）。 */
    internal fun escape(s: String): String = s
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

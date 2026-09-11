package zcode.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Markdown → Swing HTML 的纯逻辑验证。重点覆盖代码块围栏的游标推进（曾丢闭栏后一行）。 */
class MarkdownTest {

    @Test
    fun `text right after closing fence is not swallowed`() {
        val html = Markdown.toHtml("```kotlin\nval a = 1\n```\n下一行文本")
        assertTrue(html.contains("<pre>val a = 1</pre>"), "代码块内容应完整：$html")
        assertTrue(html.contains("下一行文本"), "闭栏后的第一行不能被吞掉：$html")
    }

    @Test
    fun `consecutive fenced blocks both render`() {
        val html = Markdown.toHtml("```\na\n```\n```\nb\n```")
        assertEquals(2, Regex("<pre>").findAll(html).count(), "两个代码块都应渲染：$html")
        assertTrue(html.contains("<pre>a</pre>") && html.contains("<pre>b</pre>"), html)
    }

    @Test
    fun `unclosed fence renders rest as code`() {
        val html = Markdown.toHtml("```\ncode")
        assertTrue(html.contains("<pre>code</pre>"), html)
    }

    @Test
    fun `inline code content is not formatted further`() {
        val html = Markdown.toHtml("see `**bold**` here")
        assertTrue(html.contains("<code>**bold**</code>"), html)
    }

    @Test
    fun `html entities are escaped`() {
        val html = Markdown.toHtml("a<b>&c")
        assertTrue(html.contains("&lt;b&gt;&amp;c"), html)
    }

    @Test
    fun `heading and list render`() {
        val html = Markdown.toHtml("# Title\n- item")
        assertTrue(html.contains("<h3>Title</h3>"), html)
        assertTrue(html.contains("<li>item</li>"), html)
    }

    @Test
    fun `safe link protocols render as anchor`() {
        val html = Markdown.toHtml("[text](https://a.b/c)")
        assertTrue(html.contains("<a href=\"https://a.b/c\">text</a>"), html)
    }

    @Test
    fun `unsafe link protocol degrades to plain text`() {
        val html = Markdown.toHtml("[x](javascript:alert(1))")
        assertFalse(html.contains("<a href=\"javascript"), "javascript: 链接不允许成为可点击锚点：$html")
        assertTrue(html.contains("javascript:alert(1)"), "URL 应以纯文本保留：$html")
    }

    @Test
    fun `file reference code span becomes clickable zcodefile anchor`() {
        val html = Markdown.toHtml("问题出在 `EvaluationReminderHandler.java:141` 这里")
        assertTrue(html.contains("<a href=\"zcodefile:"), "文件引用应变成 IDE 内跳转锚点：$html")
        assertTrue(html.contains("<code>EvaluationReminderHandler.java:141</code>"), "行内代码样式保留：$html")
    }

    @Test
    fun `non file code span stays plain code`() {
        val html = Markdown.toHtml("运行 `npm install` 即可")
        assertFalse(html.contains("zcodefile:"), "普通行内代码不应变链接：$html")
        assertTrue(html.contains("<code>npm install</code>"), html)
    }

    @Test
    fun `plain text path with line becomes anchor`() {
        val html = Markdown.toHtml("在 src/main/App.kt:42 处断点")
        assertTrue(html.contains("<a href=\"zcodefile:"), html)
        assertTrue(html.contains(">src/main/App.kt:42</a>"), html)
    }

    @Test
    fun `markdown url is not double linkified`() {
        val html = Markdown.toHtml("[repo](https://a.b/src/App.kt)")
        assertEquals(1, Regex("<a ").findAll(html).count(), "https 链接保持原样，不被文件引用二次包裹：$html")
        assertTrue(html.contains("<a href=\"https://a.b/src/App.kt\">repo</a>"), html)
    }

    // ------------------------------------------------------------------ 表格

    @Test
    fun `gfm table renders as html table`() {
        val html = Markdown.toHtml("| 检查项 | 说明 |\n|---|---|\n| 空指针 | 已覆盖 |\n| 并发 | 待补 |")
        assertTrue(html.contains("<table"), html)
        assertTrue(html.contains("<th>检查项</th>"), html)
        assertTrue(html.contains("<td>已覆盖</td>"), html)
        assertFalse(html.contains("|---|"), "分隔行不应以源码露出：$html")
    }

    @Test
    fun `table alignment comes from delimiter colons`() {
        val html = Markdown.toHtml("| a | b | c |\n|:--|--:|:-:|\n| 1 | 2 | 3 |")
        assertTrue(html.contains("<td align=\"right\">2</td>"), html)
        assertTrue(html.contains("<td align=\"center\">3</td>"), html)
        assertTrue(html.contains("<td>1</td>"), "left 是缺省值不加属性：$html")
        assertTrue(html.contains("<th align=\"right\">b</th>"), "表头也带对齐：$html")
    }

    @Test
    fun `table data rows alternate zebra class and cells have no gap`() {
        val html = Markdown.toHtml("| a |\n|---|\n| r0 |\n| r1 |\n| r2 |\n| r3 |")
        assertTrue(html.contains("<table width=\"100%\" cellspacing=\"0\">"), "cellspacing=0 保证横线连贯：$html")
        assertTrue(html.contains("<td>r0</td>"), "第 0 行不斑马：$html")
        assertTrue(html.contains("<td class=\"alt\">r1</td>"), html)
        assertTrue(html.contains("<td>r2</td>"), html)
        assertTrue(html.contains("<td class=\"alt\">r3</td>"), html)
        assertFalse(html.contains("<th class=\"alt\""), "表头不斑马：$html")
    }

    @Test
    fun `zebra class composes with alignment attribute`() {
        val html = Markdown.toHtml("| a | b |\n|---|--:|\n| x | 1 |\n| y | 2 |")
        assertTrue(html.contains("<td align=\"right\" class=\"alt\">2</td>"), html)
    }

    @Test
    fun `pipe line without delimiter row is not a table`() {
        val html = Markdown.toHtml("a | b\nc | d")
        assertFalse(html.contains("<table"), html)
        assertTrue(html.contains("a | b"), "普通含管道文本原样保留：$html")
    }

    @Test
    fun `table cell supports inline formatting and escaped pipe`() {
        val html = Markdown.toHtml("| 名称 | 说明 |\n|---|---|\n| **加粗** | a \\| b |")
        assertTrue(html.contains("<td><b>加粗</b></td>"), html)
        assertTrue(html.contains("a | b"), "转义管道还原为字面：$html")
    }

    @Test
    fun `table ends at blank line and following text renders`() {
        val html = Markdown.toHtml("| a |\n|---|\n| 1 |\n\n结论在表格后")
        assertTrue(html.contains("</table>"), html)
        assertTrue(html.contains("结论在表格后"), html)
        assertEquals(1, Regex("<table").findAll(html).count(), "空行后不再并入表格：$html")
    }

    @Test
    fun `pipes inside fenced code block never trigger table`() {
        val html = Markdown.toHtml("```\n| a | b |\n|---|---|\n```")
        assertEquals(0, Regex("<table").findAll(html).count(), html)
        assertTrue(html.contains("| a | b |"), html)
    }

    // ------------------------------------------------------------------ 高亮回调与小语法

    @Test
    fun `highlight callback html is adopted for fenced block`() {
        val html = Markdown.toHtml("```kotlin\nval a = 1\n```") { lang, code ->
            if (lang == "kotlin" && code == "val a = 1") "<span style=\"color:#ff0000\">val</span> a = 1" else null
        }
        assertTrue(html.contains("<pre><span style=\"color:#ff0000\">val</span> a = 1</pre>"), html)
    }

    @Test
    fun `highlight callback null falls back to escaped plain text`() {
        val html = Markdown.toHtml("```java\nint < x\n```") { _, _ -> null }
        assertTrue(html.contains("<pre>int &lt; x</pre>"), html)
    }

    @Test
    fun `strikethrough renders as line-through span`() {
        val html = Markdown.toHtml("~~旧方案~~ 已废弃")
        assertTrue(html.contains("text-decoration:line-through"), html)
        assertTrue(html.contains(">旧方案<"), html)
    }

    @Test
    fun `task list renders checkbox glyphs`() {
        val html = Markdown.toHtml("- [x] 完成\n- [ ] 待办")
        assertTrue(html.contains("<li>☑ 完成</li>"), html)
        assertTrue(html.contains("<li>☐ 待办</li>"), html)
    }
}

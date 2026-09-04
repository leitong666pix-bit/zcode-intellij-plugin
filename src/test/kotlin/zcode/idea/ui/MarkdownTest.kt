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
}

package zcode.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * FileRefs 纯逻辑验证：行内代码/纯文本两类文件引用的识别边界、href 编码与解析往返。
 */
class FileRefsTest {

    // ---------------------------------------------------------------- 行内代码段

    @Test
    fun `code span with file and line becomes href`() {
        val href = FileRefs.hrefForCodeSpan("Foo.java:141")
        assertNotNull(href)
        val (ref, line) = FileRefs.parseHref(href!!)!!
        assertEquals("Foo.java", ref)
        assertEquals(141, line)
    }

    @Test
    fun `code span accepts relative absolute and drive paths`() {
        for (span in listOf("src/main/Foo.kt:42", "src\\main\\Foo.kt", "D:\\x\\Foo.kt:12", "C:/a b/c.txt", "index.html5")) {
            assertNotNull(FileRefs.hrefForCodeSpan(span), "应识别: $span")
        }
        // 行号拆分正确性
        val (ref, line) = FileRefs.parseHref(FileRefs.hrefForCodeSpan("src/main/Foo.kt:42")!!)!!
        assertEquals("src/main/Foo.kt", ref)
        assertEquals(42, line)
        val noLine = FileRefs.parseHref(FileRefs.hrefForCodeSpan("src/main/Foo.kt")!!)!!
        assertNull(noLine.second)
        assertEquals("src/main/Foo.kt", noLine.first)
    }

    @Test
    fun `code span rejects urls numbers and plain words`() {
        assertNull(FileRefs.hrefForCodeSpan("https://a.b/x.js"))
        assertNull(FileRefs.hrefForCodeSpan("http://host:8080"))
        assertNull(FileRefs.hrefForCodeSpan("3.14"))
        assertNull(FileRefs.hrefForCodeSpan("v1.2"))
        assertNull(FileRefs.hrefForCodeSpan("plain text"))
        assertNull(FileRefs.hrefForCodeSpan("Foo"))
    }

    // ---------------------------------------------------------------- 纯文本段

    @Test
    fun `plain text path with separator is linkified`() {
        val html = FileRefs.linkifyPlainText("见 src/App.kt:10 这里")
        assertTrue(html.contains("<a href=\"zcodefile:"), html)
        assertTrue(html.contains(">src/App.kt:10</a>"), html)
    }

    @Test
    fun `bare filename in plain text is not linkified`() {
        // Bare filenames without a location stay prose; explicit file:line references are links.
        assertFalse(FileRefs.linkifyPlainText("Foo.java 很可疑").contains("<a "))
        assertTrue(FileRefs.linkifyPlainText("Foo.java:141 很可疑").contains("<a "))
    }

    @Test
    fun `url middle segment is not linkified`() {
        // https://a.b/x.js:1 —— x.js 前紧邻 '/'，被前瞻排除
        assertFalse(FileRefs.linkifyPlainText("看 https://a.b/x.js:1 页面").contains("<a "))
    }

    @Test
    fun `existing anchors are preserved untouched`() {
        val s = """前 <a href="https://a.b/c/d.js">text</a> 后"""
        assertEquals(s, FileRefs.linkifyPlainText(s))
    }

    @Test
    fun `drive-absolute path in plain text is linkified`() {
        val html = FileRefs.linkifyPlainText("打开 D:\\proj\\src\\Main.kt:5 看看")
        assertTrue(html.contains("zcodefile:"), html)
        val href = Regex("href=\"(zcodefile:[^\"]+)\"").find(html)!!.groupValues[1]
        assertEquals("D:\\proj\\src\\Main.kt" to 5, FileRefs.parseHref(href))
    }

    @Test
    fun `parse href round trip with special chars`() {
        val href = FileRefs.hrefForCodeSpan("my file.txt:7")
        assertNotNull(href)
        assertEquals("my file.txt" to 7, FileRefs.parseHref(href!!))
    }

    @Test
    fun `parse href rejects other schemes`() {
        assertNull(FileRefs.parseHref("https://a.b/c"))
    }
    @Test fun lineRangeRoundTripsAndRemainsOneLink() {
        val text = "EvaluationReminderHandler.java:336-347"
        val href = FileRefs.hrefForCodeSpan(text)!!
        assertEquals(FileReference("EvaluationReminderHandler.java", 336, 347), FileRefs.parseTarget(href))
        val html = FileRefs.linkifyPlainText("见 " + text + "，这里")
        assertTrue(html.contains(">" + text + "</a>"), html)
        assertEquals(1, Regex("<a ").findAll(html).count())
    }

    @Test fun supportsGithubAnchorsAndEnDashRanges() {
        for (text in listOf("src/Main.kt#L12-L20", "src/Main.kt#L12-20", "src/Main.kt:12–20")) {
            assertEquals(FileReference("src/Main.kt", 12, 20), FileRefs.parseTarget(FileRefs.hrefForCodeSpan(text)!!))
        }
    }

    @Test fun rejectsInvalidLocationsWithoutCreatingPartialLinks() {
        for (text in listOf("src/Main.kt:0", "src/Main.kt:12-3", "src/Main.kt:999999999999", "src/Main.kt#L0")) {
            assertNull(FileRefs.hrefForCodeSpan(text), text)
            assertFalse(FileRefs.linkifyPlainText(text).contains("<a "), text)
        }
    }

    @Test fun supportsUnicodeSpacesAndAmpersandsInCodeReferences() {
        val ref = FileReference("模块/目录 a/配置&说明.kt", 5, 9)
        assertEquals(ref, FileRefs.parseTarget(FileRefs.hrefForCodeSpan(ref.path + ":5-9")!!))
    }

    @Test fun localMarkdownLinksAndScreenshotReferenceRenderAsIdeLinks() {
        val tick = 96.toChar()
        val code = tick + "EvaluationReminderHandler.java:336-347" + tick
        assertTrue(Markdown.toHtml(code).contains("zcodefile:"))
        val html = Markdown.toHtml("[查看代码](src/Main.kt#L5-L9)")
        assertTrue(html.contains("zcodefile:"), html)
        assertTrue(html.contains(">查看代码</a>"), html)
        val amp = Markdown.toHtml(tick + "src/A&B.kt:2-3" + tick)
        val href = Regex("href=\"([^\"]+)\"").find(amp)!!.groupValues[1]
        assertEquals(FileReference("src/A&B.kt", 2, 3), FileRefs.parseTarget(href))
    }

    @Test fun existingAnchorsWithTitlesAndUrlsAreNotRelinked() {
        val html = """<a href="https://host/src/Main.kt" title="Main.kt:1-2">src/Main.kt:1-2</a>"""
        assertEquals(html, FileRefs.linkifyPlainText(html))
        assertFalse(FileRefs.linkifyPlainText("https://host/src/Main.kt:1-2").contains("<a "))
    }

    @Test fun symbolCandidatesExcludeLiteralValues() {
        for (name in listOf("HealthProperties", "EvaluationSmsLinkProperties", "scheduleReminderPatientWhitelistFilter", "execute()")) {
            assertNotNull(SymbolRefs.query(name), name)
        }
        for (text in listOf("2026-09-10", "evaluation-reminder.skip-dates", "return", "npm install")) {
            assertNull(SymbolRefs.query(text), text)
        }
    }

    @Test fun onlyResolvedSymbolsBecomeLinksAndExistingLinksAreProtected() {
        val html = "<code>HealthProperties</code> <code>execute()</code> <code>2026-09-10</code>"
        val result = SymbolRefs.decorate(html, mapOf("HealthProperties" to listOf("HealthProperties — src/HealthProperties.java")))
        assertTrue(result.contains("zcodesymbol:HealthProperties"))
        assertTrue(result.contains("<code>execute()</code>"))
        assertFalse(result.contains("zcodesymbol:execute"))
        assertEquals(result, SymbolRefs.decorate(result, mapOf("HealthProperties" to listOf("duplicate"))))
        assertTrue(result.contains("src/HealthProperties.java"))
    }

    @Test fun ambiguousSymbolsAdvertiseChoiceAndEncodeQueries() {
        val text = "Owner.execute()"
        val html = SymbolRefs.decorate("<code>" + text + "</code>", mapOf(text to listOf("a", "b")))
        assertTrue(html.contains("2 个候选"), html)
        assertEquals(text, SymbolRefs.parseHref(SymbolRefs.href(text)))
        assertEquals(listOf(text), SymbolRefs.candidatesIn("<code>" + text + "</code>"))
    }

}

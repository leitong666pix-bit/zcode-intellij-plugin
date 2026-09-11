package zcode.idea.context

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** SelectionContext 纯字符串部分的验证（拆上下文块 / 选区块生成与截断）。 */
class SelectionContextTest {

    @Test
    fun `split with marker separates prompt and context`() {
        val (prompt, ctx) = SelectionContext.splitContext(
            "我的问题\n\n${SelectionContext.CONTEXT_MARKER}\n当前活动文件: A.kt"
        )
        assertEquals("我的问题", prompt)
        assertNotNull(ctx)
        assertTrue(ctx!!.contains("A.kt"))
    }

    @Test
    fun `split without marker returns null context`() {
        val (prompt, ctx) = SelectionContext.splitContext("普通消息")
        assertEquals("普通消息", prompt)
        assertNull(ctx)
    }

    // ------------------------------------------------------------------ 内嵌引用记号

    private fun refInfo(path: String, sel: String, start: Int, end: Int) = SelectionContext.Info(
        activeFile = null,
        relativePath = path,
        selectionText = sel,
        startLine = start,
        endLine = end,
        openFiles = emptyList(),
    )

    @Test
    fun `ref token uses relative path and line range`() {
        assertEquals("@src/main/A.kt:3-5", SelectionContext.refTokenOf(refInfo("src/main/A.kt", "val a = 1", 3, 5)))
    }

    @Test
    fun sameNamedFilesKeepDistinctSelections() {
        val first = refInfo("module-a/index.ts", "first module", 1, 10)
        val second = refInfo("module-b/index.ts", "second module", 1, 10)
        val firstToken = SelectionContext.refTokenOf(first)
        val secondToken = SelectionContext.refTokenOf(second)
        org.junit.jupiter.api.Assertions.assertNotEquals(firstToken, secondToken)
        val output = SelectionContext.substituteRefs(
            firstToken + " versus " + secondToken,
            linkedMapOf(firstToken to first, secondToken to second), 8000,
        )!!
        assertTrue(output.contains("first module"))
        assertTrue(output.contains("second module"))
    }

    @Test
    fun `inline quote carries path lines and fenced code without marker`() {
        val quote = SelectionContext.buildInlineQuote(refInfo("src/main/A.kt", "val a = 1", 3, 5), 8000)
        assertTrue(quote.contains("src/main/A.kt"), quote)
        assertTrue(quote.contains("第3-5行"), quote)
        assertTrue(quote.contains("```"), quote)
        assertTrue(quote.contains("val a = 1"), quote)
        org.junit.jupiter.api.Assertions.assertFalse(quote.contains(SelectionContext.CONTEXT_MARKER))
    }

    @Test
    fun `substitute refs replaces registered tokens only`() {
        val refs = mapOf(
            "@A.kt:3-5" to refInfo("src/A.kt", "val a = 1", 3, 5),
            "@B.kt:10-20" to refInfo("src/B.kt", "fun b(){}", 10, 20),
        )
        val out = SelectionContext.substituteRefs(
            "对比 @A.kt:3-5 和 @B.kt:10-20，还有我手打的 @C.kt:1-2", refs, 8000,
        )!!
        assertTrue(out.contains("val a = 1"), out)
        assertTrue(out.contains("fun b(){}"), out)
        // 未登记的记号原样保留
        assertTrue(out.contains("@C.kt:1-2"), out)
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("@A.kt:3-5"), out)
    }

    @Test
    fun `substitute refs returns null when nothing registered or matched`() {
        assertNull(SelectionContext.substituteRefs("普通消息 @C.kt:1-2", emptyMap(), 8000))
        assertNull(SelectionContext.substituteRefs("没有记号", mapOf("@A.kt:3-5" to refInfo("A.kt", "x", 3, 5)), 8000))
    }

    @Test
    fun `substitute refs handles prefix overlapping tokens`() {
        // @A.kt:3-50 是 @A.kt:3-5 的超集前缀：长记号必须先替换，避免被短记号截断
        val refs = mapOf(
            "@A.kt:3-5" to refInfo("A.kt", "short", 3, 5),
            "@A.kt:3-50" to refInfo("A.kt", "long", 3, 50),
        )
        val out = SelectionContext.substituteRefs("两处 @A.kt:3-5 与 @A.kt:3-50", refs, 8000)!!
        assertTrue(out.contains("short"), out)
        assertTrue(out.contains("long"), out)
        org.junit.jupiter.api.Assertions.assertFalse(out.contains("@A.kt:3-5"), out)
    }

    @Test
    fun `inline quote truncates over limit`() {
        val quote = SelectionContext.buildInlineQuote(refInfo("A.kt", "x".repeat(100), 1, 1), 50)
        assertTrue(quote.contains("已截断"), quote)
    }

    @Test
    fun `marker-only text becomes placeholder prompt`() {
        val (prompt, _) = SelectionContext.splitContext(SelectionContext.CONTEXT_MARKER + "\n内容")
        assertEquals("（仅发送了 IDE 上下文）", prompt)
    }

    @Test
    fun `selection block contains path lines and fenced code`() {
        val info = SelectionContext.Info(
            activeFile = null,
            relativePath = "src/main/A.kt",
            selectionText = "val a = 1",
            startLine = 3,
            endLine = 5,
            openFiles = emptyList(),
        )
        val block = SelectionContext.buildSelectionBlock(info, 8000)!!
        assertTrue(block.contains(SelectionContext.CONTEXT_MARKER))
        assertTrue(block.contains("src/main/A.kt"))
        assertTrue(block.contains("第 3-5 行"))
        assertTrue(block.contains("```"))
        assertTrue(block.contains("val a = 1"))
    }

    @Test
    fun `selection block truncates over limit`() {
        val info = SelectionContext.Info(
            activeFile = null,
            relativePath = "A.kt",
            selectionText = "x".repeat(100),
            startLine = 1,
            endLine = 1,
            openFiles = emptyList(),
        )
        val block = SelectionContext.buildSelectionBlock(info, 10)!!
        assertTrue(block.contains("已截断"), block)
        assertTrue(block.contains("x".repeat(10)), "截断前的前 10 字符应保留")
    }

    @Test
    fun `selection block null without selection`() {
        val info = SelectionContext.Info(null, null, null, null, null, emptyList())
        assertNull(SelectionContext.buildSelectionBlock(info, 8000))
    }
}

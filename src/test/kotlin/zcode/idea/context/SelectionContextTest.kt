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

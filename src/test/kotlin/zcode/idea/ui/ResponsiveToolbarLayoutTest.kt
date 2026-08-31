package zcode.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * ResponsiveToolbarLayout 的几何验证。所有组件显式设 preferredSize（不依赖字体/显示器，
 * headless 下结果确定）。两条核心不变式：
 * 1. 任意宽度下每个组件都完整落在面板可见范围内（旧版 BorderLayout+EAST 窄面板下按钮被挤出可视区）；
 * 2. 流式换行无死区——每个非末行都塞满（下一个组件确实放不进才换行），不会出现
 *    "一行大片留白、另一行挤爆"的两行分组布局问题。
 */
class ResponsiveToolbarLayoutTest {

    private fun button(text: String, w: Int): JButton =
        JButton(text).apply { preferredSize = Dimension(w, 26) }

    private fun label(text: String, w: Int): JLabel =
        JLabel(text).apply { preferredSize = Dimension(w, 18) }

    private fun combo(w: Int): JComboBox<String> =
        JComboBox(arrayOf("x")).apply { preferredSize = Dimension(w, 26) }

    /** 与 ChatPanel.buildToolbar 同构：8 个直接子组件，前 3 个为按钮组。 */
    private fun buildToolbar(): JPanel {
        val items = listOf(
            button("新会话", 62),
            button("恢复", 50),
            button("变更文件 (12)", 102),
            label("● 就绪", 52),
            label("上下文 14k/1.0M", 106),
            combo(128),
            combo(84),
            combo(104),
        )
        return JPanel(ResponsiveToolbarLayout(leftCount = 3)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4, 10, 5, 10),
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            )
            items.forEach { add(it) }
        }
    }

    /** 按 width 布局（多轮收敛：preferred 高度依赖当前宽度），返回面板。 */
    private fun layoutAt(width: Int): JPanel = buildToolbar().apply {
        repeat(3) {
            setSize(width, preferredSize.height.coerceAtLeast(1))
            doLayout()
        }
    }

    /** 各组件的行号：按 y 中心聚类（同一行组件被垂直居中，中心相同），自上而下编号。 */
    private fun rowOf(panel: JPanel): List<Int> {
        val centers = (0 until panel.componentCount).map { panel.getComponent(it) }
            .map { it.y + it.height / 2 }
        val sorted = centers.distinct().sorted()
        return centers.map { sorted.indexOf(it) }
    }

    private fun allInside(panel: JPanel): Boolean {
        val ins = panel.insets
        for (i in 0 until panel.componentCount) {
            val c = panel.getComponent(i)
            if (c.x < ins.left || c.y < ins.top ||
                c.x + c.width > panel.width - ins.right ||
                c.y + c.height > panel.height - ins.bottom
            ) return false
        }
        return true
    }

    /** 反死区：每个非末行的行尾 + 间距 + 下一行首组件宽，必须超出可用宽度。 */
    private fun rowsFullyPacked(panel: JPanel): Boolean {
        val rows = rowOf(panel)
        val avail = panel.width - panel.insets.right
        for (r in 0 until rows.max()) {
            val rowEnd = (0 until panel.componentCount)
                .filter { rows[it] == r }
                .maxOf { panel.getComponent(it).x + panel.getComponent(it).width }
            val nextFirst = (0 until panel.componentCount).first { rows[it] == r + 1 }
            val nextW = panel.getComponent(nextFirst).width
            if (rowEnd + 8 + nextW <= avail) return false
        }
        return true
    }

    @Test
    fun `wide toolbar stays single justified row`() {
        val panel = layoutAt(900)
        assertEquals(List(8) { 0 }, rowOf(panel), "900px 应单行")
        assertEquals(panel.insets.left, panel.getComponent(0).x, "按钮组应贴左边距")
        val last = panel.getComponent(7)
        assertEquals(panel.width - panel.insets.right, last.x + last.width, "下拉组应贴右边距")
        assertTrue(allInside(panel))
    }

    @Test
    fun `medium toolbar flows labels into button row`() {
        val panel = layoutAt(500)
        val rows = rowOf(panel)
        // 核心诉求：折行时不再"按钮一行/下拉一行"，而是整条流换行铺满——
        // 状态与上下文标签跟按钮同处第一行，模型下拉从第二行开始
        assertEquals(rows[0], rows[4], "上下文标签应与按钮同在第一行")
        assertEquals(rows[5], rows[0] + 1, "模型下拉应在下一行")
        assertTrue(rowsFullyPacked(panel), "每个非末行都应塞满（无大片留白）")
        assertTrue(allInside(panel))
    }

    @Test
    fun `very narrow toolbar keeps wrapping and never clips`() {
        val panel = layoutAt(240)
        assertTrue(rowOf(panel).max() >= 2, "240px 应折 3 行以上")
        assertTrue(rowsFullyPacked(panel))
        assertTrue(allInside(panel), "极窄下面板内也不允许组件被裁掉")
        assertTrue(panel.height > 100, "多行折行后面板高度应相应撑开，实际 ${panel.height}")
    }

    @Test
    fun `height grows monotonically as width shrinks`() {
        fun prefHeightAt(width: Int): Int = buildToolbar().let { p ->
            p.setSize(width, 1); p.doLayout(); p.preferredSize.height
        }
        val hWide = prefHeightAt(900)
        val hMedium = prefHeightAt(500)
        val hNarrow = prefHeightAt(240)
        assertTrue(hWide < hMedium && hMedium < hNarrow, "高度应随宽度收缩递增：$hWide < $hMedium < $hNarrow")
    }
}

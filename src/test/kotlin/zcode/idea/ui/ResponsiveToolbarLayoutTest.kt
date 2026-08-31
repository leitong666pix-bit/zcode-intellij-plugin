package zcode.idea.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * ResponsiveToolbarLayout 的几何验证。所有组件显式设 preferredSize（不依赖字体/显示器，
 * headless 下结果确定）。核心不变式：任意宽度下，工具栏里的每个按钮/标签/下拉都完整落在
 * 面板可见范围内——旧版 BorderLayout+EAST 在窄面板下会把左组按钮挤到可视区外（“消失”）。
 */
class ResponsiveToolbarLayoutTest {

    private fun button(text: String, w: Int): JButton =
        JButton(text).apply { preferredSize = Dimension(w, 26) }

    private fun label(text: String, w: Int): JLabel =
        JLabel(text).apply { preferredSize = Dimension(w, 18) }

    private fun combo(w: Int): JComboBox<String> =
        JComboBox(arrayOf("x")).apply { preferredSize = Dimension(w, 26) }

    /** 与 ChatPanel.buildToolbar 同构的工具栏：component(0)=左组（按钮），component(1)=右组（标签+下拉）。 */
    private fun buildToolbar(): JPanel {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 2, 4)).apply {
            add(button("新会话", 62))
            add(button("恢复", 50))
            add(button("变更文件 (12)", 102))
        }
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 4)).apply {
            add(label("● 就绪", 52))
            add(label("上下文 14k/1.0M", 106))
            add(combo(128))
            add(combo(84))
            add(combo(104))
        }
        return JPanel(ResponsiveToolbarLayout(left, right)).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(4, 10, 5, 10),
                BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
            )
            add(left)
            add(right)
        }
    }

    /** 按 width 布局（多轮收敛：preferred 高度依赖当前宽度），返回 (面板, 左组, 右组)。
     *  真实 Swing 的 validate 树会级联布局子容器，这里手动补上 doLayout 级联。 */
    private fun layoutAt(width: Int): Triple<JPanel, JPanel, JPanel> {
        val panel = buildToolbar()
        repeat(3) {
            panel.setSize(width, panel.preferredSize.height.coerceAtLeast(1))
            panel.doLayout()
            for (i in 0 until panel.componentCount) panel.getComponent(i).doLayout()
        }
        return Triple(panel, panel.getComponent(0) as JPanel, panel.getComponent(1) as JPanel)
    }

    /** 组内行数：FlowLayout 垂直居中，同一行组件的 (y+高度/2) 相同。 */
    private fun rows(group: JComponent): Int =
        (0 until group.componentCount).map { group.getComponent(it) }
            .map { it.y + it.height / 2 }.distinct().size

    /** 全部子组件（换算到面板坐标系）是否完整落在面板范围内。 */
    private fun allInside(panel: JPanel): Boolean {
        val ins = panel.insets
        for (gi in 0 until panel.componentCount) {
            val g = panel.getComponent(gi) as java.awt.Container
            for (ci in 0 until g.componentCount) {
                val c = g.getComponent(ci)
                val r = Rectangle(c.bounds).apply { x += g.x; y += g.y }
                if (r.x < ins.left || r.y < ins.top ||
                    r.x + r.width > panel.width - ins.right ||
                    r.y + r.height > panel.height - ins.bottom
                ) return false
            }
        }
        return true
    }

    @Test
    fun `wide toolbar stays single row`() {
        val (panel, left, right) = layoutAt(900)
        assertEquals(1, rows(left))
        assertEquals(1, rows(right))
        assertEquals(left.y, right.y, "单行模式下两组应在同一行")
        assertEquals(panel.insets.left, left.x, "左组应贴左边距")
        assertEquals(panel.width - panel.insets.right, right.x + right.width, "右组应贴右边距")
        assertTrue(allInside(panel))
    }

    @Test
    fun `narrow toolbar wraps into two rows without hiding buttons`() {
        val (panel, left, right) = layoutAt(500)
        assertTrue(right.y > left.y, "窄面板应折成两行（右组在下一行）")
        assertEquals(1, rows(left))
        assertTrue(allInside(panel), "所有按钮/下拉必须可见（旧布局此宽度下左组被压为 0 宽）")
    }

    @Test
    fun `very narrow toolbar keeps wrapping and never clips`() {
        val (panel, _, right) = layoutAt(240)
        assertTrue(rows(right) >= 2, "240px 放不下右组单行（约 500px），应折为多行")
        assertTrue(allInside(panel), "极窄下面板内也不允许组件被裁掉")
        assertTrue(panel.height > 60, "折行后面板高度应明显大于单行，实际 ${panel.height}")
    }

    @Test
    fun `stacked mode grows height as width shrinks`() {
        fun prefHeightAt(width: Int): Int = buildToolbar().let { p ->
            p.setSize(width, 1); p.doLayout(); p.preferredSize.height
        }
        val hNarrow = prefHeightAt(400)
        val hWide = prefHeightAt(900)
        assertTrue(hNarrow > hWide, "窄面板 Preferred 高度应更大（$hNarrow vs $hWide）")
    }
}

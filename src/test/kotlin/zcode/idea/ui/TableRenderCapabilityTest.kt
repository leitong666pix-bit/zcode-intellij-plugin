package zcode.idea.ui

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Dimension
import java.awt.image.BufferedImage
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit

/**
 * HTMLEditorKit 表格样式能力的真实渲染验证（纯 JDK Swing + 字面色，headless 可跑）。
 * 表格的 GitHub 风格样式依赖两个非显而易见的能力，若某天平台/JDK 行为变化导致失效，
 * 这里的像素断言会先红，避免"样式静默退化成无分隔线表格"：
 * 1. th/td 的单侧边框 border-bottom 确实绘制；
 * 2. class 选择器（td.alt）的 background-color 确实生效。
 * 边框故意用 6px 粗线、断言用像素计数，与字体渲染差异解耦。
 */
class TableRenderCapabilityTest {

    private fun paintAndCountPixels(): Map<Int, Int> {
        val pane = JEditorPane()
        pane.editorKit = HTMLEditorKit()
        (pane.editorKit as HTMLEditorKit).styleSheet.apply {
            addRule("body { margin: 0 }")
            addRule("th, td { padding: 20px; }")
            addRule("th { border-bottom: 6px solid #ff0000; }")
            addRule("td { border-bottom: 6px solid #0000ff; }")
            addRule("td.alt { background-color: #00ff00; }")
        }
        pane.text = """
            <html><body><table width="300" cellspacing="0">
            <tr><th>H</th></tr>
            <tr><td class="alt">aaa</td></tr>
            <tr><td>bbb</td></tr>
            </table></body></html>
        """.trimIndent()
        pane.size = Dimension(400, 800)
        val img = BufferedImage(400, 800, BufferedImage.TYPE_INT_ARGB)
        pane.paint(img.createGraphics())
        val counts = HashMap<Int, Int>()
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                val rgb = img.getRGB(x, y)
                counts[rgb] = (counts[rgb] ?: 0) + 1
            }
        }
        return counts
    }

    @Test
    fun `single side border-bottom is painted on th and td`() {
        val counts = paintAndCountPixels()
        val red = counts[Color(0xFF0000).rgb] ?: 0
        val blue = counts[Color(0x0000FF).rgb] ?: 0
        assertTrue(red >= 100, "th 的 border-bottom 未绘制（红像素 $red）：HTMLEditorKit 不支持单侧边框，需回退四边框方案")
        assertTrue(blue >= 100, "td 的 border-bottom 未绘制（蓝像素 $blue）")
    }

    @Test
    fun `class selector background is painted on td`() {
        val counts = paintAndCountPixels()
        val green = counts[Color(0x00FF00).rgb] ?: 0
        assertTrue(green >= 1000, "td.alt 的 background-color 未生效（绿像素 $green）：class 选择器不被支持，斑马纹需换 bgcolor 属性方案")
    }
}

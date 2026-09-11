package zcode.idea.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import javax.swing.JScrollBar

/**
 * 贴底跟踪的纯模型验证（裸 JScrollBar，无 UI）。
 * 核心不变量：内容增高（只动 maximum）不改变吸附态；用户滚离立刻停跟随；拉回底部恢复；拖动期间不跟随。
 */
class StickyBottomTrackerTest {

    private fun bar(maximum: Int = 1000, extent: Int = 300): JScrollBar =
        JScrollBar().apply { setValues(0, extent, 0, maximum) }

    @Test
    fun `content growth while pinned keeps following`() {
        val tracker = StickyBottomTracker(8)
        val bar = bar()
        tracker.attach(bar)
        bar.value = 700 // 贴底（700 + 300 == 1000）
        assertTrue(tracker.stuck, "程序贴底后应处于吸附态")

        bar.maximum = 5000 // 流式内容增高：maximum 动了、value 没动
        assertTrue(tracker.stuck, "内容增高不应被误判成用户上翻")

        bar.value = 4700 // 程序继续贴底
        assertTrue(tracker.stuck)
    }

    @Test
    fun `user scrolls up stops following and returning to bottom resumes`() {
        val tracker = StickyBottomTracker(8)
        val bar = bar()
        tracker.attach(bar)
        bar.value = 700
        assertTrue(tracker.stuck)

        bar.value = 646 // 用户滚轮上移一档（离底 54 > 容差 8）
        assertFalse(tracker.stuck, "用户上翻即停跟随")

        bar.maximum = 8000 // 停跟随后内容再怎么涨也不拽人
        assertFalse(tracker.stuck)

        bar.value = 7700 // 用户拉回底部
        assertTrue(tracker.stuck, "回到底部自动恢复跟随")
    }

    @Test
    fun `drag suspends following even within tolerance`() {
        val tracker = StickyBottomTracker(8)
        val bar = bar()
        tracker.attach(bar)
        bar.value = 700
        assertTrue(tracker.stuck)

        bar.model.valueIsAdjusting = true
        bar.value = 690 // 拖动中仅移动 10px（在容差内）
        assertFalse(tracker.stuck, "拖动未松手一律暂停吸附，不能跟用户抢滚动条")

        bar.value = 700 // 拖回底部后松手
        bar.model.valueIsAdjusting = false
        assertTrue(tracker.stuck, "松手时的首个事件即便值未变也要重判（拖回底部应恢复吸附）")
    }

    @Test
    fun `small bounce within tolerance still counts as following`() {
        val tracker = StickyBottomTracker(8)
        val bar = bar()
        tracker.attach(bar)
        bar.value = 699 // 距底 1px（< 容差）
        assertTrue(tracker.stuck)
    }
}

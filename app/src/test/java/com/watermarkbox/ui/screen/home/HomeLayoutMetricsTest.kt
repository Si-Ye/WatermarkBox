package com.watermarkbox.ui.screen.home

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.watermarkbox.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页布局推导（[homeMetrics]）的回归测试。
 *
 * ## 这组测试在守什么
 *
 * 曾经的 bug：小屏（360×640dp）上第三张入口卡片被压成一条扁条，难点。
 * 根因是卡片高度交给了父容器的剩余约束去决定。现在的契约是：
 *
 * 1. 卡片高度永远 ≥ 最小可点高度（竖排 88dp / 并排 140dp）；
 * 2. 除「空间本身就不够」的极端尺寸外，整页内容不得超出可用高度；
 * 3. 极矮屏要主动让位（藏副标题、藏页脚），而不是把卡片压扁。
 *
 * 这些断言全部是纯算术，不需要设备。
 */
class HomeLayoutMetricsTest {

    /** 内容总高 = 头部 + 卡片前留白 + 卡片区 + 页脚 + 收尾留白。 */
    private fun contentHeight(m: HomeMetrics): Dp {
        val header = m.gapAfterTopBar + m.logoSize + m.gapAfterLogo + HomeDims.TitleBlock +
            (if (m.showTagline) HomeDims.TaglineBlock else 0.dp)
        val footer = if (m.showFooter) m.footerGap + HomeDims.FooterText else 0.dp
        val cards = if (m.wide) m.cardHeight else m.cardHeight * 3 + Spacing.Item * 2
        return header + m.gapBeforeCards + cards + footer + m.bottomSpacer
    }

    @Test
    fun `所有主流尺寸的卡片都不低于最小点击高度`() {
        for ((w, h) in MAINSTREAM_SIZES) {
            val m = homeMetrics(width = w, height = h)
            val min = if (m.wide) HomeDims.CardWideMin else HomeDims.CardMin
            assertTrue(
                "尺寸 ${w.value}x${h.value}dp 的卡片高度 ${m.cardHeight.value}dp 低于下限 ${min.value}dp",
                m.cardHeight >= min
            )
        }
    }

    @Test
    fun `主流尺寸的整页内容不超出可用高度`() {
        for ((w, h) in MAINSTREAM_SIZES) {
            val m = homeMetrics(width = w, height = h)
            val content = contentHeight(m)
            // 卡片被夹到下限的尺寸属于「本来就放不下」，按设计由滚动兜底，不在此断言范围。
            val clamped = m.cardHeight == (if (m.wide) HomeDims.CardWideMin else HomeDims.CardMin)
            if (!clamped) {
                assertTrue(
                    "尺寸 ${w.value}x${h.value}dp 内容 ${content.value}dp 超出可用高度 ${h.value}dp",
                    content <= h
                )
            }
        }
    }

    @Test
    fun `小屏手机 360x640 三张卡片高度一致且可点`() {
        val m = homeMetrics(width = 360.dp, height = 640.dp)
        assertEquals(false, m.wide)
        assertTrue(m.cardHeight >= HomeDims.CardMin)
        // 640dp 高的小屏手机必须放得下整页，否则用户仍要滚动才能看到第三个入口。
        assertTrue(
            "内容 ${contentHeight(m).value}dp 超出 640dp",
            contentHeight(m) <= 640.dp
        )
    }

    @Test
    fun `平板与横屏走并排布局`() {
        for ((w, h) in listOf(720.dp to 1280.dp, 800.dp to 1280.dp, 960.dp to 1706.dp)) {
            assertTrue("${w.value}x${h.value}dp 应并排", homeMetrics(w, h).wide)
        }
        // 手机竖屏（360~411dp 宽）不能并排，否则每张卡片只有 100dp 宽。
        for ((w, h) in listOf(360.dp to 640.dp, 411.dp to 731.dp)) {
            assertEquals("${w.value}x${h.value}dp 不应并排", false, homeMetrics(w, h).wide)
        }
    }

    @Test
    fun `矮屏优先让位而不是压扁卡片`() {
        // 手机横屏 731x411dp：高度极紧，必须藏掉副标题与页脚，但卡片仍有可点高度。
        val landscape = homeMetrics(width = 731.dp, height = 411.dp)
        assertTrue(landscape.wide)
        assertEquals(false, landscape.showTagline)
        assertTrue(landscape.cardHeight >= HomeDims.CardWideMin)

        // 竖屏矮机 288x512dp：副标题可按高度保留，但卡片高度不得低于下限。
        val narrow = homeMetrics(width = 288.dp, height = 512.dp)
        assertTrue(narrow.cardHeight >= HomeDims.CardMin)
    }

    @Test
    fun `Logo 随可用高度单调不减`() {
        val heights = listOf(300.dp, 450.dp, 600.dp, 700.dp, 900.dp, 1200.dp)
        val logos = heights.map { homeMetrics(width = 411.dp, height = it).logoSize.value }
        assertEquals(
            "Logo 尺寸应随可用高度递增或持平，实际 $logos",
            logos.sorted(),
            logos
        )
    }

    private companion object {
        /**
         * 主流手机与平板尺寸（dp）。
         *
         * 覆盖：老小屏、主流手机、大屏手机、7 寸/10 寸平板竖屏、
         * 手机横屏、平板横屏。数值来自常见分辨率 ÷ 常见 density。
         */
        val MAINSTREAM_SIZES = listOf(
            320.dp to 480.dp,   // 4 寸老机 320x480
            360.dp to 640.dp,   // 5 寸 1080p@480dpi（MuMu 默认）
            360.dp to 780.dp,   // 全面屏小机
            411.dp to 731.dp,   // 主流手机 1080p@420dpi
            411.dp to 914.dp,   // 主流长屏手机
            600.dp to 960.dp,   // 7 寸平板竖屏
            720.dp to 1280.dp,  // 7 寸平板竖屏（高清）
            800.dp to 1280.dp,  // 10 寸平板竖屏
            960.dp to 1706.dp,  // 10 寸平板竖屏（大）
            731.dp to 411.dp,   // 手机横屏
            914.dp to 411.dp,   // 手机横屏（长边）
            960.dp to 600.dp,   // 7 寸平板横屏
            1280.dp to 800.dp   // 10 寸平板横屏
        )
    }
}

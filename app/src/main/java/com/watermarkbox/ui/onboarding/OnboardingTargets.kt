package com.watermarkbox.ui.onboarding

import androidx.annotation.StringRes
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.watermarkbox.R

/**
 * 引导可以**聚焦**的目标。
 *
 * ## 为什么用枚举而不是随便传个字符串
 *
 * 目标名字是「界面」与「引导脚本」之间的契约：
 * - 界面侧：给控件挂 `Modifier.onboardingTarget(OnboardingTarget.ENTRY_PICK_IMAGE)`
 * - 脚本侧：`SpotlightStep(target = OnboardingTarget.ENTRY_PICK_IMAGE, ...)`
 *
 * 用枚举后，**写错名字编译期就报**；字符串则要等到运行时
 * 「引导框指向了空气」才发现。
 */
enum class OnboardingTarget {
    /** 首页「选择图片」入口。 */
    ENTRY_PICK_IMAGE,

    /** 首页「批量处理」入口。 */
    ENTRY_BATCH,

    /** 首页「检测水印」入口。 */
    ENTRY_DETECT,

    /** 首页右上角「水印预设」书签。 */
    ENTRY_PRESETS,

    /** 首页右上角「设置」齿轮。 */
    ENTRY_SETTINGS,

    /** 预设管理页右上角的「⋮」菜单（导入 / 导出在里面）。 */
    PRESETS_OVERFLOW_MENU
}

/**
 * 一套引导，对应一个页面。
 *
 * ## 为什么要分「套」
 *
 * 一套引导只能在**同一个页面**里跑 —— 高亮孔要指向真实控件，
 * 而其它页面的控件此刻根本没组合出来，坐标无从获取。
 *
 * 所以每套引导各自独立记录「看没看过」：用户可能看过首页引导、
 * 但还没进过预设页，那时进预设页仍应看到引导。
 */
enum class OnboardingTour {
    /** 首页：三个入口 + 设置。 */
    HOME,

    /** 预设管理页：导入 / 导出入口。 */
    PRESETS
}

/** 气泡相对目标的位置。 */
enum class BubbleSide {
    TOP,
    BOTTOM,
    START,
    END,

    /**
     * 由引导层自动挑选 —— 取**目标上方空间够不够**来判断，
     * 不够就放下方。
     *
     * 首页那几个入口大多在屏幕中上部，自动判断能避免
     * 「气泡被状态栏切掉」。
     */
    AUTO
}

/** 高亮孔的形状。 */
sealed interface HoleShape {
    /** 圆角矩形（默认）。 */
    data class RoundedRect(
        val corner: Dp = 12.dp,
        /** 向外扩张的内边距：让孔比控件本身大一圈，看起来更透气。 */
        val padding: Dp = 6.dp
    ) : HoleShape
}

/**
 * 引导的一步。
 *
 * @param target       要聚焦的控件
 * @param titleRes     气泡标题
 * @param bodyRes      气泡正文
 * @param side         气泡相对目标的位置（默认自动）
 * @param shape        高亮孔形状
 * @param strokeColor  描边颜色（默认用主题色）
 * @param glow         是否绘制外发光（多层半透明描边，营造"打光"感）
 * @param allowTapHole 点击高亮区是否也算"下一步"
 */
data class SpotlightStep(
    val target: OnboardingTarget,
    @param:StringRes val titleRes: Int,
    @param:StringRes val bodyRes: Int,
    val side: BubbleSide = BubbleSide.AUTO,
    val shape: HoleShape = HoleShape.RoundedRect(),
    val glow: Boolean = true,
    val allowTapHole: Boolean = true
)

/**
 * 引导脚本：**只描述「讲什么、指哪里」，不含任何布局计算**。
 *
 * 位置计算全部交给 [OnboardingOverlay] —— 它才知道屏幕多大、
 * 目标在哪、气泡该往哪放。脚本保持纯数据，便于调整顺序与文案。
 */
object OnboardingSteps {

    /**
     * 首页的新手引导。
     *
     * ## 为什么是这 5 步
     *
     * 每一步都要用户「读 + 点」，步数越多跳过率越高 ——
     * 这里只覆盖**第一次打开最该知道的事**：
     * 三个入口各是干什么的 + 右上角两个图标（预设 / 设置）。
     *
     * 细节留在「设置 → 其他 → 使用教程」，两边不重复。
     */
    val HOME: List<SpotlightStep> = listOf(
        SpotlightStep(
            target = OnboardingTarget.ENTRY_PICK_IMAGE,
            titleRes = R.string.onboarding_p1_title,
            bodyRes = R.string.onboarding_p1_body,
            side = BubbleSide.AUTO
        ),
        SpotlightStep(
            target = OnboardingTarget.ENTRY_BATCH,
            titleRes = R.string.onboarding_p2_title,
            bodyRes = R.string.onboarding_p2_body,
            side = BubbleSide.AUTO
        ),
        SpotlightStep(
            target = OnboardingTarget.ENTRY_DETECT,
            titleRes = R.string.onboarding_p3_title,
            bodyRes = R.string.onboarding_p3_body,
            side = BubbleSide.AUTO
        ),
        SpotlightStep(
            target = OnboardingTarget.ENTRY_PRESETS,
            titleRes = R.string.onboarding_p4_title,
            bodyRes = R.string.onboarding_p4_body,
            side = BubbleSide.BOTTOM
        ),
        SpotlightStep(
            target = OnboardingTarget.ENTRY_SETTINGS,
            titleRes = R.string.onboarding_p5_title,
            bodyRes = R.string.onboarding_p5_body,
            side = BubbleSide.BOTTOM
        )
    )

    /**
     * 预设管理页的引导。
     *
     * ## 为什么只有 1 步
     *
     * 这个页面本身就一个职能（管理预设），值得专门指出的事情只有一件：
     * **导入 / 导出藏在右上角「⋮」里** —— 不点开根本发现不了。
     *
     * 另一个原因是**空状态**：用户第一次进来时一张预设都没有，
     * 没有卡片可以指。多写几步就会出现「只有蒙版、没有高亮孔」的尴尬帧。
     */
    val PRESETS: List<SpotlightStep> = listOf(
        SpotlightStep(
            target = OnboardingTarget.PRESETS_OVERFLOW_MENU,
            titleRes = R.string.onboarding_presets_title,
            bodyRes = R.string.onboarding_presets_body,
            side = BubbleSide.BOTTOM
        )
    )

    /** 按页面取对应的引导脚本。 */
    fun of(tour: OnboardingTour): List<SpotlightStep> = when (tour) {
        OnboardingTour.HOME -> HOME
        OnboardingTour.PRESETS -> PRESETS
    }
}

/**
 * 目标控件上报的窗口坐标。
 *
 * `boundsInWindow` 而不是 `boundsInRoot`：引导层可能挂在
 * 一个独立的全屏 Dialog 里，两者坐标系不同，
 * **窗口坐标是唯一能对齐的基准**。
 */
data class TargetBounds(val rect: Rect)

/**
 * 把高亮孔的**内边距**也算进去，得到最终要挖的矩形。
 *
 * 抽成纯函数是为了可测：屏幕边缘的夹紧逻辑最容易写错，
 * 而它错了只会表现为"引导框偏了一点"，肉眼很难发现。
 *
 * [density] 必须由调用方传入（用 `LocalDensity.current`）——
 * dp→px 依赖设备密度，自己造一个 `Density(1f)` 会让内边距
 * 在高分屏上小得看不见。
 */
fun computeHoleRect(
    targetRect: Rect,
    shape: HoleShape,
    screenSize: Size,
    density: Density
): Rect {
    val pad = with(density) {
        when (shape) {
            is HoleShape.RoundedRect -> shape.padding.toPx()
        }
    }
    return Rect(
        left = (targetRect.left - pad).coerceAtLeast(0f),
        top = (targetRect.top - pad).coerceAtLeast(0f),
        right = (targetRect.right + pad).coerceAtMost(screenSize.width),
        bottom = (targetRect.bottom + pad).coerceAtMost(screenSize.height)
    )
}

/** 目标是否已经在屏幕上量到位置（滚动/首帧前可能还没有）。 */
fun TargetBounds?.isUsable(): Boolean =
    this != null && rect.width > 0f && rect.height > 0f

/** 小工具：把 Rect 的中心点取出来，箭头要对准它。 */
fun Rect.centerOffset(): Offset = Offset((left + right) / 2f, (top + bottom) / 2f)

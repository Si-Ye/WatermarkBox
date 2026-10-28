package com.watermarkbox.worker

import androidx.annotation.StringRes
import com.watermarkbox.R

/**
 * 单张图片处理**失败的原因**。
 *
 * ## 为什么要专门把原因带出来
 *
 * 此前每张失败都被压成一个 `false`，结果页只能说「失败 N 张」——
 * 用户拿到这句话什么也做不了：不知道该换图、该缩图，还是该清一下存储空间。
 *
 * 分成四类之后，「失败原因」这一行就能直接告诉用户下一步该干什么：
 *
 * | 原因 | 用户该做的 |
 * |---|---|
 * | [SOURCE_TOO_LARGE] | 先缩小图片，或换台内存更大的设备 |
 * | [SOURCE_UNREADABLE] | 换一张图（文件可能已损坏 / 被移走）|
 * | [SAVE_FAILED] | 清存储空间 / 换输出目录 |
 * | [UNKNOWN] | 看日志（属于意外异常）|
 *
 * 分类粒度是**按「用户能采取的行动」**划的，不是按异常类型划的 ——
 * 后者会让原因多到没法在结果页展示。
 *
 * ## 文案为什么挂在枚举上
 *
 * 同一个原因有两处要显示：结果页的「失败原因」分组，和导出的 CSV 清单。
 * 一开始文案是结果页里的私有 `labelRes()` 扩展函数，于是 CSV 那侧
 * 只能自己再写一份 —— 两份迟早不一致（改文案只改一处）。
 * 挂到枚举上就只有一个来源，且与项目里 `ExportMaxEdge` / `DetectChannel`
 * 的做法一致：**存资源 ID 而不是字符串**，界面才能跟随语言。
 */
enum class BatchFailureReason(@param:StringRes val labelRes: Int) {
    /** 图片太大：全分辨率处理的峰值超出内存预算（判据见 `BitmapBudget`）。 */
    SOURCE_TOO_LARGE(R.string.batch_fail_reason_too_large),

    /** 图片读不出来：文件损坏、已被删除、或没有读取权限。 */
    SOURCE_UNREADABLE(R.string.batch_fail_reason_unreadable),

    /** 渲染 / 写入失败：存储空间不足、输出目录不可写等。 */
    SAVE_FAILED(R.string.batch_fail_reason_save_failed),

    /** 其它异常（不该发生，出现时说明有 bug）。 */
    UNKNOWN(R.string.batch_fail_reason_unknown)
}
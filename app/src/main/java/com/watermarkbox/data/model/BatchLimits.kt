package com.watermarkbox.data.model

/**
 * 批量处理的容量约束。
 *
 * ## 为什么是 100
 *
 * 曾经试过 150（`MediaStore.getPickImagesMaxLimit()` 的通行默认值），
 * 但实测在本机打开 Picker 时**直接闪退**。
 *
 * 需要注意：当时的闪退**真凶并不是 150 这个数字**，而是
 * `registerForActivityResult` 被写成了 `by lazy` —— 延迟到用户点击时才注册，
 * 此时 Activity 已 RESUMED，抛
 * `IllegalStateException: attempting to register while current state is RESUMED`。
 *
 * 该注册时机问题已修复。但既然 100 已被验证可用、且对绝大多数场景足够，
 * 这里就沿用 100 作为稳妥上限。
 *
 * ## 上限在哪生效
 *
 * 1. [REQUEST_MAX_ITEMS] —— 传给 Photo Picker 的请求值（编译期常量）
 * 2. [MAX_BATCH_SIZE]   —— UI 侧展示与截断用的上限
 *
 * 两者必须一致，避免「Picker 允许选 100，但列表只留 80」这种不一致。
 */
object BatchLimits {

    /**
     * 传给 `PickMultipleVisualMedia(n)` 的上限。
     *
     * ## 为什么必须是编译期常量
     *
     * 这个值在 **Activity 构造期**就要用到（字段初始化时注册 launcher），
     * 而此时 Context 尚未完全就绪，无法查询
     * `MediaStore.getPickImagesMaxLimit()`。
     *
     * 好在这不影响正确性：Photo Picker 内部会把请求值夹到系统上限，
     * 传大不会崩 —— 之前崩的是**注册时机**，不是数值大小。
     */
    const val REQUEST_MAX_ITEMS = 100

    /** 实际生效的批量上限（UI 展示与列表截断用）。 */
    const val MAX_BATCH_SIZE = REQUEST_MAX_ITEMS

    /** 兼容旧命名的别名。 */
    const val DEFAULT_MAX_BATCH = MAX_BATCH_SIZE
}
package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BitmapUtils.swapsWidthHeight] 的单元测试。
 *
 * ## 为什么值得单独测
 *
 * 这个判据决定「预览水印基准」与「导出」是否处在**同一坐标系**
 * （详见 `BitmapUtils.queryImageSize` 的说明）。
 * 它一旦错了，表现为「水印大小仍与导出差约 1.33 倍」—— 而且
 * **只对带 EXIF 旋转的照片发作**：普通横图完全正常，手机竖拍才异常。
 * 这种"一半场景正确"的缺陷最难在使用中发现，所以用测试把 8 个方向全钉住。
 *
 * ## 为什么用字面量而不是 `ExifInterface` 常量
 *
 * 单测跑在 JVM 上。`ExifInterface.ORIENTATION_*` 虽然是编译期常量、
 * 会被内联，但写成字面量可以**彻底排除**任何类加载风险，
 * 让这条测试永远不依赖 Android 运行时。
 *
 * 取值与 AndroidX `ExifInterface` 完全一致：
 * `1=NORMAL, 2=FLIP_HORIZONTAL, 3=ROTATE_180, 4=FLIP_VERTICAL,
 * 5=TRANSPOSE, 6=ROTATE_90, 7=TRANSVERSE, 8=ROTATE_270`
 */
class BitmapUtilsExifOrientationTest {

    private companion object {
        const val UNDEFINED = 0
        const val NORMAL = 1
        const val FLIP_HORIZONTAL = 2
        const val ROTATE_180 = 3
        const val FLIP_VERTICAL = 4
        const val TRANSPOSE = 5
        const val ROTATE_90 = 6
        const val TRANSVERSE = 7
        const val ROTATE_270 = 8
    }

    /** 90°/270° 旋转与转置/反转置 → 宽高互换（手机竖拍最典型的是 ROTATE_90）。 */
    @Test
    fun `互换宽高的方向`() {
        assertTrue("ROTATE_90 必须互换宽高", BitmapUtils.swapsWidthHeight(ROTATE_90))
        assertTrue("ROTATE_270 必须互换宽高", BitmapUtils.swapsWidthHeight(ROTATE_270))
        assertTrue("TRANSPOSE 必须互换宽高", BitmapUtils.swapsWidthHeight(TRANSPOSE))
        assertTrue("TRANSVERSE 必须互换宽高", BitmapUtils.swapsWidthHeight(TRANSVERSE))
    }

    /** 翻转与 180° 旋转只做镜像/翻转，**不**改变行列。 */
    @Test
    fun `不互换宽高的方向`() {
        assertFalse("NORMAL 不应互换宽高", BitmapUtils.swapsWidthHeight(NORMAL))
        assertFalse("FLIP_HORIZONTAL 不应互换宽高", BitmapUtils.swapsWidthHeight(FLIP_HORIZONTAL))
        assertFalse("ROTATE_180 不应互换宽高", BitmapUtils.swapsWidthHeight(ROTATE_180))
        assertFalse("FLIP_VERTICAL 不应互换宽高", BitmapUtils.swapsWidthHeight(FLIP_VERTICAL))
    }

    /**
     * 未定义 / 负数 / 越界值都不应崩溃，且按「不互换」处理。
     *
     * 这是**保守**选择：拿不准方向时退回「文件原始尺寸」，
     * 与修复前的行为一致，不至于把本来正常的图弄坏。
     */
    @Test
    fun `未知方向按不互换处理`() {
        assertFalse("UNDEFINED 应不互换", BitmapUtils.swapsWidthHeight(UNDEFINED))
        assertFalse("负数应不互换", BitmapUtils.swapsWidthHeight(-1))
        assertFalse("越界值应不互换", BitmapUtils.swapsWidthHeight(999))
    }

    /**
     * **关键回归**：手机竖拍的典型场景。
     *
     * 文件 4032×3024、`orientation=ROTATE_90` —— 用户看到的是 3024×4032。
     * `queryImageSize` 必须返回 `(3024, 4032)`（旋正后），
     * 否则预览的 `baseWidth` 会是 4032、而导出的 `baseWidth` 是 3024，
     * 水印占比相差 4032/3024 ≈ 1.33 倍 —— 正是本次要修的那个缺陷。
     */
    @Test
    fun `竖拍照片的尺寸换算与导出同源`() {
        val fileW = 4032
        val fileH = 3024

        // 复刻 queryImageSize 内部的决策逻辑
        val (w, h) = if (BitmapUtils.swapsWidthHeight(ROTATE_90)) {
            fileH to fileW
        } else {
            fileW to fileH
        }

        assertEquals("旋正后宽应为 3024（= 导出侧 baseWidth）", 3024, w)
        assertEquals("旋正后高应为 4032", 4032, h)
    }

    /** 无旋转的横图：旋正前后一致，尺寸不应被改动。 */
    @Test
    fun `无旋转的横图尺寸不变`() {
        val fileW = 4032
        val fileH = 3024
        val (w, h) = if (BitmapUtils.swapsWidthHeight(NORMAL)) {
            fileH to fileW
        } else {
            fileW to fileH
        }
        assertEquals(4032, w)
        assertEquals(3024, h)
    }
}

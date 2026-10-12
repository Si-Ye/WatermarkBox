package com.watermarkbox.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Bitmap 降采样算法单元测试。
 *
 * 注意：BitmapUtils 中除 calculateInSampleSize 外都依赖 Android 框架类，
 * 无法在纯 JVM 单测中运行，因此此处只测试纯计算逻辑。
 */
class BitmapUtilsSampleSizeTest {

    @Test
    fun `目标为 0 时不降采样`() {
        assertEquals(1, BitmapUtils.calculateInSampleSize(4000, 3000, 0))
    }

    @Test
    fun `小图不降采样`() {
        // 800x600 小于目标 1024，sampleSize 应为 1
        assertEquals(1, BitmapUtils.calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun `大图降采样为 2 的幂且不小于目标`() {
        // 4000x3000 目标 1024：while 循环 4000/2=2000>=1024 -> 2；2000/2=1000<1024 停止
        assertEquals(2, BitmapUtils.calculateInSampleSize(4000, 3000, 1024))
    }

    @Test
    fun `超高分辨率图片降采样到接近目标`() {
        // 8000x6000 目标 1024：8000->4000->2000->1000(<1024 停)，sampleSize=4
        assertEquals(4, BitmapUtils.calculateInSampleSize(8000, 6000, 1024))
    }

    @Test
    fun `同时受限于宽和高`() {
        // 宽够但高不够时，不能继续降采样：5000x1500 目标 1024
        // 第一轮 5000/2=2500>=1024 且 1500/2=750<1024 -> 不满足，sampleSize=1
        assertEquals(1, BitmapUtils.calculateInSampleSize(5000, 1500, 1024))
    }

    @Test
    fun `正好等于两倍目标时降采样一次`() {
        // 2048x2048 目标 1024：2048/2=1024>=1024 -> 满足，sampleSize=2
        assertEquals(2, BitmapUtils.calculateInSampleSize(2048, 2048, 1024))
    }
}
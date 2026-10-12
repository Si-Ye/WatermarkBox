package com.watermarkbox.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppLicense] 数据一致性测试。
 *
 * 这些字段会直接展示在关于页上，写错了用户一眼就能看到，
 * 但单测能更早、更确定地发现 —— 尤其是仓库地址这类**改一处、忘一处**的值。
 */
class AppLicenseTest {

    @Test
    fun `仓库地址是合法的 https 链接`() {
        val url = AppLicense.REPOSITORY_URL
        assertTrue("仓库地址应以 https:// 开头", url.startsWith("https://"))
        assertTrue("仓库地址应指向 github.com", url.contains("github.com"))
    }

    @Test
    fun `仓库地址与展示文本指向同一仓库`() {
        // 展示文本是去掉协议头的形式，两者必须指向同一个仓库，
        // 否则用户从界面上看到的是一个、点开却是另一个。
        val display = AppLicense.REPOSITORY_DISPLAY
        val url = AppLicense.REPOSITORY_URL
        assertTrue(
            "展示文本应能在完整地址中找到",
            url.contains(display)
        )
        assertTrue(
            "展示文本不应包含协议头",
            !display.startsWith("http")
        )
    }

    @Test
    fun `许可证 SPDX 标识与名称一致`() {
        assertEquals("Apache-2.0", AppLicense.LICENSE_SPDX)
        assertTrue(
            "许可证名称应包含 Apache",
            AppLicense.LICENSE_NAME.contains("Apache")
        )
    }

    @Test
    fun `许可证全文地址是合法链接`() {
        val url = AppLicense.LICENSE_URL
        assertTrue("许可证地址应以 https:// 开头", url.startsWith("https://"))
        assertTrue("应指向 apache.org", url.contains("apache.org"))
    }

    @Test
    fun `所有展示文本非空`() {
        listOf(
            AppLicense.LICENSE_NAME,
            AppLicense.LICENSE_SPDX,
            AppLicense.REPOSITORY_URL,
            AppLicense.REPOSITORY_DISPLAY,
            AppLicense.LICENSE_URL,
            AppLicense.LICENSE_SUMMARY
        ).forEach { value ->
            assertTrue("字段不应为空", value.isNotBlank())
        }
    }
}
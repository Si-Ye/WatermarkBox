package com.watermarkbox.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 下载完成后的 APK 校验。
 *
 * ## 为什么需要校验
 *
 * 应用内更新要「下载一个 APK 再交给系统安装器」，这条链路上有多个
 * 可能出错的环节：CDN 返回了损坏的文件、下载中途被截断、
 * 甚至（极端情况下）被中间人替换。
 *
 * 光靠 HTTPS 不够 —— 它只保证「传输过程没被改」，
 * 不能保证「服务器上那份是对的」。所以下载完必须再验一遍。
 *
 * ## 三层校验（从强到弱）
 *
 * 1. **签名一致性**（最关键）：用 Android 的包解析器读 APK 的签名，
 *    与当前已安装应用的签名比对。签名一致 ⇒ 确实是**同一把私钥签发的包**，
 *    这是防篡改的**根本保证**，攻击者没有私钥就伪造不出来。
 * 2. **包名一致**：防止下载到别的应用（曾发生过 Release 传错文件）。
 * 3. **SHA-256 一致**：与 GitHub API 提供的 `digest` 比对，
 *    能发现「传输损坏」这类问题。GitHub 没给 digest 时跳过。
 *
 * ## 为什么用 PackageManager 而不是自己解析签名块
 *
 * APK 的签名可能在 v1（JAR）、v2（APK Signing Block）、v3 等多种方案里，
 * 自己解析很容易漏掉某种方案。Android 官方在 API 28+ 提供了
 * `PackageManager.getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`，
 * 直接给出**最终生效的签名证书**，各方案都被统一处理。
 *
 * ## 校验失败的后果
 *
 * 一定是**删掉文件并拒绝安装**。宁可让用户手动去 GitHub 下载，
 * 也不能把来路不明的包交给系统安装器。
 */
object ApkVerifier {

    /** 校验结果。 */
    sealed interface Result {
        /** 全部通过。 */
        data object Ok : Result

        /**
         * 校验失败。[reason] 是给用户看的简短原因，
         * [detail] 是给开发者排查用的细节（可空）。
         */
        data class Failed(val reason: FailureReason, val detail: String? = null) : Result
    }

    /** 失败原因分类（UI 据此选文案）。 */
    enum class FailureReason {
        /** 文件不存在或为空 */
        EMPTY_FILE,

        /** 大小与 Release 里标注的不符 */
        SIZE_MISMATCH,

        /** SHA-256 不符（文件损坏或被替换） */
        HASH_MISMATCH,

        /** 无法解析 APK（不是合法的 APK 文件） */
        UNPARSEABLE,

        /** 包名不符（下载到了别的应用） */
        PACKAGE_MISMATCH,

        /** 签名不符（不是同一把私钥签发的包） */
        SIGNATURE_MISMATCH
    }

    /**
     * 校验下载好的 APK。
     *
     * @param expectedSha256 GitHub 提供的 SHA-256（小写 hex）；为 null 时跳过该层
     * @param expectedSize   Release 里标注的字节数；<= 0 时跳过该层
     */
    suspend fun verify(
        context: Context,
        apk: File,
        expectedSha256: String?,
        expectedSize: Long = 0L
    ): Result = withContext(Dispatchers.IO) {
        // ---- 0. 文件必须存在且非空 ----
        if (!apk.exists() || apk.length() == 0L) {
            return@withContext Result.Failed(FailureReason.EMPTY_FILE)
        }

        // ---- 1. 大小 ----
        // 便宜且能立刻发现「下载被截断」，放在哈希之前。
        if (expectedSize > 0 && apk.length() != expectedSize) {
            return@withContext Result.Failed(
                FailureReason.SIZE_MISMATCH,
                "expected=$expectedSize actual=${apk.length()}"
            )
        }

        // ---- 2. SHA-256 ----
        if (!expectedSha256.isNullOrBlank()) {
            val actual = sha256(apk)
            if (!actual.equals(expectedSha256, ignoreCase = true)) {
                return@withContext Result.Failed(
                    FailureReason.HASH_MISMATCH,
                    "expected=$expectedSha256 actual=$actual"
                )
            }
        }

        // ---- 3. 解析 APK（拿包名 + 签名）----
        val archive = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageArchiveInfo(
                    apk.absolutePath,
                    PackageManager.GET_SIGNATURES
                )
            }
        } catch (e: Exception) {
            null
        } ?: return@withContext Result.Failed(FailureReason.UNPARSEABLE)

        // ---- 4. 包名 ----
        if (archive.packageName != context.packageName) {
            return@withContext Result.Failed(
                FailureReason.PACKAGE_MISMATCH,
                "expected=${context.packageName} actual=${archive.packageName}"
            )
        }

        // ---- 5. 签名（最关键）----
        val newSignatures = signaturesOf(archive)
        val currentSignatures = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
            }
            signaturesOf(info)
        } catch (e: Exception) {
            emptyList()
        }

        if (newSignatures.isEmpty()) {
            return@withContext Result.Failed(FailureReason.SIGNATURE_MISMATCH, "no signature in apk")
        }
        if (currentSignatures.isEmpty()) {
            // 读不到「当前应用」的签名属于异常情况，保守起见拒绝安装
            return@withContext Result.Failed(
                FailureReason.SIGNATURE_MISMATCH,
                "cannot read current signature"
            )
        }
        // 用集合相等判断：APK 可能多签（多个签名者），必须完全一致
        if (newSignatures.toSet() != currentSignatures.toSet()) {
            return@withContext Result.Failed(
                FailureReason.SIGNATURE_MISMATCH,
                "apk=${newSignatures.size} current=${currentSignatures.size}"
            )
        }

        Result.Ok
    }

    /**
     * 计算文件的 SHA-256，返回**小写十六进制**。
     *
     * 流式读取（8KB 缓冲），避免把几十 MB 的 APK 整个读进内存。
     */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 取出一个包的全部签名证书哈希（小写 hex）。
     *
     * API 28+ 用 `signingInfo`（优先取「多签历史」，覆盖旋转签名的情况）；
     * 更低版本退回已废弃的 `signatures` 字段。
     */
    private fun signaturesOf(info: PackageInfo): List<String> {
        val raw: Array<android.content.pm.Signature>? = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
        ) {
            val signing = info.signingInfo ?: return emptyList()
            if (signing.hasMultipleSigners()) {
                signing.apkContentsSigners
            } else {
                // 历史签名（签名轮换的旧证书集合）—— 兼容性更宽
                signing.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        // 用 `Signature.toByteArray()` 而非 `toCharsString()`：
        // 后者返回的是**调试用的可读串**，其格式没有跨版本保证
        // （不同 Android 版本可能不同）；前者是签名的原始字节，稳定、权威。
        // 两者都只用于"比较是否同一把密钥"，但用原始字节更可靠。
        return raw?.map { it.toByteArray().sha256Of() } ?: emptyList()
    }

    /** 对字节做 SHA-256（把证书内容压成一个可比较的短值）。 */
    private fun ByteArray.sha256Of(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(this)
            .joinToString("") { "%02x".format(it) }
    }
}

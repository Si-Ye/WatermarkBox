#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_apk_integrity.py —— 校验 APK 是不是**真的能装**

## 为什么需要这个脚本

`./gradlew assembleRelease` 成功 **≠** 产物可用。

本项目真实踩过：AGP 9.0.0 在 arm64/proot 环境下，`packageRelease` 会把
`AndroidManifest.xml` 与 `resources.arsc` 漏在包外 —— 构建全绿、APK 也生成了，
但 `pm install` 报：

    Failed to parse APK file: ... FileNotFoundException: AndroidManifest.xml

而 CI 里原来那步「编译 Release 包（未签名，仅验证能构建）」只能证明
**构建命令没报错**，证明不了产物完整。

## 检查项与理由

| 项 | 为什么 |
|---|---|
| `AndroidManifest.xml` | 缺了直接装不上（包解析阶段就失败） |
| `resources.arsc` | 缺了也许能装，但界面全乱 |
| `classes*.dex` | 缺了就是个空壳 |
| `resources.arsc` 必须 **Stored**（不压缩） | Android R+ 的硬要求；压缩过的包装不上 |
| 签名（可选，`--require-signature`） | 没签名装不上；但未签名的构建产物不该因为这条失败 |

最后一条默认只**提示**不失败 —— 因为 `build.yml` 里构建的 release 包本来就是
故意不签名的（那个 job 没有密钥）。

## 用法

    python3 tools/check_apk_integrity.py <apk> [--require-signature]

通过退出码 0；任一硬性检查不过退出码 1（CI 里即失败）。
"""

import os
import sys
import zipfile

REQUIRED_ENTRIES = ("AndroidManifest.xml", "resources.arsc")

# 读 APK 尾部找 v2/v3 签名块用的魔数
APK_SIG_BLOCK_MAGIC = b"APK Sig Block 42"


def _has_dex(names):
    return any(n.endswith(".dex") for n in names)


def _has_v1_signature(names):
    return any(n.startswith("META-INF/") and n.endswith(".SF") for n in names)


def _has_v2_or_v3_signature(apk_path):
    """v2/v3 签名块贴在 zip 结构之后，所以读文件尾部找魔数。"""
    try:
        size = os.path.getsize(apk_path)
        with open(apk_path, "rb") as f:
            f.seek(max(0, size - 512 * 1024))
            return APK_SIG_BLOCK_MAGIC in f.read()
    except OSError:
        return False


def check(apk_path, require_signature=False):
    """返回 (problems, notices)：problems 非空表示不可用。"""
    problems = []
    notices = []

    if not os.path.isfile(apk_path):
        return ["文件不存在：%s" % apk_path], notices

    try:
        with zipfile.ZipFile(apk_path) as z:
            names = z.namelist()

            for entry in REQUIRED_ENTRIES:
                if entry not in names:
                    problems.append(
                        "缺少 %s —— 这样的包装不上（包解析阶段就会失败）" % entry
                    )

            if not _has_dex(names):
                problems.append("缺少 classes*.dex —— 包是空壳")

            if "resources.arsc" in names:
                info = z.getinfo("resources.arsc")
                if info.compress_type != zipfile.ZIP_STORED:
                    problems.append(
                        "resources.arsc 被压缩了 —— Android R+ 要求 Stored（不压缩）"
                    )
    except zipfile.BadZipFile as e:
        return ["不是合法的 zip/APK：%s" % e], notices

    signed = _has_v1_signature(names) or _has_v2_or_v3_signature(apk_path)
    if not signed:
        message = "没有找到签名（v1 的 META-INF/*.SF 或 v2/v3 签名块）"
        (problems if require_signature else notices).append(message)

    return problems, notices


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    require_signature = "--require-signature" in sys.argv[1:]

    if not args:
        sys.exit(__doc__.strip().splitlines()[-1].strip() or "用法：check_apk_integrity.py <apk>")

    apk = args[0]
    problems, notices = check(apk, require_signature)

    print("[apk-check] %s (%.2f MB)" % (apk, os.path.getsize(apk) / 1048576.0))

    for n in notices:
        print("[apk-check] 提示：%s" % n)

    if problems:
        for p in problems:
            print("[apk-check] ❌ %s" % p)
        print("[apk-check] 结论：这个包**不可用**，不要发布")
        return 1

    print("[apk-check] ✅ AndroidManifest.xml / resources.arsc / dex / 压缩方式 全部正常")
    if require_signature:
        print("[apk-check] ✅ 签名块存在")
    print("[apk-check] 结论：可以安装")
    return 0


if __name__ == "__main__":
    sys.exit(main())
#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
check_profile_coverage.py —— 检查「应用自身的类」到底有没有进 baseline profile

## 为什么需要这个脚本

baseline profile 里躺着的是一堆形如

    HSPLcom/watermarkbox/ui/navigation/AppNavigationKt;->WatermarkBoxNavHost(...)V

但 release 包经过 R8 之后，类名已经被混淆成 `a.a.b` 这种，
**没法靠名字直接 grep** —— 于是「profile 里到底有没有我们的代码」
变成一个看起来无法验证的问题。

答案：用 R8 的 mapping 表反查。mapping 里有 `原名 -> 混淆名`，
把本应用类的混淆名收集起来，再回查 profile 文本即可精确统计。

## 用法

先构建 release（会产出 mapping 与 profile 中间产物），然后：

    python3 tools/check_profile_coverage.py

可选参数：mapping 与 profile 的路径（默认用 app/build 下的标准位置）。

## 判读

- 「命中的本应用类数」为 0 → profile 里没有本应用的代码，
  也就是说「启动优化」是假的（本项目就踩过这个坑）
- 非 0 → 规则真的生效了，再看包分布是否符合预期（只覆盖启动路径）
"""

import collections
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

DEFAULT_MAPPING = os.path.join(
    ROOT, "app", "build", "outputs", "mapping", "release", "mapping.txt"
)
# R8 重写 + 通配展开之后、被编译成二进制 profile 之前的最终形态
DEFAULT_PROFILE = os.path.join(
    ROOT, "app", "build", "intermediates", "combined_art_profile",
    "release", "compileReleaseArtProfile", "baseline-prof.txt",
)

APP_PACKAGE = "com.watermarkbox"
CLASS_LINE = re.compile(r"^(" + re.escape(APP_PACKAGE) + r"[\w.$]*) -> ([\w.$]+):$")
CLASS_REF = re.compile(r"L([\w/$]+);")


def collect_app_classes(mapping_path):
    """从 mapping 表读出「本应用类 -> 混淆名」。"""
    obfuscated_to_original = {}
    with open(mapping_path, encoding="utf-8") as f:
        for line in f:
            m = CLASS_LINE.match(line.strip())
            if m:
                obfuscated_to_original[m.group(2)] = m.group(1)
    return obfuscated_to_original


def analyse(profile_path, app_classes):
    """统计 profile 中命中本应用类的行数与类数。"""
    hit_lines = 0
    hit_classes = set()
    total_lines = 0
    with open(profile_path, encoding="utf-8") as f:
        for line in f:
            total_lines += 1
            names = set(CLASS_REF.findall(line))
            matched = {app_classes[n] for n in names if n in app_classes}
            if matched:
                hit_lines += 1
                hit_classes |= matched
    return total_lines, hit_lines, hit_classes


def main():
    mapping = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MAPPING
    profile = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_PROFILE

    for path in (mapping, profile):
        if not os.path.isfile(path):
            sys.exit(
                "找不到文件：%s\n"
                "请先执行 ./gradlew :app:assembleRelease\n"
                "（mapping 需要 release 开了混淆）" % path
            )

    app_classes = collect_app_classes(mapping)
    total, hit_lines, hit_classes = analyse(profile, app_classes)

    print("mapping :", mapping)
    print("profile :", profile)
    print()
    print("本应用类总数     :", len(app_classes))
    print("profile 总行数   :", total)
    print("命中本应用的行数 :", hit_lines)
    print("命中本应用的类数 :", len(hit_classes))
    print()

    if not hit_classes:
        print("⚠️  应用自身的代码**完全没有**进 profile —— 所谓「启动优化」是空的。")
        print("    常见原因：手写的通配符规则被 AGP 在展开阶段丢掉了。")
        print("    正确做法：用 Macrobenchmark 生成（见 docs/baseline-profile.md）。")
        return 1

    print("命中类的包分布（前 15）：")
    counter = collections.Counter(
        c.rsplit("/", 1)[0] if "/" in c else c for c in hit_classes
    )
    for pkg, count in counter.most_common(15):
        print("   %-58s %d" % (pkg, count))
    return 0


if __name__ == "__main__":
    sys.exit(main())

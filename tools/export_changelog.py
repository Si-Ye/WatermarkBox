#!/usr/bin/env python3
"""
export_changelog.py —— 把内置的更新说明导出为 `docs/changelog.json`。

═══════════════════════════════════════════════════════════════════
这个脚本是**一次性迁移工具**，不是每次发版都要跑
═══════════════════════════════════════════════════════════════════

## 背景

应用内的「更新说明」原本**硬编码**在两个地方：

- `data/model/ReleaseHistory.kt` —— 版本 / 日期 / 类型 / 变更性质
- `res/values*/strings.xml`     —— 每条文案（中英各一份）

这带来一个流程隐患：**发版时容易忘记同步它**（本仓库真实发生过 ——
2.1.0 发布时漏了，导致用户在新版里看不到本版说明了什么）。

改成「更新说明独立成一份 JSON 文档，应用运行时从 GitHub 拉取」之后：

- 说明的更新不再需要改代码、重新发版
- 发版流程里只需改一个文件（`docs/changelog.json`）

## 用法

    python3 tools/export_changelog.py            # 干跑：只打印「会写什么」
    python3 tools/export_changelog.py --apply    # 真写：覆盖 docs/changelog.json
    python3 tools/export_changelog.py --help

从仓库根目录执行。**默认干跑，不碰任何文件。**

这是 2026-10-16 补的护栏：此前脚本没有参数解析，`--help` 会被当成普通参数
忽略并**直接覆写** `docs/changelog.json`（真实事故：一次「想看看有什么参数」
的调用删掉了 108 行发布历史，靠 `git checkout` 才还原）。

## 之后的维护方式

**直接编辑 `docs/changelog.json`**，不要再跑这个脚本。

脚本保留只是为了：① 留下「数据从哪来」的可追溯记录；
② 万一将来 JSON 损坏，可以从 Kotlin 源重新导出。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT_FILE = ROOT / "app/src/main/java/com/watermarkbox/data/model/ReleaseHistory.kt"
ZH_XML = ROOT / "app/src/main/res/values/strings.xml"
EN_XML = ROOT / "app/src/main/res/values-en/strings.xml"
OUT = ROOT / "docs/changelog.json"

SCHEMA_VERSION = 1


def parse_strings(xml_path: Path) -> dict[str, str]:
    """读 strings.xml，返回 {name: text}。

    只收集 `release_*` 开头的键 —— 其它键与本功能无关。

    注意转义：XML 里的 `\\n` 是字面反斜杠 + n（Android 会在运行时转成换行），
    这里保持原样，让 JSON 与 Android 资源语义一致。
    """
    if not xml_path.exists():
        sys.exit(f"[export] 找不到 {xml_path}")
    text = xml_path.read_text(encoding="utf-8")
    out: dict[str, str] = {}
    for m in re.finditer(
        r'<string name="(release_[^"]+)">(.*?)</string>', text, re.DOTALL
    ):
        name, value = m.group(1), m.group(2)
        # 反转义 XML 实体（这几条文案里出现过 amp 之类）
        #
        # ⚠️ 这里刻意用 chr(38) 拼出 "&"，而不是直接写实体名。
        # 原因：本仓库的编辑工具链会对源码里的 XML 实体做一次解码，
        # 直接写 `"` 会被悄悄改成 `"`，导致本文件语法错误。
        # 拼出来就绕开了这个坑。
        amp = chr(38)
        value = (
            value.replace(amp + "amp;", amp)
            .replace(amp + "lt;", "<")
            .replace(amp + "gt;", ">")
            .replace(amp + "quot;", '"')
            .replace(amp + "apos;", "'")
        )
        out[name] = value.strip()
    return out


def parse_release_history(kt_path: Path) -> list[dict]:
    """解析 ReleaseHistory.kt，返回按文件顺序（= 展示顺序）的版本列表。

    只做「够用」的解析：Kotlin 里的结构非常规整（见文件本身），
    用正则比引入一个 Kotlin 解析器划算得多。
    """
    if not kt_path.exists():
        sys.exit(f"[export] 找不到 {kt_path}")
    src = kt_path.read_text(encoding="utf-8")

    # 先按 `ReleaseNote(` 切块。用 finditer 取每个块的起止，避免贪婪匹配跨越多个版本。
    starts = [m.start() for m in re.finditer(r"ReleaseNote\(\s*\n\s*version\s*=", src)]
    if not starts:
        sys.exit("[export] 没解析到任何版本，检查 ReleaseHistory.kt 的格式是否变了")

    releases = []
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(src)
        block = src[start:end]

        version = re.search(r'version\s*=\s*"([^"]+)"', block)
        date = re.search(r'date\s*=\s*"([^"]+)"', block)
        title = re.search(r"titleRes\s*=\s*R\.string\.(\w+)", block)
        rtype = re.search(r"type\s*=\s*ReleaseType\.(\w+)", block)
        if not (version and date and title and rtype):
            sys.exit(f"[export] 第 {i + 1} 个版本块缺字段，原文：\n{block[:300]}")

        changes = [
            {"kind": m.group(2), "key": m.group(1)}
            for m in re.finditer(
                r"ReleaseNote\.Change\(\s*R\.string\.(\w+)\s*,\s*ChangeKind\.(\w+)\s*\)",
                block,
            )
        ]

        releases.append(
            {
                "version": version.group(1),
                "date": date.group(1),
                "type": rtype.group(1),
                "titleKey": title.group(1),
                "changes": changes,
            }
        )
    return releases


def _display(path: Path) -> str:
    """尽量显示成仓库相对路径，便于复制进 issue；失败就退化成绝对路径。"""
    try:
        # 统一用正斜杠：Windows 上 Path 的 str() 是反斜杠，
        # 但文档与 diff 里一律用 `/`，输出保持一致。
        return path.relative_to(ROOT).as_posix()
    except ValueError:
        return str(path)


def _describe_change(old: str | None, new: str) -> str:
    """一句话说明「写下去会变成什么」，供干跑预览使用。"""
    if old is None:
        return "文件不存在，将新建"
    if old == new:
        return "内容与现有文件完全一致"
    old_lines = old.count("\n")
    new_lines = new.count("\n")
    return f"行数 {old_lines} → {new_lines}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="export_changelog.py",
        description=(
            "把内置更新说明（ReleaseHistory.kt + strings.xml）导出为 "
            "docs/changelog.json。默认干跑，不写盘。"
        ),
        epilog="确认无误后再加 --apply 真正覆盖目标文件。",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="真正写入目标文件（缺省只打印将要发生的变化）",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=None,
        help=f"输出路径，默认 {_display(OUT)}",
    )
    args = parser.parse_args(argv)

    out_path: Path = args.out or OUT

    zh = parse_strings(ZH_XML)
    en = parse_strings(EN_XML)
    releases = parse_release_history(KT_FILE)

    out_releases = []
    missing: list[str] = []
    for rel in releases:
        title_key = rel["titleKey"]
        if title_key not in zh:
            missing.append(f"zh:{title_key}")
        if title_key not in en:
            missing.append(f"en:{title_key}")

        items = []
        for ch in rel["changes"]:
            key = ch["key"]
            if key not in zh:
                missing.append(f"zh:{key}")
            if key not in en:
                missing.append(f"en:{key}")
            items.append(
                {
                    "kind": ch["kind"],
                    "zh": zh.get(key, ""),
                    "en": en.get(key, ""),
                }
            )

        out_releases.append(
            {
                "version": rel["version"],
                "date": rel["date"],
                "type": rel["type"],
                "title": {"zh": zh.get(title_key, ""), "en": en.get(title_key, "")},
                "changes": items,
            }
        )

    # ---- 完整性问题必须在导出时就暴露，而不是等用户打开应用才发现 ----
    if missing:
        print("[export] ❌ 以下文案在中/英资源里缺失：", file=sys.stderr)
        for k in missing:
            print(f"    {k}", file=sys.stderr)
        return 1

    doc = {
        "schemaVersion": SCHEMA_VERSION,
        # updatedAt 用最新一版的日期：它天然就是"这份文档最后动过的时间"
        "updatedAt": out_releases[0]["date"] if out_releases else "",
        "releases": out_releases,
    }

    # 保持中文可读（ensure_ascii=False），缩进 2 空格便于人工 diff
    new_text = json.dumps(doc, ensure_ascii=False, indent=2) + "\n"
    old_text = out_path.read_text(encoding="utf-8") if out_path.exists() else None

    total_changes = sum(len(r["changes"]) for r in out_releases)
    print(f"[export] 目标：{_display(out_path)}（{_describe_change(old_text, new_text)}）")
    print(f"[export]    版本 {len(out_releases)} 个 / 变更 {total_changes} 条")
    print(
        f"[export]    最新版本：{out_releases[0]['version']}（{out_releases[0]['date']}）"
    )

    if not args.apply:
        print("[export] ⏸  干跑结束：未写盘。确认无误后加 --apply 才会覆盖。")
        return 0

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(new_text, encoding="utf-8")
    print(f"[export] ✅ 已写出 {_display(out_path)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

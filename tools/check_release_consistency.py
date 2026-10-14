#!/usr/bin/env python3
"""
check_release_consistency.py —— 发版一致性校验。

═══════════════════════════════════════════════════════════════════
它解决什么问题
═══════════════════════════════════════════════════════════════════

发版要向**三个地方**写版本信息：

| 文件 | 用途 |
|---|---|
| `app/build.gradle.kts` 的 `versionName` | 客户端版本（唯一权威源） |
| `docs/changelog.json` | 应用内「更新说明」（运行时从 GitHub 拉） |
| `docs/releases/vX.Y.Z.md` | GitHub Release 页面正文 |

**历史上漏过**：2.1.0 发布时 `changelog.json` 的前身（硬编码数据）
没同步，用户在新版里看不到本版说明了什么。

而且当时**测试也拦不住** —— 那条"更新日志覆盖当前版本"的断言里
写死了一个早已过期的版本号常量（`1.4.1`），校验的其实是 1.4.1。

本脚本的版本号**只从 `build.gradle.kts` 取**，没有第二份手写副本，
所以不会再出现"校验了个寂寞"。

═══════════════════════════════════════════════════════════════════
用法
═══════════════════════════════════════════════════════════════════

    python3 tools/check_release_consistency.py

    # 额外校验 git tag 与 versionName 一致（发版 CI 用）
    python3 tools/check_release_consistency.py --tag v2.1.1

通过输出 `✅` 并返回 0；任何一项不符都会打印问题清单并返回 1
（CI 里会让构建失败）。
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = ROOT / "app/build.gradle.kts"
CHANGELOG_JSON = ROOT / "docs/changelog.json"
RELEASES_DIR = ROOT / "docs/releases"

# 与 ChangelogJson.kt / ReleaseHistory.kt 里的枚举保持一致。
# 这里重复一份是**故意的**：脚本要能独立发现"JSON 里写了个不存在的分类"，
# 而不是等客户端解析时才默默跳过那一条。
VALID_KINDS = {"ADDED", "FIXED", "IMPROVED", "CHANGED"}
VALID_TYPES = {"MAJOR", "MINOR"}


class Problems:
    """收集所有问题再统一输出 —— 一次跑完看到全部，比挤牙膏强。"""

    def __init__(self) -> None:
        self.items: list[str] = []

    def add(self, msg: str) -> None:
        self.items.append(msg)

    def check(self, ok: bool, msg: str) -> bool:
        if not ok:
            self.add(msg)
        return ok

    def ok(self) -> bool:
        return not self.items


def read_version_name() -> str | None:
    """从 build.gradle.kts 提取 versionName —— **唯一的权威来源**。"""
    if not GRADLE_FILE.exists():
        return None
    text = GRADLE_FILE.read_text(encoding="utf-8")
    m = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    return m.group(1).strip() if m else None


def read_version_code() -> int | None:
    if not GRADLE_FILE.exists():
        return None
    text = GRADLE_FILE.read_text(encoding="utf-8")
    m = re.search(r"versionCode\s*=\s*(\d+)", text)
    return int(m.group(1)) if m else None


def parse_version(v: str) -> tuple[int, ...] | None:
    parts = v.split(".")
    if len(parts) != 3 or not all(p.isdigit() for p in parts):
        return None
    return tuple(int(p) for p in parts)


def check_doc_structure(doc: dict, p: Problems) -> list[dict]:
    """校验 changelog.json 的字段完整性，返回 releases 列表。"""
    p.check("schemaVersion" in doc, "changelog.json 缺少 schemaVersion")
    p.check(isinstance(doc.get("releases"), list), "changelog.json 的 releases 不是数组")

    releases = doc.get("releases") or []
    p.check(len(releases) > 0, "changelog.json 的 releases 是空的")

    seen_versions: set[str] = set()
    parsed_order: list[tuple[int, ...]] = []

    for i, rel in enumerate(releases):
        where = f"releases[{i}]"
        version = rel.get("version", "")
        if not version:
            p.add(f"{where} 的 version 为空")
            continue
        where = f"{where}({version})"

        # ---- 版本号 ----
        if version in seen_versions:
            p.add(f"{where} 的版本号重复")
        seen_versions.add(version)
        parsed = parse_version(version)
        if parsed is None:
            p.add(f"{where} 的版本号格式不是 X.Y.Z")
        else:
            parsed_order.append(parsed)

        # ---- 基本字段 ----
        p.check(bool(rel.get("date", "").strip()), f"{where} 的 date 为空")
        rel_type = rel.get("type", "")
        p.check(
            rel_type in VALID_TYPES,
            f"{where} 的 type='{rel_type}' 不是 {sorted(VALID_TYPES)} 之一",
        )

        # ---- 标题：中英都不能空 ----
        title = rel.get("title") or {}
        for lang in ("zh", "en"):
            p.check(
                bool(str(title.get(lang, "")).strip()),
                f"{where} 的 title.{lang} 为空",
            )

        # ---- 变更条目 ----
        changes = rel.get("changes") or []
        if not p.check(len(changes) > 0, f"{where} 没有任何变更条目"):
            continue

        for j, ch in enumerate(changes):
            cwhere = f"{where}.changes[{j}]"
            kind = ch.get("kind", "")
            p.check(
                kind in VALID_KINDS,
                f"{cwhere} 的 kind='{kind}' 不是 {sorted(VALID_KINDS)} 之一",
            )
            for lang in ("zh", "en"):
                p.check(
                    bool(str(ch.get(lang, "")).strip()),
                    f"{cwhere} 的 {lang} 为空",
                )

    # ---- 必须按版本倒序（最新在最前；客户端直接按顺序渲染）----
    if len(parsed_order) == len(releases) and len(parsed_order) > 1:
        p.check(
            parsed_order == sorted(parsed_order, reverse=True),
            "changelog.json 的版本不是倒序排列（最新应排在最前）",
        )

    return releases


def main() -> int:
    parser = argparse.ArgumentParser(description="发版一致性校验")
    parser.add_argument(
        "--tag",
        default=None,
        help="额外校验 git tag（如 v2.1.1）与 versionName 一致",
    )
    args = parser.parse_args()

    p = Problems()

    # ---- ① 权威版本号 ----
    version_name = read_version_name()
    if not p.check(version_name is not None, "无法从 app/build.gradle.kts 读到 versionName"):
        p.add("  → 期望格式：versionName = \"2.1.1\"")
        print_report(p, None)
        return 1

    assert version_name is not None
    version_code = read_version_code()
    p.check(version_code is not None, "无法从 app/build.gradle.kts 读到 versionCode")

    # ---- ② changelog.json ----
    releases: list[dict] = []
    if not p.check(CHANGELOG_JSON.exists(), f"缺少 {CHANGELOG_JSON.relative_to(ROOT)}"):
        pass
    else:
        try:
            doc = json.loads(CHANGELOG_JSON.read_text(encoding="utf-8"))
        except json.JSONDecodeError as e:
            p.add(f"changelog.json 不是合法 JSON：{e}")
            doc = None
        if isinstance(doc, dict):
            releases = check_doc_structure(doc, p)

            # 最新版本必须等于 versionName —— 这是**防漏写的核心断言**
            if releases:
                newest = releases[0].get("version", "")
                p.check(
                    newest == version_name,
                    f"changelog.json 的最新版本是 {newest}，"
                    f"而 versionName 是 {version_name} —— "
                    f"发版忘了更新说明（这是用户能直接看到的问题）",
                )

    # ---- ③ Release 说明 ----
    release_md = RELEASES_DIR / f"v{version_name}.md"
    p.check(
        release_md.exists(),
        f"缺少 {release_md.relative_to(ROOT)} —— "
        f"GitHub Release 页面会没有正文（CI 的 release.yml 读这个文件）",
    )

    # ---- ④ tag（可选）----
    if args.tag is not None:
        expected = f"v{version_name}"
        p.check(
            args.tag == expected,
            f"tag 是 {args.tag}，而版本号对应 {expected} —— "
            f"打错 tag 会发布出文件名与内容不符的包",
        )

    print_report(p, version_name)
    return 0 if p.ok() else 1


def print_report(p: Problems, version_name: str | None) -> None:
    print("[release-check] 发版一致性校验")
    print(f"[release-check] 版本：{version_name or '(读取失败)'}")
    if p.ok():
        print("[release-check] ✅ 三处版本信息一致，可以发布")
        return
    print(f"[release-check] ❌ 发现 {len(p.items)} 个问题：")
    for item in p.items:
        # 多行说明（以空格缩进开头的）不额外加前缀，保持可读
        print(f"    - {item}" if not item.startswith("  ") else item)
    print()
    print("[release-check] 修复方式：")
    print("    1. 若提示 changelog.json 版本不符 → 在 releases 数组**开头**插入新版本条目")
    print("    2. 若提示缺少 vX.Y.Z.md → 参照 docs/releases/v2.1.0.md 的结构补一份")
    print("    3. 详见 docs/RELEASE_PROCESS.md")


if __name__ == "__main__":
    sys.exit(main())
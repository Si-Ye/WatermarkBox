#!/usr/bin/env python3
r"""
collab_status.py —— 多会话协作的**一条命令状态自检**。

═══════════════════════════════════════════════════════════════════
它解决什么问题（三个真实失败，同一个根因）
═══════════════════════════════════════════════════════════════════

本项目由多个 AI 会话并行推进，`docs/multi-session-collaboration.md` §0
写了「工作前必做 3 件事」（看提交历史 / 看工作区与仓库差异 / 看状态与看板）。

但它是**口头规矩**，没有任何机制强制。于是实际发生过：

| 失败 | 后果 |
|---|---|
| 用 `git status -sb | head -1` 看状态 | 漏看了 7 个未提交改动，险些提交不完整状态 |
| 没核对 git 就直接写记忆 | 把「2.1.1 已发布」写进记忆 —— 而它**早已被人类要求回退** |
| 把 HEAD 写进协作文档状态表 | 提交后立即过期，形成「改文档→HEAD 变→再改」死循环 |

**共同根因**：状态靠「人记得去查」，而机制不参与。

本脚本把「必做 3 件事」压成**一条命令**，并且
**与上次自检的快照对比、有变化就主动提醒** ——
后者专门解决「我以为还是 A，其实已经变成 B」。

═══════════════════════════════════════════════════════════════════
用法
═══════════════════════════════════════════════════════════════════

    python3 tools/collab_status.py
    python3 tools/collab_status.py --workspace <工作区路径>

工作区路径可选（且**不写死在脚本里** —— 不同机器不同，写死会被
pre-commit 的本机路径检查拦下）。传了才会做「工作区 ↔ 仓库」差异比较。

快照存在 `.git/collab-state.json`（不属于工作树，不会被提交）。
退出码：有「需要注意」的变化时为 1（便于自动化判断），否则 0。
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT = ROOT / ".git" / "collab-state.json"

LINE = "─" * 56


def git(*args: str, default: str = "") -> str:
    """跑一条 git 命令；失败返回 default（不抛异常 —— 自检脚本不该自己崩）。"""
    try:
        r = subprocess.run(
            ["git", *args],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
        )
        return r.stdout.strip() if r.returncode == 0 else default
    except Exception:
        return default


def load_snapshot() -> dict:
    try:
        return json.loads(SNAPSHOT.read_text(encoding="utf-8"))
    except Exception:
        return {}


def save_snapshot(data: dict) -> None:
    try:
        SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
        SNAPSHOT.write_text(
            json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8"
        )
    except Exception:
        pass  # 存不下也不影响本次输出


def collect(workspace: str | None) -> dict:
    head = git("rev-parse", "--short", "HEAD")
    head_title = git("log", "-1", "--pretty=%s")

    origin_head = git("rev-parse", "--short", "origin/main")
    origin_title = git("log", "-1", "--pretty=%s", "origin/main")

    ahead = git("rev-list", "--count", "origin/main..HEAD", default="0")
    behind = git("rev-list", "--count", "HEAD..origin/main", default="0")

    # 未提交改动（已跟踪文件的修改/增删）
    porcelain = git("status", "--porcelain")
    tracked_changes: list[str] = []
    untracked: list[str] = []
    for line in porcelain.splitlines():
        if not line.strip():
            continue
        if line.startswith("??"):
            untracked.append(line[3:].strip())
        else:
            tracked_changes.append(line.strip())

    # 工作区 ↔ 仓库差异（可选）
    ws_diff: list[str] = []
    ws_local: list[str] = []
    if workspace:
        ws = Path(workspace)
        if ws.is_dir():
            ws_diff, ws_local = compare_workspace(ws)
        else:
            ws_diff = [f"(路径不存在：{workspace})"]

    return {
        "time": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "head": head,
        "head_title": head_title,
        "origin_head": origin_head,
        "origin_title": origin_title,
        "ahead": int(ahead or 0),
        "behind": int(behind or 0),
        "tracked_changes": tracked_changes,
        "untracked": untracked,
        "workspace_diff": ws_diff,
        "workspace_local_only": ws_local,
    }


def _git_ignored(rel_path: str) -> bool:
    """
    该路径是否被 `.gitignore` 忽略。

    ## 为什么不自己解析 .gitignore

    匹配规则很多（`**`、目录尾斜杠、否定 `!`、多层嵌套的 .gitignore…），
    自己实现必然有偏差。直接问 git —— 它才是权威。

    `git check-ignore -q`：被忽略时退出码 0，否则 1。
    """
    try:
        r = subprocess.run(
            ["git", "check-ignore", "-q", rel_path],
            cwd=ROOT,
            capture_output=True,
            timeout=10,
        )
        return r.returncode == 0
    except Exception:
        return False


def _is_local_only(rel_path: str, name: str) -> bool:
    """
    判断某个差异项是否属于「本机专用文件」—— 它们**本来就不该入库**，
    报出来只会制造噪音、降低对报告的信任。

    规则：
    - 以 `.` 开头的文件/目录（如 `.inject_lint.py`、`.cmsg_150.txt`）
    - 以 `_` 开头的文件（本仓库把`_`前缀用作"临时脚本/截图"的标记，
      例如 `_home_top.png`、`_dct_compare.py`）
    - `__pycache__`、`.pyc`
    - **被 `.gitignore` 忽略的路径** —— 例如 `docs/build-artifacts.md`：
      它「只在本机存在」是**设计如此**，不是「同步漏了」。
      真实踩到（2026-10-14）：在 worktree 里跑本脚本时，工作区多出的
      本机文件被逐个报成「仅工作区有」，全是假警报。
    """
    if name.startswith(".") or name.startswith("_"):
        return True
    parts = Path(rel_path).parts
    if any(p == "__pycache__" or p.startswith(".") for p in parts):
        return True
    return _git_ignored(rel_path)


def _root_file_diffs(ws: Path) -> list[str]:
    """
    比对**根目录下、仓库已跟踪的文件**（AGENTS.md / CONTEXT.md / README.md …）。

    ## 为什么需要它

    `compare_workspace` 只比对了 4 个**目录**（app/src、docs、tools、.github）。
    但仓库根目录还有一批**项目文件**（入口文档、构建脚本、.gitignore…），
    它们不在任何被比对目录里，于是差异被**漏报**。

    真实后果（2026-10-14）：修好 `AGENTS.md` 并合并后，主工作区那份
    仍是旧版，而本脚本报「代码/文档一致」—— 新会话从工作区读到旧版
    入口文档，被误导。

    ## 为什么用 `git ls-files` 而不是写死清单

    写死清单要人工维护，新增根目录文件时必然漏加（正是本 bug 的成因模式）。
    用 `git ls-files` 自动取「仓库跟踪的根目录文件」：既不漏，
    也天然排除本机专用文件（local.properties / keystore.properties
    等**未被跟踪**，不会出现在列表里）。
    """
    real: list[str] = []
    for rel in git("ls-files").splitlines():
        if "/" in rel:
            continue  # 只看根目录；子目录由 compare_workspace 负责
        a = ws / rel
        b = ROOT / rel
        if not b.is_file():
            continue
        if not a.exists():
            real.append(f"仅仓库有：{rel}")
            continue
        if not a.is_file():
            real.append(f"类型不同：{rel}")
            continue
        r = subprocess.run(
            ["diff", "-q", str(a), str(b)],
            capture_output=True,
            text=True,
            timeout=30,
        )
        if r.returncode != 0:
            real.append(f"内容不同：{rel}")
    return real


def _side_and_rel(
    dir_part: str, name: str, a: Path, b: Path, rel_ws: str
) -> tuple[str, str]:
    """
    判断 `diff -rq` 的 `Only in <dir>` 属于哪一侧，并算出相对工作区的路径。

    @return `(side, rel)`，side ∈ {"ws", "repo", "?"}
    """
    p = Path(dir_part)
    for base, side in ((a, "ws"), (b, "repo")):
        try:
            sub = p.relative_to(base)
        except Exception:
            continue
        rel = (
            f"{rel_ws}/{name}"
            if str(sub) in (".", "")
            else f"{rel_ws}/{sub}/{name}"
        )
        return side, rel
    return "?", f"{rel_ws}/{name}"


def compare_workspace(ws: Path) -> tuple[list[str], list[str]]:
    """
    比较工作区与仓库。

    @return `(需要确认的差异, 本机专用文件)` —— 分开返回，
            避免把「本来就是本机的临时文件」误报成「同步漏了」。
    """
    real: list[str] = []
    local_only: list[str] = []
    pairs = [
        ("app/src", "app/src"),
        ("docs", "docs"),
        ("tools", "tools"),
        (".github", ".github"),
    ]
    for rel_ws, rel_repo in pairs:
        a = ws / rel_ws
        b = ROOT / rel_repo
        if not a.is_dir() or not b.is_dir():
            continue
        r = subprocess.run(
            ["diff", "-rq", str(a), str(b)],
            capture_output=True,
            text=True,
            timeout=60,
        )
        for line in r.stdout.splitlines():
            if "Only in" in line:
                # `diff -rq` 的输出形如：Only in <dir>: <name>
                # <dir> 可能是**工作区侧**或**仓库侧** —— 必须区分方向，
                # 否则会把「仅仓库有」误报成「仅工作区有」，把排查引向反方向。
                # （2026-10-14 真实踩到：主仓库多出个未跟踪文件，却报「仅工作区有」。）
                #
                # 顺带修一个路径 bug：旧写法只用 `name`，丢掉子目录层级
                # （`Only in .../a/b: c` 会算成 `docs/c`，应为 `docs/a/b/c`）。
                body = line.split("Only in ", 1)[1]
                dir_part, name = body.rsplit(":", 1)
                dir_part, name = dir_part.strip(), name.strip()

                side, rel = _side_and_rel(dir_part, name, a, b, rel_ws)
                if _is_local_only(rel, name):
                    local_only.append(rel)
                elif side == "repo":
                    real.append(f"仅仓库有：{rel}")
                else:
                    real.append(f"仅工作区有：{rel}")
            elif "differ" in line:
                # `diff -rq` 的输出形如：
                #     Files /path/to/A and /path/to/B differ
                #
                # 早期直接 `split(" and ")[0]` 就去 `relative_to(a)` ——
                # 但字符串里还留着 `Files ` 前缀，Path 会把它当成相对路径，
                # `relative_to` 必然失败，于是退回**原始英文行**（很难读）。
                # 这里先把前缀剥掉。
                left = line.split(" and ")[0].strip()
                if left.startswith("Files "):
                    left = left[len("Files "):]
                try:
                    rel = Path(left).relative_to(a)
                    rel_str = str(rel)
                except Exception:
                    # 兜底：至少给出文件名，不要吐原始英文行
                    rel_str = Path(left).name
                if _is_local_only(f"{rel_ws}/{rel_str}", Path(rel_str).name):
                    local_only.append(f"{rel_ws}/{rel_str}")
                else:
                    real.append(f"内容不同：{rel_ws}/{rel_str}")

    # ---- 根目录文件（AGENTS.md / CONTEXT.md / README.md / .gitignore …）----
    #
    # 上面的 pairs 只覆盖了 4 个**目录**，根目录的项目文件全被漏掉。
    # 真实后果：修好 AGENTS.md 并合并后，主工作区那份仍是旧版，
    # 而本脚本报「代码/文档一致」—— 新会话读到旧版入口文档，被误导。
    real.extend(_root_file_diffs(ws))

    return real, local_only


def changes_vs(prev: dict, cur: dict) -> list[str]:
    """与上次快照对比，返回「值得提醒的变化」。这是本脚本的核心价值。"""
    warns: list[str] = []
    if not prev:
        return warns  # 首次运行，没有基线

    if prev.get("origin_head") and prev["origin_head"] != cur["origin_head"]:
        old, new = prev["origin_head"], cur["origin_head"]
        # ---- 判定「前进」还是「回退」----
        #
        # 只看 behind 是不够的：本轮真实发生过「远端被回退」，
        # 但本地仍领先（behind = 0），于是会被误报成"别人推了新提交"。
        #
        # 更准的判据：远端 HEAD 是否**仍是本地 HEAD 的祖先**。
        #   - 是（且本地领先）→ 远端停在了本地历史上的较早位置 → **回退**
        #   - 否 → 双方分叉，或远端确实前进了
        origin_is_ancestor = (
            subprocess.run(
                ["git", "merge-base", "--is-ancestor", "origin/main", "HEAD"],
                cwd=ROOT,
                capture_output=True,
            ).returncode
            == 0
        )
        if cur["behind"] > 0:
            warns.append(f"ℹ️ 远端 HEAD 前进：{old} → {new}（别人推了新提交）")
        elif origin_is_ancestor and cur["ahead"] > 0:
            warns.append(
                f"⚠️ **远端 HEAD 回退**：{old} → {new}\n"
                f"     → 远端退到了本地历史上的更早位置，且本地领先 {cur['ahead']} 个"
                f"\n     → 有人删了提交或 force-push。**先向人类确认，别自动恢复！**"
            )
        else:
            warns.append(f"⚠️ 远端 HEAD 变了：{old} → {new}（原因不明，先查清）")

    if prev.get("ahead") is not None and prev["ahead"] != cur["ahead"]:
        warns.append(f"ℹ️ 待推送数量变化：{prev['ahead']} → {cur['ahead']}")

    if prev.get("head") and prev["head"] != cur["head"]:
        warns.append(
            f"ℹ️ 本地 HEAD 变了：{prev['head']} → {cur['head']}"
            f"\n     → 自上次自检以来，**有人（可能是你）产生了新提交**"
        )

    if not prev.get("tracked_changes") and cur["tracked_changes"]:
        warns.append("⚠️ 出现了新的未提交改动（上次自检时没有）")

    return warns


def report(s: dict, prev: dict, warns: list[str], workspace: str | None) -> None:
    print(LINE)
    print(f" 协作状态自检  ·  {s['time']}")
    print(LINE)

    # ---- 仓库 ----
    print("\n📌 仓库")
    print(f"  HEAD       {s['head']}  {s['head_title'][:44]}")
    print(f"  远端       {s['origin_head']}  {s['origin_title'][:44]}")
    if s["ahead"] or s["behind"]:
        print(f"  差异       ahead {s['ahead']} / behind {s['behind']}")
    else:
        print("  差异       与远端一致")

    # ---- 状态变化（核心）----
    if warns:
        print("\n🔔 状态变化（与上次自检相比）")
        for w in warns:
            print(f"  {w}")

    # ---- 未提交 ----
    print("\n📝 未提交改动")
    if s["tracked_changes"]:
        for c in s["tracked_changes"][:20]:
            print(f"  {c}")
        if len(s["tracked_changes"]) > 20:
            print(f"  … 还有 {len(s['tracked_changes']) - 20} 项")
    else:
        print("  无")

    # ---- 未跟踪 ----
    if s["untracked"]:
        print("\n📂 未跟踪文件（确认是否该入库）")
        for u in s["untracked"][:20]:
            print(f"  {u}")

    # ---- 工作区差异 ----
    if workspace:
        real = s["workspace_diff"]
        local_only = s.get("workspace_local_only") or []
        print(f"\n🔀 工作区 ↔ 仓库差异（{workspace}）")
        if real:
            for d in real[:30]:
                print(f"  ⚠️ {d}")
            if len(real) > 30:
                print(f"  … 还有 {len(real) - 30} 项")
        else:
            print("  代码/文档一致 ✅")
        if local_only:
            # 这些本来就该只在本机（点号开头 / __pycache__ 等），
            # 单列出来**只为透明**，不是问题。
            print(f"  （另有 {len(local_only)} 个本机专用文件，正常忽略："
                  f"{', '.join(local_only[:3])}{'…' if len(local_only) > 3 else ''}）")

    # ---- 建议 ----
    print("\n💡 建议")
    tips: list[str] = []
    if s["behind"] > 0:
        tips.append("远端领先：先 `git log HEAD..origin/main` 看清别人改了什么，不要强行 push")
    if any("回退" in w or "force" in w for w in warns):
        tips.append("远端 HEAD 后退了：先向人类确认是不是有意回退，**别自动恢复**")
    if workspace and s["workspace_diff"]:
        tips.append("工作区与仓库不一致：`cp -r <工作区>/app/src/. <仓库>/app/src/` 再提交")
    if s["untracked"]:
        tips.append("有未跟踪文件：确认是否需要入库（含本机绝对路径会被钩子拦）")
    if s["ahead"] > 0:
        tips.append(f"本地有 {s['ahead']} 个提交待推送 —— **未经人类明示不要 push**")
    if not tips:
        tips.append("状态干净，可以开工。记得读协作文档 §4 看板了解活跃任务")

    for i, t in enumerate(tips, 1):
        print(f"  {i}. {t}")

    print("\n" + LINE)


def check_sync_mode(cur: dict, workspace: str | None) -> int:
    """
    只做「工作区 ↔ 仓库是否同步」这一件事，供 **pre-commit 钩子**调用。

    ## 为什么单独做成一个模式（方案 D）

    本轮的真实痛点：所有代码改动都要手动 `cp` 同步到仓库，
    漏一次就会**提交旧代码** —— 而 `git commit` 本身不会告诉你这件事。

    钩子里调用本模式即可拦住：
    - 有实质差异 → 打印差异 + **退出码 1** → 提交被拦
    - 只有本机临时文件（`.`/`_` 前缀、`__pycache__`）→ 不算差异 → 放行

    ## 没配工作区路径怎么办

    直接放行（退出码 0）。工作区路径是**本机信息**（不入库），
    别的机器/克隆里没有配置，此时无从检查 —— 不该因此阻塞提交。
    """
    if not workspace:
        return 0  # 未配置工作区，跳过检查

    real = cur.get("workspace_diff") or []
    if not real:
        return 0

    print("[check-sync] ⚠️ 工作区与仓库不一致 —— 提交前请先同步，否则仓库里是旧代码：")
    for d in real[:20]:
        print(f"    {d}")
    if len(real) > 20:
        print(f"    … 还有 {len(real) - 20} 项")
    print()
    print("    同步命令：cp -r <工作区>/app/src/. <仓库>/app/src/")
    print("    （docs/、tools/、.github/ 同理；详见 docs/RELEASE_PROCESS.md）")
    return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="多会话协作状态自检")
    ap.add_argument(
        "--workspace",
        default=None,
        help="工作区根目录（可选）。传了才比较「工作区 ↔ 仓库」差异。",
    )
    ap.add_argument(
        "--quiet",
        action="store_true",
        help="只在有「需要注意的变化」时输出（适合放进自动化流程）",
    )
    ap.add_argument(
        "--check-sync",
        action="store_true",
        help="只检查「工作区 ↔ 仓库」是否同步；有实质差异时退出码 1。"
             "供 pre-commit 钩子调用。",
    )
    args = ap.parse_args()

    cur = collect(args.workspace)

    # ---- 模式一：只做同步检查（pre-commit 用）----
    if args.check_sync:
        return check_sync_mode(cur, args.workspace)

    # ---- 模式二：完整报告 ----
    prev = load_snapshot()
    warns = changes_vs(prev, cur)

    if args.quiet and not warns:
        save_snapshot(cur)
        return 0

    report(cur, prev, warns, args.workspace)
    save_snapshot(cur)

    # 有「需要注意」的变化时用非零退出码，便于脚本判断
    needs_attention = any(w.startswith("⚠️") for w in warns)
    return 1 if needs_attention else 0


if __name__ == "__main__":
    sys.exit(main())

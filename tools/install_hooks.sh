#!/bin/sh
# ============================================================
# 安装 git 钩子
# ============================================================
#
# git 钩子必须放在 `.git/hooks/` 里才生效，而 `.git/` 不随仓库分发。
# 所以把钩子源文件放在 `tools/git-hooks/`（受版本控制、可审查），
# 再用本脚本软链/复制到 `.git/hooks/`。
#
# 用法（在仓库根目录执行一次）：
#
#     sh tools/install_hooks.sh
#
# 每次拉取更新后如果钩子有改动，重新执行一次即可。
# ============================================================

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "错误：当前目录不是 git 仓库。" >&2
    exit 1
fi

SRC_DIR="$ROOT/tools/git-hooks"
DST_DIR="$ROOT/.git/hooks"

if [ ! -d "$SRC_DIR" ]; then
    echo "错误：找不到钩子源目录 $SRC_DIR" >&2
    exit 1
fi

mkdir -p "$DST_DIR"

count=0
COPIED=0
for hook in "$SRC_DIR"/*; do
    [ -f "$hook" ] || continue
    name=$(basename "$hook")
    dst="$DST_DIR/$name"

    # 尽量用相对软链，这样钩子内容更新后立即生效，无需重装。
    #
    # ⚠️ 相对路径必须从 **.git/hooks/ 出发**算：
    #
    #     .git/hooks/  --..-->  .git/  --../..-->  仓库根
    #
    # 早期这里写的是 `../tools/git-hooks/$name`，内核会把它解析成
    # `.git/tools/git-hooks/...` —— **根本不存在**，
    # 于是得到一个**悬空软链**：git 找不到钩子，**静默不执行**。
    # 比「不安装」更危险：因为 `ls .git/hooks/` 看着它是在的。
    # （2026-10-14 实际踩到，靠一次「故意制造未同步去测拦截」才发现。）
    rel_src="../../tools/git-hooks/$name"
    rm -f "$dst"

    # 只有 `ln -s` **并且** `[ -L ]` 也确认它是链接，才算链接成功。
    #
    # ⚠️ MSYS（Git-Bash）上 `ln -s` 常常**返回成功、实际却复制了一份**（或造出 .lnk）。
    #    只看 `ln` 的退出码就会打印「已链接」而其实是副本 ——
    #    于是改了 `tools/git-hooks/*` **不生效**，而且没人知道（2026-10-16 记录）。
    if ln -s "$rel_src" "$dst" 2>/dev/null && [ -L "$dst" ] && [ -e "$dst" ]; then
        echo "已链接：.git/hooks/$name -> $rel_src"
    else
        rm -f "$dst"
        cp "$hook" "$dst"
        chmod +x "$dst" 2>/dev/null || true
        COPIED=1
        echo "已复制：.git/hooks/$name（本机不支持软链）"
    fi
    count=$((count + 1))
done

echo ""
echo "安装完成，共 $count 个钩子。"

if [ "$COPIED" = "1" ]; then
    echo ""
    echo "⚠️ 本机是【副本模式】：钩子是复制过去的，不是软链。"
    echo "   → 以后改了 tools/git-hooks/* 的内容，**必须重跑本脚本**才生效。"
    echo "   → 原因见 docs/multi-session-collaboration.md §2.7「Windows 已知坑」第 3 条。"
fi

# 最终自检：每个钩子都必须可执行（防再次出现悬空软链）
echo "自检："
for f in "$DST_DIR"/*; do
    [ -e "$f" ] || continue
    n=$(basename "$f")
    case "$n" in *.sample) continue ;; esac
    if [ -x "$f" ]; then
        echo "  ✅ $n"
    else
        echo "  ❌ $n 不可执行（目标可能不存在）" >&2
    fi
done

echo ""
echo "试试提交一次，应该能看到 [pre-commit] 的审计输出。"
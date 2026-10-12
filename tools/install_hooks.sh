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
for hook in "$SRC_DIR"/*; do
    [ -f "$hook" ] || continue
    name=$(basename "$hook")
    dst="$DST_DIR/$name"

    # 尽量用相对软链，这样钩子内容更新后立即生效，无需重装
    rel_src="../tools/git-hooks/$name"
    rm -f "$dst"
    if ln -s "$rel_src" "$dst" 2>/dev/null; then
        echo "已链接：.git/hooks/$name -> $rel_src"
    else
        # 软链不可用（少见）时退回复制
        cp "$hook" "$dst"
        chmod +x "$dst"
        echo "已复制：.git/hooks/$name"
    fi
    count=$((count + 1))
done

echo ""
echo "安装完成，共 $count 个钩子。"
echo "试试提交一次，应该能看到 [pre-commit] 的审计输出。"
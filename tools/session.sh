#!/bin/sh
# ============================================================
# session.sh —— 多会话工作区管理（三层方案的第 1、3 层）
# ============================================================
#
# ## 它解决什么
#
# 本项目由多个 AI 会话并行推进。过去的做法是：
#
#     所有会话共用一份代码 → 各自改 → 手动 cp 同步到 git 仓库 → 提交
#
# 这条路有三个反复出问题的地方：
#
# | 问题 | 后果 |
# |---|---|
# | 手动同步容易漏 | 提交了旧代码（本轮真实踩过，手打 cp 六次以上） |
# | 改动互相覆盖 | 两个会话改同一文件，后同步的把前者的活儿冲掉 |
# | 分不清谁改的 | 日志里一堆改动，不知道属于哪次工作 |
#
# 改用 **git worktree** 后，每个会话有**独立目录 + 独立分支**，
# 天然隔离，最后统一合并 —— 把「协作问题」变成「版本控制问题」。
#
# ## 三层方案
#
# | 层 | 做什么 | 由谁实现 |
# |---|---|---|
# | 1 · 隔离 | 每个会话一个 worktree + 分支 | 本脚本 `new` |
# | 2 · 上下文 | 统一读 `CONTEXT.md`，明确职责边界 | 文档约定（见 CONTEXT.md） |
# | 3 · 合并审查 | 审查改动 → 合并 → 全量测试 → 冲突上报 | 本脚本 `review` / `merge` |
#
# ## 用法
#
#     sh tools/session.sh new <名字>        # 创建会话 worktree
#     sh tools/session.sh list              # 列出所有会话
#     sh tools/session.sh review <名字>     # 合并前审查：动了哪些文件
#     sh tools/session.sh merge <名字>      # 合并回 main（含全量测试）
#     sh tools/session.sh rm <名字>         # 删除会话工作区
#
# ## 会话 worktree 放哪
#
#     <仓库的父目录>/wmb-wt-<名字>
#
# 刻意**放在仓库外**：避免被主仓库的 git 状态扫描到，
# 也避免误提交。名字前缀统一，便于识别与清理。
#
# ## 与「工作区（FUSE）」的关系
#
# 以前那个 FUSE 工作区**不再需要**：AI 工具可直接通过
# `environment=linux` 读写 worktree 里的文件（已实测）。
# 于是「工作区 ↔ 仓库手动同步」这一步**彻底消失**。
#
# ============================================================

set -u

# ---------- 基础 ----------
ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "错误：当前目录不是 git 仓库。" >&2
    exit 1
fi
cd "$ROOT" || exit 1

WT_BASE="$(dirname "$ROOT")/wmb-wt"

# 全量测试命令（合并后必跑）。
#
# ⚠️ 用 `sh ./gradlew` 而不是 `./gradlew`：
# 本仓库的 `gradlew` 长期没有可执行位（历史遗留），
# 直接 `./gradlew` 会 `Permission denied` —— 实测踩到，
# 而且**症状具有迷惑性**：合并本身成功了，只是测试没跑起来。
# 用 `sh` 显式解释执行可以彻底绕开权限问题。
# （同时也补上了 `chmod +x gradlew`，两道保险。）
TEST_CMD='sh ./gradlew testDebugUnitTest lintDebug --console=plain'

usage() {
    sed -n 's/^# \{0,1\}//p' "$0" | sed -n '/^用法/,/^$/p' 2>/dev/null
    cat <<'EOF'
用法：
    sh tools/session.sh new <名字>       创建会话 worktree + 分支 session/<名字>
    sh tools/session.sh list             列出所有会话工作区
    sh tools/session.sh review <名字>    合并前审查（动了哪些文件）
    sh tools/session.sh merge <名字>     合并回 main（保留合并记录 + 全量测试）
    sh tools/session.sh rm <名字>        删除会话工作区（分支保留，需手工删）
    sh tools/session.sh sync             把当前改动同步回主工作区（merge 已自动调用）

约定：
    · 工作区位置：<仓库父目录>/wmb-wt-<名字>
    · 分支名：session/<名字>
    · 开工前先读仓库根的 CONTEXT.md（第 2 层）
EOF
}

# 校验会话名：只允许字母数字与短横线（避免路径穿越）
check_name() {
    case "$1" in
        ''|*[!a-zA-Z0-9_-]*)
            echo "错误：会话名只能用字母、数字、下划线、短横线（收到：$1）" >&2
            exit 1
            ;;
    esac
}

wt_path() { echo "$WT_BASE-$1"; }

# ---------- new：创建会话 ----------
cmd_new() {
    name="$1"
    check_name "$name"

    wt=$(wt_path "$name")
    br="session/$name"

    if [ -e "$wt" ]; then
        echo "错误：$wt 已存在。换一个名字，或先 'rm $name'。" >&2
        exit 1
    fi
    if git show-ref --verify --quiet "refs/heads/$br"; then
        echo "错误：分支 $br 已存在。换一个名字。" >&2
        exit 1
    fi

    # 从 **main** 开分支 —— 保证所有会话起点一致，便于后续合并
    git worktree add "$wt" -b "$br" main || exit 1

    cat <<EOF

已创建会话工作区：
    路径   $wt
    分支   $br
    起点   main

接下来请做两件事：
    1. 读 $ROOT/CONTEXT.md  —— 项目目标、已确认决策、职责边界
    2. 只改你负责的那部分文件（越界会在合并审查时被发现）

提交照旧（本地提交，不推送）：
    cd $wt && git add -A && git commit ...

干完活回来合并：
    sh tools/session.sh review $name
    sh tools/session.sh merge  $name
EOF
}

# ---------- list ----------
cmd_list() {
    echo "当前所有工作树："
    git worktree list
    echo ""
    echo "会话分支（未合并的）："
    git branch --list 'session/*' | sed 's/^/    /' || true
}

# ---------- review：合并前审查（第 3 层第 1 步）----------
cmd_review() {
    name="$1"
    check_name "$name"
    br="session/$name"

    if ! git show-ref --verify --quiet "refs/heads/$br"; then
        echo "错误：找不到分支 $br" >&2
        exit 1
    fi

    echo "═══════════════════════════════════════════════════"
    echo " 合并前审查：$br  →  main"
    echo "═══════════════════════════════════════════════════"
    echo ""

    # 1) 领先/落后
    ahead=$(git rev-list --count "main..$br")
    behind=$(git rev-list --count "$br..main")
    echo "提交数：领先 main $ahead 个 / 落后 $behind 个"
    if [ "$ahead" -eq 0 ]; then
        echo ""
        echo "⚠️ 该会话没有新提交，无需合并。"
        return 0
    fi

    # 2) 改动的文件（重点：越界检查）
    echo ""
    echo "---- 改动的文件 ----"
    git diff "main..$br" --stat | sed 's/^/    /'

    # 3) 与 main 的冲突预判
    echo ""
    echo "---- 冲突预判 ----"
    base=$(git merge-base main "$br")
    if git merge-tree "$base" main "$br" 2>/dev/null | grep -q '^<<<<<<<\|changed in both'; then
        echo "    ⚠️ 可能冲突 —— 合并时若失败，需人工决断（脚本不会自动解决）"
    else
        echo "    ✅ 无明显冲突"
    fi

    echo ""
    echo "下一步：sh tools/session.sh merge $name"
}

# ---------- 同步回主工作区（merge 收尾 / 手动）----------
#
# ## 为什么需要它
#
# worktree 流程解决了「改动怎么进仓库」，但**反方向没人管**：
# 主工作区（AI 默认环境，FUSE）里的文件不会随 merge 更新。
#
# 真实后果（2026-10-14）：修好 AGENTS.md 并 merge 后，主工作区那份
# 仍是旧版 —— 而系统提示会把**工作区的** AGENTS.md 注入给新会话，
# 于是新会话读到旧版入口文档（等于白修）。
#
# ## 工作区路径从哪来
#
# 复用 pre-commit 钩子已有的本机配置 `.git/collab-config`（不入库），
# 格式 `WORKSPACE=<工作区根目录>`。没配置就跳过（别的机器无从同步）。
#
# ## 只同步「仓库里确实存在的」文件
#
# 删除的文件**不碰工作区** —— 保守优先，避免误删工作区里别的东西。
# 未跟踪的本机文件本来就不在改动列表里，天然排除。
sync_to_workspace() {
    # 工作区路径来源（优先级）：环境变量 $WORKSPACE > 本机配置 .git/collab-config
    # 环境变量优先 —— 便于测试与临时指定。
    ws="${WORKSPACE:-}"
    if [ -z "$ws" ]; then
        cfg="$ROOT/.git/collab-config"
        [ -f "$cfg" ] && ws=$(sed -n 's/^WORKSPACE=//p' "$cfg" | head -1)
    fi

    if [ -z "$ws" ] || [ ! -d "$ws" ]; then
        echo "（未配置工作区，跳过同步；如需自动同步，在 .git/collab-config 写 WORKSPACE=<路径>）"
        return 0
    fi

    # 本次改动的文件（merge 后 HEAD 是 merge commit，HEAD^ 是旧 main）
    files=$(git diff --name-only HEAD^ HEAD 2>/dev/null)
    if [ -z "$files" ]; then
        echo "（本次无改动文件）"
        return 0
    fi

    n=0
    for f in $files; do
        if [ -f "$ROOT/$f" ]; then
            mkdir -p "$(dirname "$ws/$f")"
            cp "$ROOT/$f" "$ws/$f"
            n=$((n + 1))
        fi
    done
    echo "已同步 $n 个文件到工作区：$ws"
}

# ---------- sync：手动同步回工作区 ----------
cmd_sync() {
    echo "═══════════════════════════════════════════════════"
    echo " 同步回主工作区"
    echo "═══════════════════════════════════════════════════"
    sync_to_workspace
}

# ---------- merge：合并（第 3 层）----------
cmd_merge() {
    name="$1"
    check_name "$name"
    br="session/$name"

    if ! git show-ref --verify --quiet "refs/heads/$br"; then
        echo "错误：找不到分支 $br" >&2
        exit 1
    fi

    # main 必须干净，否则合并后分不清哪些改动来自哪。
    #
    # ⚠️ 只看**已跟踪**文件的改动（`--untracked-files=no`）：
    # 未跟踪文件（例如正在等脱敏、有意不入库的文档）**不影响 git merge**，
    # 若把它们也算作"不干净"，就会让 merge **被永久误拦**
    # —— 实测踩到：`docs/rust-migration-feasibility.md` 待脱敏，
    # 每次 merge 都被它挡住。
    if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
        echo "错误：main 有未提交改动（已跟踪文件），先处理干净再合并。" >&2
        git status --short --untracked-files=no | head -10 >&2
        exit 1
    fi

    cur=$(git branch --show-current)
    if [ "$cur" != "main" ]; then
        echo "错误：当前在 $cur 分支，合并必须在 main 上进行。" >&2
        exit 1
    fi

    echo "═══════════════════════════════════════════════════"
    echo " 合并：$br  →  main"
    echo "═══════════════════════════════════════════════════"

    # ---- 合并前先审查（第 3 层要求：先审查再合并）----
    echo ""
    echo "[1/3] 审查改动"
    git diff "main..$br" --stat | tail -20 | sed 's/^/    /'

    # ---- 合并：--no-ff 保留合并记录（用户明确要求）----
    echo ""
    echo "[2/3] 合并（--no-ff 保留合并记录）"
    if ! git merge --no-ff "$br" -m "merge: 合并 $br

由 tools/session.sh merge 执行。
--no-ff 保留合并记录，便于日后回溯「这批改动来自哪次会话工作」。"; then
        echo ""
        echo "═══════════════════════════════════════════════════"
        echo " ⚠️ 合并冲突 —— 需要人工决断"
        echo "═══════════════════════════════════════════════════"
        echo " 冲突文件："
        git diff --name-only --diff-filter=U | sed 's/^/    /'
        echo ""
        echo " 处理方式："
        echo "   放弃合并：  git merge --abort"
        echo "   手工解决：  编辑冲突文件后 git add + git commit"
        echo ""
        echo " ⚠️ 脚本**不会自动解决冲突** —— 两个会话可能各有合理之处，"
        echo "    应把两方收益/代价报告给人类，由人类决断。"
        exit 1
    fi

    # ---- 合并后跑全量测试 ----
    echo ""
    echo "[3/3] 全量测试（合并后必跑，冲突可能引入静默错误）"
    if sh -c "$TEST_CMD"; then
        echo ""
        echo "✅ 合并完成且测试通过。"
        echo ""
        echo "[收尾] 同步回主工作区"
        sync_to_workspace
        echo ""
        echo "提示：本脚本**不推送**。远端可见性由人类决定。"
    else
        echo ""
        echo "═══════════════════════════════════════════════════"
        echo " ❌ 合并后测试失败"
        echo "═══════════════════════════════════════════════════"
        echo " 合并本身成功了（代码已进 main），但测试没过。"
        echo " 选择："
        echo "   撤销合并：git reset --hard HEAD~1   ⚠️ 会丢弃本次合并"
        echo "   或先排查失败原因（可能是两边的改动语义冲突）"
        exit 1
    fi
}

# ---------- rm ----------
cmd_rm() {
    name="$1"
    check_name "$name"
    wt=$(wt_path "$name")

    if [ ! -e "$wt" ]; then
        echo "错误：$wt 不存在。" >&2
        exit 1
    fi

    # 有未提交改动时拒绝（避免误删工作成果）
    if [ -n "$(git -C "$wt" status --porcelain 2>/dev/null)" ]; then
        echo "⚠️ $wt 里有未提交改动：" >&2
        git -C "$wt" status --short | head -10 >&2
        echo "" >&2
        echo "先提交或丢弃，再删除。强行删除：git worktree remove $wt --force" >&2
        exit 1
    fi

    git worktree remove "$wt" || exit 1
    echo "已删除工作区 $wt"
    echo "（分支 session/$name 仍保留；确认不用了可：git branch -D session/$name）"
}

# ---------- 分发 ----------
case "${1:-}" in
    new)    shift; [ $# -ge 1 ] || { usage; exit 1; }; cmd_new "$1" ;;
    list)   cmd_list ;;
    review) shift; [ $# -ge 1 ] || { usage; exit 1; }; cmd_review "$1" ;;
    merge)  shift; [ $# -ge 1 ] || { usage; exit 1; }; cmd_merge "$1" ;;
    rm)     shift; [ $# -ge 1 ] || { usage; exit 1; }; cmd_rm "$1" ;;
    sync)   cmd_sync ;;
    *)      usage; exit 1 ;;
esac
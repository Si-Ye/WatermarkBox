#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# push_release.sh —— 一键同步 + 自查 + 提交 + 推送
#
# 把「工作区 → git 仓库 → GitHub」的整套流程串起来，避免手抖漏步骤。
#
# 用法：
#   sh tools/push_release.sh "提交说明"                # 提交并推送
#   sh tools/push_release.sh "提交说明" --dry-run      # 只自查，不提交
#   sh tools/push_release.sh --tag v1.4.1 "说明"       # 顺便打 tag（触发 Release）
#
# 环境变量：
#   GITHUB_TOKEN    必填（推送时）。经 http.extraheader 传入，**不写入 .git/config**；
#                   建议用完立刻在 GitHub 设置里删除该令牌。
#   SRC_DIR         源码工作区，默认自动探测
#   WMB_GIT_DIR     git 仓库目录，默认自动探测（优先当前目录）
#
# 注意：本脚本刻意**不使用** GIT_DIR 环境变量名，因为它会全局改变 git 的
#       行为（让 git 在任意目录都去找那个仓库），从而污染当前 shell，
#       后续在该 shell 里执行 git 命令会莫名失败。
#
# 退出码：
#   0 = 成功    1 = 自查未通过或出错
# ---------------------------------------------------------------------------

set -u

# ---------- 推送封装 ----------
# PUSH_AUTH_HEADER 非空时（HTTPS 分支会设置），把令牌作为本次命令的 -c 参数传给 git。
#
# 为什么不用 `git remote set-url` 注入令牌：
#   那样会把令牌明文写进 .git/config，靠 EXIT trap 还原。脚本被 kill -9、
#   终端被关、或机器断电时 trap 不触发，令牌就长期留在本地配置里。
#   2026-10-16 的历史审查确认过这条路径，故改为 Authorization 头（GitHub 官方推荐）。
#
# 已知边界：头内容会出现在本机进程命令行（ps 可见）。这是有意的取舍 ——
# 本机单人使用，写盘残留比进程可见性更值得消除。
PUSH_AUTH_HEADER=""
git_push() {
    if [ -n "${PUSH_AUTH_HEADER:-}" ]; then
        git -c http.extraheader="$PUSH_AUTH_HEADER" push "$@"
    else
        git push "$@"
    fi
}

# ---------- 参数 ----------
TAG=""
DRY_RUN=0
MESSAGE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --tag) TAG="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --file) MESSAGE_FILE="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,26p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) MESSAGE="$1"; shift ;;
    esac
done

# 支持两种提交信息写法：
#   1) 命令行直接给（适合单行）：sh push_release.sh "fix: 修复某问题"
#   2) 从文件读（适合多行）：     sh push_release.sh --file msg.txt
# 注意：命令行里的 \n 不会被自动展开（那是 shell 的行为，不是脚本的），
#       要写多行正文请用 --file。
if [ -n "${MESSAGE_FILE:-}" ]; then
    [ -f "$MESSAGE_FILE" ] || { echo "找不到提交信息文件：$MESSAGE_FILE" >&2; exit 1; }
    MESSAGE="$(cat "$MESSAGE_FILE")"
fi

# ---------- 路径探测 ----------
# git 仓库：优先环境变量，其次当前目录（若已是仓库），最后常见位置。
GIT_DIR_PATH="${WMB_GIT_DIR:-}"
if [ -z "$GIT_DIR_PATH" ]; then
    if git rev-parse --show-toplevel >/dev/null 2>&1; then
        GIT_DIR_PATH="$(git rev-parse --show-toplevel)"
    else
        for candidate in "$HOME/wmb-git" "${TMPDIR:-/tmp}/wmb-git" /tmp/wmb-git ; do
            [ -d "$candidate/.git" ] && GIT_DIR_PATH="$candidate" && break
        done
    fi
fi
if [ -z "${SRC_DIR:-}" ]; then
    for candidate in \
        /storage/emulated/0/OperitAI/WatermarkBox \
        /sdcard/OperitAI/WatermarkBox ; do
        [ -d "$candidate" ] && SRC_DIR="$candidate" && break
    done
fi

# 独立的工作区只在走「已废弃的 tar 同步段」时才需要（见下方 1/6）。
# 单份代码平台（Windows 等）$WORKSPACE = $GIT_REPO = 仓库根，不必再指定 SRC_DIR。
if [ "${WMB_LEGACY_TWO_COPY_SYNC:-0}" = "1" ]; then
    [ -d "${SRC_DIR:-}" ] || { echo "找不到源码工作区（可用 SRC_DIR 指定）" >&2; exit 1; }
fi
[ -d "$GIT_DIR_PATH/.git" ] || { echo "找不到 git 仓库：$GIT_DIR_PATH" >&2; exit 1; }

# ---------- 输出辅助 ----------
if [ -t 1 ]; then
    R='\033[31m'; G='\033[32m'; Y='\033[33m'; B='\033[1m'; N='\033[0m'
else
    R=''; G=''; Y=''; B=''; N=''
fi
step() { printf "\n${B}▸ %s${N}\n" "$1"; }
ok()   { printf "${G}  ✓ %s${N}\n" "$1"; }
warn() { printf "${Y}  ! %s${N}\n" "$1"; }
die()  { printf "${R}  ✗ %s${N}\n" "$1"; exit 1; }

printf "${B}══════════════════════════════════════════${N}\n"
printf "${B}  发布流程${N}\n"
# 独立的「源码工作区」只在走旧的双份同步时才存在
[ "${WMB_LEGACY_TWO_COPY_SYNC:-0}" = "1" ] && printf "  源码: %s\n" "$SRC_DIR"
printf "  仓库: %s\n" "$GIT_DIR_PATH"
[ -n "$TAG" ] && printf "  标签: %s\n" "$TAG"
[ "$DRY_RUN" = "1" ] && printf "  模式: ${Y}试运行${N}\n"
printf "${B}══════════════════════════════════════════${N}\n"

# ===========================================================================
# ⚠️ 已废弃：工作区 → 仓库 tar 同步段（保留仅供参考，默认跳过）
# ===========================================================================
# 过去有两份代码（$WORKSPACE 工作区 + $GIT_REPO 仓库），改完要手动同步；
# 现在单份代码平台（Windows 等）$WORKSPACE = $GIT_REPO = 仓库根，
# 且改代码走 worktree（见 docs/multi-session-collaboration.md §2.6），
# 这段 tar 管道既没必要，在单份代码上还是「仓库往自己身上拷」，故默认跳过。
# 真要跑旧流程：WMB_LEGACY_TWO_COPY_SYNC=1 sh tools/push_release.sh "说明"
if [ "${WMB_LEGACY_TWO_COPY_SYNC:-0}" = "1" ]; then
    # =======================================================================
    step "1/6 同步工作区 → git 仓库"
    # =======================================================================
    # 用 tar 管道同步，排除敏感文件与缓存。
    # 注意：/sdcard 是 FUSE 挂载，不支持 Unix 权限位，tar 会报 mode 警告，不影响内容。
    cd "$SRC_DIR" || die "无法进入源码目录"

    tar cf - \
        --exclude='./dist' \
        --exclude='./.gradle' \
        --exclude='./.kotlin' \
        --exclude='./app/build' \
        --exclude='./build' \
        --exclude='./keystore' \
        --exclude='./keystore.properties' \
        --exclude='./local.properties' \
        --exclude='./.git' \
        . 2>/dev/null | (cd "$GIT_DIR_PATH" && tar xf - 2>/dev/null)

    ok "同步完成"
else
    warn "跳过「工作区 → 仓库」tar 同步段（已废弃；要走旧流程设 WMB_LEGACY_TWO_COPY_SYNC=1）"
fi

# ===========================================================================
step "2/6 暂存变更"
# ===========================================================================
cd "$GIT_DIR_PATH" || die "无法进入仓库目录"
git add -A

CHANGED=$(git diff --cached --name-only | wc -l | tr -d ' ')
if [ "$CHANGED" -eq 0 ]; then
    warn "没有变更，无需提交"
    if [ "$DRY_RUN" = "1" ]; then
        printf "\n${G}${B}  试运行结束${N}\n"
        exit 0
    fi
fi
ok "暂存 $CHANGED 个文件"

# ===========================================================================
step "3/6 敏感信息自查"
# ===========================================================================
if [ -x tools/pre_push_audit.sh ] || [ -f tools/pre_push_audit.sh ]; then
    if sh tools/pre_push_audit.sh --staged; then
        ok "自查通过"
    else
        die "自查未通过 —— 请先处理上面的问题，不要强行推送"
    fi
else
    warn "未找到 tools/pre_push_audit.sh，跳过自查（不推荐）"
fi

# ===========================================================================
step "4/6 提交"
# ===========================================================================
if [ "$DRY_RUN" = "1" ]; then
    warn "试运行：跳过提交"
else
    if [ "$CHANGED" -eq 0 ]; then
        warn "无变更可提交"
    else
        if [ -z "$MESSAGE" ]; then
            die "缺少提交说明。用法：sh tools/push_release.sh \"说明文字\""
        fi
        git commit -q -m "$MESSAGE" || die "提交失败"
        ok "已提交：$(git log --oneline -1)"
    fi
fi

# ---------- 推送（SSH 优先，HTTPS 回退）----------
step "5/6 推送"
if [ "$DRY_RUN" = "1" ]; then
    warn "试运行：跳过推送"
else
    CURRENT_URL=$(git remote get-url origin)

    # 判断走哪条路：
    #   SSH  —— 凭据在 ~/.ssh/，无需令牌，最省心（推荐）
    #   HTTPS —— 需要 GITHUB_TOKEN，脚本临时注入并在结束后还原
    case "$CURRENT_URL" in
        git@*|ssh://*)
            # 原为未定义的 info()；选 warn —— 同为单行彩色提示，step 会插空行并当小标题用
            warn "使用 SSH 凭据（$CURRENT_URL）"
            if git push origin main 2>&1 | tail -3; then
                ok "推送成功"
            else
                die "推送失败 —— 检查 SSH 密钥是否已加到 GitHub 账号"
            fi
            ;;
        *)
            if [ -z "${GITHUB_TOKEN:-}" ]; then
                die "remote 是 HTTPS 且缺少 GITHUB_TOKEN。\n     建议改用 SSH：git remote set-url origin git@github.com:Si-Ye/WatermarkBox.git"
            fi
            # 同上：未定义的 info() → 单行提示用 warn
            warn "使用 HTTPS 令牌（经 http.extraheader 传入，不落盘）"

            # 只在本次命令生效，不改动 origin 的 URL（见文件顶部 git_push 的说明）
            PUSH_AUTH_HEADER="AUTHORIZATION: basic $(printf 'x-access-token:%s' "$GITHUB_TOKEN" | base64 | tr -d '\r\n')"

            if git_push origin main 2>&1 | tail -3; then
                ok "推送成功"
            else
                die "推送失败（检查令牌权限或是否已过期）"
            fi
            ;;
    esac

    # 打 tag 会触发 GitHub 的 Release 草稿流程
    if [ -n "$TAG" ]; then
        if git rev-parse "$TAG" >/dev/null 2>&1; then
            warn "标签 $TAG 已存在，跳过"
        else
            git tag -a "$TAG" -m "Release $TAG"
            git_push origin "$TAG" 2>&1 | tail -2
            ok "标签 $TAG 已推送"
        fi
    fi
fi

# ===========================================================================
step "6/6 结果"
# ===========================================================================
printf "\n  最近提交：\n"
git log --oneline -3 | sed 's/^/    /'
printf "\n  未推送提交：\n"
if git rev-parse --verify -q '@{upstream}' >/dev/null 2>&1; then
    UNPUSHED=$(git log @{upstream}..HEAD --oneline 2>/dev/null | wc -l | tr -d ' ')
    if [ "$UNPUSHED" -eq 0 ]; then
        printf "    ${G}（无，已全部同步）${N}\n"
    else
        printf "    ${Y}$UNPUSHED 个待推送${N}\n"
        git log @{upstream}..HEAD --oneline | sed 's/^/    /'
    fi
fi

printf "\n${B}══════════════════════════════════════════${N}\n"
printf "${G}${B}  完成${N}\n"
printf "${B}══════════════════════════════════════════${N}\n"
printf "\n${Y}提醒：用过的 GitHub 令牌请到设置页删除${N}\n"
printf "      https://github.com/settings/tokens\n\n"
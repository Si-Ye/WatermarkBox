#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# pre_push_audit.sh —— 推送前的敏感信息自查
#
# 用途：
#   在 git push 之前跑一遍，检查是否有不该公开的内容混进版本库。
#   覆盖两类问题：
#     (A) 不该提交的文件（密钥、安装包、本机配置、产物目录）
#     (B) 不该出现的内容（密码、令牌、本机绝对路径、内网地址）
#
# 用法：
#   sh tools/pre_push_audit.sh              # 检查已暂存 + 已提交的完整历史
#   sh tools/pre_push_audit.sh --staged     # 只检查暂存区（提交前快速自查）
#   sh tools/pre_push_audit.sh --path DIR   # 指定项目根目录（默认脚本上级目录）
#
# 退出码：
#   0 = 通过    1 = 发现问题（详见输出）
# ---------------------------------------------------------------------------

set -u

# ---------- 参数 ----------
MODE="full"
ROOT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --staged) MODE="staged"; shift ;;
        --path) ROOT="$2"; shift 2 ;;
        -h|--help)
            sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *) echo "未知参数: $1（用 --help 查看用法）" >&2; exit 2 ;;
    esac
done

if [ -z "$ROOT" ]; then
    ROOT="$(cd "$(dirname "$0")/.." && pwd)"
fi
cd "$ROOT" || { echo "无法进入目录: $ROOT" >&2; exit 2; }

# ---------- 输出辅助 ----------
if [ -t 1 ]; then
    R='\033[31m'; G='\033[32m'; Y='\033[33m'; B='\033[1m'; N='\033[0m'
else
    R=''; G=''; Y=''; B=''; N=''
fi

ISSUES=0

fail() { printf "${R}  ✗ %s${N}\n" "$1"; ISSUES=$((ISSUES + 1)); }
pass() { printf "${G}  ✓ %s${N}\n" "$1"; }
info() { printf "${Y}  · %s${N}\n" "$1"; }
head_() { printf "\n${B}%s${N}\n" "$1"; }

# ---------- 检查用的文件集合 ----------
# staged 模式：只看暂存区；full 模式：看整个仓库的受版本控制文件 + 全部历史
list_checked_files() {
    if [ "$MODE" = "staged" ]; then
        git diff --cached --name-only --diff-filter=ACMR
    else
        git ls-files
    fi
}

# 在指定内容范围内做正则匹配
# 用法: scan_content <描述> <正则> [排除正则]
#
# 注意：本脚本自身含有大量用于检测的模式串，会必然自匹配。
# 因此所有扫描都排除本脚本文件（SELF），避免假阳性。
SELF="tools/pre_push_audit.sh"

scan_content() {
    local desc="$1" pattern="$2" exclude="${3:-}"
    local hits

    # 通用过滤：去掉「本脚本自身」与「规则测试文件」的命中行。
    #
    # ## 为什么要排除测试文件
    #
    # `tools/test_audit_rules.sh` 的职责就是**故意构造看起来像密码的
    # 字符串**去验证规则。它必然包含 `storePassword=xxx` 这类片段
    # ——否则测不出东西。
    #
    # 如果不排除，审计脚本会把测试文件本身报成"发现密码"，
    # 导致每次提交维护者都要手动 --no-verify，钩子形同虚设。
    #
    # ## 为什么排除是安全的
    #
    # 用**精确路径**而非通配符：只有这一个文件被豁免，
    # 别人无法通过改名或加后缀绕过。
    # 该文件本身也受代码审查（改动会出现在 diff 里）。
    strip_self() {
        grep -vF "$SELF" \
            | grep -vF 'pre_push_audit.sh' \
            | grep -vF 'tools/test_audit_rules.sh' \
            || true
    }

    if [ "$MODE" = "staged" ]; then
        hits=$(git grep --cached -nIE "$pattern" 2>/dev/null | strip_self)
    else
        # 完整模式扫描两个来源：
        #   1. 当前工作树内容
        #   2. 将要推送的提交（HEAD 与上游的差集）的新增行
        #
        # 只扫「将要推送的」而非全历史 —— 因为：
        #   - 已被 force push 覆盖的旧提交仍留在本地 reflog，但远端已无，
        #     扫它们会产生无法消除的假阳性；
        #   - 真正要紧的是"这次会推上去什么"。
        local range=""
        if git rev-parse --verify -q '@{upstream}' >/dev/null 2>&1; then
            range="@{upstream}..HEAD"
        fi

        hits=$(git grep -nIE "$pattern" 2>/dev/null | strip_self)
        if [ -n "$range" ]; then
            hits="$hits
$(git log -p "$range" 2>/dev/null | grep -IE "^\+.*($pattern)" | strip_self)"
        fi
        hits=$(printf '%s\n' "$hits" | grep -v '^$' | sort -u)
    fi

    # 排除审计脚本自身（它包含大量用于检测的模式串，必然自匹配）
    if [ -n "$hits" ]; then
        hits=$(printf '%s\n' "$hits" | grep -vF 'pre_push_audit.sh')
    fi

    if [ -n "$exclude" ] && [ -n "$hits" ]; then
        hits=$(printf '%s\n' "$hits" | grep -vIE "$exclude")
    fi

    if [ -n "$hits" ]; then
        fail "$desc"
        printf '%s\n' "$hits" | head -10 | sed 's/^/      /'
        local n
        n=$(printf '%s\n' "$hits" | wc -l | tr -d ' ')
        [ "$n" -gt 10 ] && info "... 另有 $((n - 10)) 处"
    else
        pass "$desc"
    fi
}

printf "${B}══════════════════════════════════════════${N}\n"
printf "${B}  推送前敏感信息自查${N}\n"
printf "  目录: %s\n" "$ROOT"
printf "  模式: %s\n" "$MODE"
printf "${B}══════════════════════════════════════════${N}\n"

# ===========================================================================
head_ "[1/5] 危险文件是否入库"
# ===========================================================================
FILES=$(list_checked_files)

if [ -z "$FILES" ]; then
    info "没有可检查的文件"
else
    # 注意：所有后缀都锚定到行尾（$），否则 build_release_apk.py 会被
    # ".apk" 误匹配、setup_android_env.sh 会被 ".env" 误匹配。
    for entry in \
        "签名密钥:\.jks$" \
        "密钥库:\.keystore$" \
        "PKCS12:\.(p12|pfx)$" \
        "PEM:\.pem$" \
        "私钥:\.key$" \
        "密钥 base64 副本:\.b64$" \
        "安装包:\.apk$" \
        "发布包:\.aab$" \
        "加密存档:\.(kdbx|gpg|age)$" \
        "环境变量:\.env$" \
        "调试日志:\.log$" \
        "数据库:\.(db|sqlite|sqlite3)$" \
        "产物目录:^dist/" \
        "平台配置:^\.operit/" \
        "对话备份:^\.backup/" \
        "本地工具:^tools/aapt2$" \
        ; do
        name="${entry%%:*}"
        pat="${entry#*:}"
        found=$(printf '%s\n' "$FILES" | grep -IE "$pat" || true)
        if [ -n "$found" ]; then
            fail "$name 被纳入版本控制"
            printf '%s\n' "$found" | head -5 | sed 's/^/      /'
        fi
    done

    # 明确点名本地配置文件（它们应当被 .gitignore 排除）
    for f in local.properties keystore.properties; do
        if printf '%s\n' "$FILES" | grep -qxF "$f"; then
            fail "本机配置 $f 被纳入版本控制"
        fi
    done
    pass "危险文件检查完成"
fi

# ===========================================================================
head_ "[2/5] 凭据与密码"
# ===========================================================================
# 排除占位符写法：**** / your-xxx / <xxx> / CHANGE_ME
PLACEHOLDER='\*\*\*\*|your[-_]|<[^>]*>|CHANGE[_-]?ME|xxx+|示例|替换|placeholder'

# 密码检查：只抓「赋值」形态，放行读取形态。
#   要抓：storePassword = "真实密码"      （字面量赋值）
#   放行：storePassword = props.get(...)  （从配置读取）
#         storePassword=****             （占位符）
scan_content "无硬编码密码（带引号）" \
    '(storePassword|keyPassword|ksPass|keyPass|KS_PASS|KEY_PASS)\s*[:=]\s*["'"'"'][^"'"'"']{4,}["'"'"']' \
    "$PLACEHOLDER"

# 无引号形态也要抓。
#
# ## 为什么需要单独一条（踩坑记录）
#
# 上面的规则要求值必须带引号，所以 `storePassword=SuperSecret123`
# 这种**不带引号**的写法会漏过 —— 而 `keystore.properties` 恰恰就是
# 这种格式（`storePassword=xxx`）。
#
# 2026-10 实测：往暂存区放一个 `storePassword=SuperSecret123` 的文件，
# 上面的规则**没有报错**，说明存在真实盲区。
#
# ## 怎么避免误报
#
# 放行三类：
#   - 空值：        storePassword=
#   - 占位符：      storePassword=**** / CHANGE_ME / your-xxx
#   - 代码表达式：  storePassword=$FOO / ${FOO} / props.get(...) / System.getenv(...)
#
# 其中「代码表达式」用行尾特征排除：含 `$/{}()` 之一即视为非字面量。
# 用 grep 的管道二次过滤实现（scan_content 不支持这种排除，所以这里手工做）。
#
# ⚠️ 踩坑 1：`grep -vFiE` 是**非法组合** —— `-F`（固定串）与 `-E`（扩展正则）
# 互斥，grep 会报 "conflicting matchers specified" 并**滤掉全部输入**，
# 导致这条检查永远「通过」（假阴性）。$PLACEHOLDER 是正则，必须用 -E。
#
# ⚠️ 踩坑 2：printf 模板会被误报。
# CI 里写 `printf '...storePassword=%s\n...' "$PASS"` 是**正常且安全**的
# （%s 是格式占位符，真值在变量里）。所以要把 `%s` / `%1$s` 等
# printf 占位符一并放行。
_noquote_pw() {
    local pat='(storePassword|keyPassword|ksPass|keyPass|KS_PASS|KEY_PASS)\s*[:=]\s*[^"'"'"'\s].[^\s]*'
    if [ "$MODE" = "staged" ]; then
        git grep --cached -nIE "$pat" 2>/dev/null
    else
        git grep -nIE "$pat" 2>/dev/null
    fi \
    | grep -vF 'pre_push_audit.sh' \
    | grep -vF 'tools/test_audit_rules.sh' \
    | grep -vE "$PLACEHOLDER" \
    | grep -vE '[:=][[:space:]]*$' \
    | grep -vE '[:=][[:space:]]*%[0-9]*[$]?[a-zA-Z]' \
    | grep -vE '[:=][[:space:]]*[^[:space:]]*[$({][A-Za-z_]?' \
    | grep -vE '[:=][[:space:]]*[^[:space:]]*[(][)]' \
    || true
}
HITS_NOQUOTE=$(_noquote_pw)
if [ -n "$HITS_NOQUOTE" ]; then
    fail "发现无引号的密码赋值："
    printf '%s\n' "$HITS_NOQUOTE" | sed 's/^/      /'
else
    pass "无硬编码密码（无引号）"
fi

scan_content "无 GitHub 令牌" \
    'github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,}' \
    ''

scan_content "无云服务密钥" \
    'AKIA[0-9A-Z]{16}|sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{30,}' \
    ''

scan_content "无通用密钥字段赋值" \
    '(api[_-]?key|secret|access[_-]?token|auth[_-]?token)\s*[:=]\s*["'"'"'][^"'"'"']{8,}' \
    "$PLACEHOLDER"

# ===========================================================================
head_ "[3/5] 本机路径与内网地址"
# ===========================================================================
# ⚠️ 注意：`/data/local/tmp/`、`/sdcard/`、`/storage/` 是 **Android 通用路径**
# （任何设备都一样），不泄露「本机环境结构」—— 因此**不在**拦截范围。
#
# 它们曾误伤过（2026-10-14）：`tools/install_debug.sh` 与协作文档 §5.17
# 提到 `/data/local/tmp/` 就被这条规则拦下，而同一份脚本在 [4/5] 里
# 又把 `/data/local/tmp/` 列为**白名单** —— 自相矛盾。
scan_content "无类 Unix 本机绝对路径" \
    '(/root/|/home/[a-z]|/Users/[A-Za-z]|/data/user/0/)' \
    '示例|example|README|LICENSE'

scan_content "无 Windows 本机路径" \
    '[A-Za-z]:\\\\+[Uu]sers\\\\+' \
    ''

scan_content "无内网 IP" \
    '\b(10\.[0-9]{1,3}|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.)[0-9]{1,3}\.' \
    ''

# ===========================================================================
head_ "[4/5] 构建配置可移植性"
# ===========================================================================
# gradle wrapper 若指向本地文件，他人 clone 后无法构建
if [ -f gradle/wrapper/gradle-wrapper.properties ]; then
    if grep -qE '^distributionUrl=file:' gradle/wrapper/gradle-wrapper.properties; then
        fail "gradle-wrapper.properties 指向本地文件，他人无法构建"
        grep -n '^distributionUrl' gradle/wrapper/gradle-wrapper.properties | sed 's/^/      /'
    else
        pass "gradle wrapper 分发地址可移植"
    fi
else
    info "未找到 gradle-wrapper.properties，跳过"
fi

# 构建脚本里不应出现写死的 SDK / 密钥路径
# 白名单：SDK 常见安装位置（用于探测而非写死本机路径）、Android 设备上的公用目录
SAFE_ABS='apksigner|build-tools|/opt/android-sdk|/usr/lib/android-sdk|/sdcard/|/storage/|/data/local/tmp/|示例|#'
if [ "$MODE" = "staged" ]; then
    SCRIPT_HITS=$(git grep --cached -nIE '"/[a-z]+/[^"]*"|'"'"'/[a-z]+/[^'"'"']*'"'"'' -- '*.py' '*.sh' 2>/dev/null \
        | grep -vE "$SAFE_ABS" || true)
else
    SCRIPT_HITS=$(git grep -nIE '"/[a-z]+/[^"]*"|'"'"'/[a-z]+/[^'"'"']*'"'"'' -- '*.py' '*.sh' 2>/dev/null \
        | grep -vE "$SAFE_ABS" || true)
fi
if [ -n "$SCRIPT_HITS" ]; then
    fail "构建脚本中存在绝对路径字面量"
    printf '%s\n' "$SCRIPT_HITS" | head -8 | sed 's/^/      /'
else
    pass "构建脚本无绝对路径字面量"
fi

# ===========================================================================
head_ "[5/5] 忽略规则完整性"
# ===========================================================================
if [ -f .gitignore ]; then
    MISSING=""
    for rule in 'keystore' 'local\.properties' '\*\.jks' '\*\.apk' 'dist'; do
        grep -qE "$rule" .gitignore || MISSING="$MISSING $rule"
    done
    if [ -n "$MISSING" ]; then
        fail ".gitignore 缺少规则:$MISSING"
    else
        pass ".gitignore 关键规则齐全"
    fi
else
    fail "缺少 .gitignore"
fi

# 行尾规范文件：仓库横跨 Linux（手机 proot）与 Windows（Git-Bash），
# 没有它时两边各按各的 core.autocrlf 处理，会产生整库假改动。
# 2026-10-16 才补上，所以这里钉一条检查防止它再消失。
if [ -f .gitattributes ]; then
    if grep -qE '^\*[[:space:]]+text=auto' .gitattributes; then
        pass ".gitattributes 存在且设置了默认行尾规则"
    else
        fail ".gitattributes 缺少「* text=auto」默认行尾规则"
    fi
else
    fail "缺少 .gitattributes（行尾会在 Windows/Linux 之间抖动）"
fi

# ===========================================================================
printf "\n${B}══════════════════════════════════════════${N}\n"
if [ "$ISSUES" -eq 0 ]; then
    printf "${G}${B}  ✓ 全部通过，可以推送${N}\n"
else
    printf "${R}${B}  ✗ 发现 %s 项问题，请处理后重试${N}\n" "$ISSUES"
fi
printf "${B}══════════════════════════════════════════${N}\n"

exit $([ "$ISSUES" -eq 0 ] && echo 0 || echo 1)

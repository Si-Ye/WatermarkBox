#!/bin/bash
# ============================================================
# 敏感信息审计规则的回归测试
# ============================================================
#
# ## 为什么需要这个测试
#
# `pre_push_audit.sh` 的密码检测是**基于正则 + 多层过滤**的，
# 改动其中任何一层都可能：
#   - 让该拦的漏过（假阴性）—— 密码进 git 历史，代价极大
#   - 让不该拦的报错（假阳性）—— 正常代码提交不了，体验差
#
# 而这两类错误都**不会自己暴露**：假阴性表现为「检查通过」（看起来没事），
# 假阳性表现为「莫名其妙被拦」（用户可能直接 --no-verify 绕过）。
#
# 所以用一组固定场景把当前行为**钉死**，改完规则跑一遍就知道有没有破坏。
#
# ## 已知踩坑（这些场景就是为此加的）
#
# 1. `grep -vFiE` 是非法组合（-F 与 -E 互斥），会报
#    "conflicting matchers specified" 并滤掉全部输入
#    → 检查永远"通过"。**本测试的「应拦截」用例专门防这个回归。**
# 2. CI 里的 `printf '...storePassword=%s...'` 是正常写法，
#    不该被误报 → **「printf 占位符」用例专门防这个。**
#
# 用法：
#
#     bash tools/test_audit_rules.sh
#
# 退出码 0 = 全部符合预期；非 0 = 有不符预期的场景。
# ============================================================

set -u

ROOT=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "错误：当前目录不是 git 仓库。" >&2
    exit 1
}
cd "$ROOT" || exit 1

AUDIT="tools/pre_push_audit.sh"
TMP="tools/.audittest.tmp"
FAILED=0
TOTAL=0

cleanup() {
    git reset -q HEAD "$TMP" 2>/dev/null
    rm -f "$TMP"
}
trap cleanup EXIT

# 用法: expect <说明> <文件内容> <BLOCK|ALLOW>
expect() {
    local label="$1" content="$2" want="$3"
    TOTAL=$((TOTAL + 1))

    printf '%s\n' "$content" > "$TMP"
    git add -f "$TMP"

    # 用审计脚本的**退出码**判断：非 0 表示发现问题（即「拦截」）
    #
    # 不用 grep 匹配输出文字 —— 文案可能改，而退出码是稳定契约。
    bash "$AUDIT" --staged > /dev/null 2>&1
    if [ $? -ne 0 ]; then got="BLOCK"; else got="ALLOW"; fi

    if [ "$got" = "$want" ]; then
        printf '  ✓ %s → %s\n' "$label" "$got"
    else
        printf '  ✗ %s → %s（预期 %s）\n' "$label" "$got" "$want"
        FAILED=$((FAILED + 1))
    fi

    git reset -q HEAD "$TMP" 2>/dev/null
    rm -f "$TMP"
}

# ------------------------------------------------------------------
# 测试数据构造
# ------------------------------------------------------------------
#
# ## 为什么不能直接写 `storePassword=真密码` 这样的字面量
#
# **审计脚本会把自己拦下来** —— 它扫的是暂存区内容，
# 而本文件里恰好有大量「看起来像密码赋值」的示例字符串。
#
# 这是设计使然（宁可误报也不能漏报），所以正确做法是**让测试数据
# 在运行时才拼出来**：源码里只有片段，没有完整字面量。
#
# 顺带好处：不需要给任何文件开白名单 —— 白名单一旦存在，
# 就成了绕过检查的口子。
#
# 各片段用独立的变量名，避免拼接后仍被规则命中。
P1='store'; P2='Password'
Q1='key';   Q2='Password'
K1='KS';    K2='_PASS'

# 真值（故意用明显的假值，但"形如真密码"以便触发规则）
V_SECRET='SuperSecret123'
V_ANOTHER='AnotherPass99'
V_MYSECRET='MySecretPass'
V_REAL='RealPassword123'
V_TOP='topsecretvalue'

echo "======== 审计规则回归测试 ========"
echo
echo "【必须拦截】—— 漏掉 = 密码可能进 git 历史"
expect "无引号真密码"              "$P1$P2=$V_SECRET"                  BLOCK
expect "无引号真密码（keyPassword）" "$Q1$Q2=$V_ANOTHER"                 BLOCK
expect "等号两侧带空格"            "$P1$P2 = $V_MYSECRET"               BLOCK
expect "带引号真密码"              "$P1$P2=\"$V_REAL\""                 BLOCK
expect "KS_PASS 变量名"            "$K1$K2=$V_TOP"                      BLOCK

echo
echo "【必须放行】—— 误报 = 正常代码提交不了"
expect "空值"                      "$P1$P2="                            ALLOW
expect "占位符 ****"               "$P1$P2=****"                        ALLOW
expect "占位符 CHANGE_ME"          "$P1$P2=CHANGE_ME"                   ALLOW
expect "printf 占位符 %s（CI 用法）" "$P1$P2=%s"                         ALLOW
expect "shell 变量 \$PW"            "$P1$P2="'$PW'                       ALLOW
expect "shell 变量 \${PW}"          "$P1$P2="'${PW}'                     ALLOW
expect "Kotlin getProperty 读取"    "$P1$P2 = props.getProperty(\"$P1$P2\")" ALLOW
expect "无关代码"                  'val x = 1'                          ALLOW

echo
echo "=================================="
if [ "$FAILED" -eq 0 ]; then
    echo "全部通过（$TOTAL 个场景）✓"
    exit 0
else
    echo "有 $FAILED 个场景不符合预期（共 $TOTAL 个）✗"
    exit 1
fi
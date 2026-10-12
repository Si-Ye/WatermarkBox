#!/system/bin/sh
# ============================================================================
# ensure_nomedia.sh —— 确保项目相关目录都有 .nomedia（让相册看不到它们）
# ============================================================================
#
# ## 为什么需要这个脚本
#
# 本项目相关目录有两处需要 .nomedia 保护，其中 `builds/` 那份容易丢：
#
#   1. `builds/WatermarkBox/` 在 git 仓库外 —— 不会随 clone 自动获得
#   2. 它可能被整体删除重建（比如你清理产物）
#
# 一旦丢失，几十张截图/诊断图就会重新出现在相册里。这脚本用来兜底。
#
# 另外还管一个**跟本项目无关**的目录：
#
#   3. `Download/skill_downloads/` —— Operit 的 skill 缓存。
#      它的内容由 Operit 应用自己读写，可能被应用清理时连带删掉。
#      放在这里只是「顺手一起维护」，不代表它属于本项目。
#
# ## 用法
#
#   sh tools/ensure_nomedia.sh                   # 只检查（默认）
#   sh tools/ensure_nomedia.sh --fix             # 缺了就补上
#   sh tools/ensure_nomedia.sh --fix --rescan    # 补完后触发媒体重扫
#
# ## 退出码
#
#   0 = 全部就绪（或已修复完成）
#   1 = 有缺失且未修复（未传 --fix）
#   2 = 参数错误
#
# ## 运行环境
#
# `--rescan` 需要 Android 的 `content` 命令（Shizuku / adb / root shell），
# 在 Ubuntu proot 终端里没有它 —— 脚本会检测并降级提示，不算失败。
#
# ============================================================================

# 注意：不用 set -e（有 `cmd || true` 这类写法时容易误退出）
#       也不用 set -u（$1 未定义时会报错；这里用 for 遍历，本就不需要）

# ---- 配置：需要保护的目标目录 ----
# 前两个属于水印盒项目；第三个是本机环境（Operit 的 skill 缓存）。
TARGET_1="/storage/emulated/0/OperitAI/WatermarkBox"
TARGET_2="/storage/emulated/0/OperitAI/builds/WatermarkBox"
TARGET_3="/storage/emulated/0/Download/skill_downloads"

DO_FIX=0
DO_RESCAN=0

# ---- 参数解析 ----
for arg in "$@"; do
    case "$arg" in
        --fix)    DO_FIX=1 ;;
        --rescan) DO_RESCAN=1 ;;
        --help|-h)
            echo "用法: sh tools/ensure_nomedia.sh [--fix] [--rescan]"
            echo ""
            echo "  --fix      缺少 .nomedia 时自动创建"
            echo "  --rescan   补完后触发媒体重扫（需要 Android shell）"
            echo ""
            echo "不带参数时只做检查，发现缺失会以退出码 1 结束。"
            exit 0
            ;;
        *)
            echo "未知参数: $arg（用 --help 查看用法）" >&2
            exit 2
            ;;
    esac
done

# ---- 检查/修复单个目录 ----
# 封装成函数，避免重复代码；也避开了 IFS 切分带来的各种坑
# （此前用 `for line in $TARGETS` + 换行 IFS，在部分 shell 下
#   换行被当成字面量 `\n`，导致循环一次都不执行 —— 已废弃该写法）
check_one() {
    _dir="$1"
    _desc="$2"

    if [ ! -d "$_dir" ]; then
        echo "  [跳过] $_dir"
        echo "         （目录不存在：$_desc）"
        return 0
    fi

    if [ -f "$_dir/.nomedia" ]; then
        echo "  [✓] $_dir"
        return 0
    fi

    echo "  [✗] $_dir —— 缺少 .nomedia"
    echo "         $_desc"
    MISSING=$((MISSING + 1))

    if [ "$DO_FIX" = "1" ]; then
        # 用 printf 而非 heredoc —— 更兼容 toybox sh
        if printf '%s\n' \
            "# 让 Android 媒体扫描器忽略本目录（及其所有子目录）" \
            "# 由 tools/ensure_nomedia.sh 生成/确认。" \
            "# 作用：相册看不到本目录的图片；文件本身仍在磁盘上。" \
            "# 注意：只是隐藏，不提供安全保护。" \
            > "$_dir/.nomedia"; then
            echo "         → 已创建 ✓"
            FIXED=$((FIXED + 1))
        else
            echo "         → ✗ 创建失败（权限问题？）"
        fi
    else
        echo "         → 用 --fix 可自动创建"
    fi
    return 0
}

echo "══════════════════════════════════════════════"
echo "  .nomedia 自检"
echo "══════════════════════════════════════════════"

MISSING=0
FIXED=0

check_one "$TARGET_1" "源码工作区（这份在 git 里，clone 自带）"
check_one "$TARGET_2" "构建产物与临时文件"
check_one "$TARGET_3" "Operit 的 skill 缓存（不属于本项目，由 Operit 应用读写）"

echo ""
if [ "$MISSING" = "0" ]; then
    echo "  ✓ 全部就绪"
elif [ "$DO_FIX" = "1" ]; then
    echo "  已修复 $FIXED 处"
else
    echo "  发现 $MISSING 处缺失（用 --fix 修复）"
fi

# ---- 触发媒体重扫 ----
if [ "$DO_RESCAN" = "1" ]; then
    echo ""
    echo "── 触发媒体重扫 ──"

    if ! command -v content >/dev/null 2>&1; then
        echo "  ⚠️ 当前环境没有 content 命令（可能是 Ubuntu 终端）"
        echo "     → 请在 Android shell 里运行，或手动执行："
        echo "       content call --uri content://media --method scan_volume --arg external_primary"
    elif content call --uri content://media --method scan_volume \
            --arg external_primary >/dev/null 2>&1; then
        echo "  已触发（等待索引更新…）"
        sleep 5
    else
        echo "  ⚠️ scan_volume 调用失败，可重启手机或等系统自行重扫"
    fi

    echo ""
    echo "── 验证结果 ──"
    if command -v content >/dev/null 2>&1; then
        # OperitAI/（项目相关两处）
        for t in images audio video; do
            n=$(content query --uri content://media/external/$t/media \
                    --projection _data 2>/dev/null \
                  | grep -c 'OperitAI/' 2>/dev/null || true)
            echo "  $t 中 OperitAI/ 的记录: $n"
        done
        # skill_downloads/（同属自检范围）
        for t in images audio video; do
            n=$(content query --uri content://media/external/$t/media \
                    --projection _data 2>/dev/null \
                  | grep -c 'Download/skill_downloads/' 2>/dev/null || true)
            echo "  $t 中 skill_downloads/ 的记录: $n"
        done
        echo "  （全都应为 0）"
    else
        echo "  跳过（需要 content 命令）"
    fi
fi

echo ""
echo "══════════════════════════════════════════════"
echo "  完成"
echo "══════════════════════════════════════════════"

# ---- 退出码 ----
if [ "$MISSING" != "0" ] && [ "$DO_FIX" != "1" ]; then
    exit 1
fi
exit 0
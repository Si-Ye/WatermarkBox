#!/bin/sh
# ============================================================
# 装机：把 debug APK 装到设备（**分两段执行**）
# ============================================================
#
# ## 为什么需要脚本
#
# 装机看着就一步 `pm install`，实际有三个坑（都踩过）：
#
# 1. **`pm install` 读不了 /sdcard**（SELinux：`system_server` 无权读 FUSE 挂载）
#    → 必须先把 APK 放到 `/data/local/tmp/`
# 2. **proot 访问不了 `/data/local/tmp`**（Permission denied）
#    → 「复制到 tmp」这步要用 shell（Shizuku / Root），**不能**在本脚本里做
# 3. **包名必须对**（`com.watermarkbox.final`）—— 装错包名 = 桌面上多一个图标
#
# 所以本脚本做「proot 能做的部分」（校验包名 + 归档 + 算出 md5），
# 然后把**剩余命令**打出来，交给 shell 工具执行。
#
# ## 用法
#
#     sh tools/install_debug.sh                    # 用默认产物
#     sh tools/install_debug.sh path/to/app.apk    # 指定 APK
#
# ## 详见
#
# docs/multi-session-collaboration.md §5.17
# ============================================================

set -u

# ─────────────────────────────────────────────────────────────
# 平台守卫（「划界支持」，见 docs/multi-session-collaboration.md §2.7）
# 本脚本属「不支持面」：只在 Linux 下可用，且需要 $ANDROID_HOME 下有
# build-tools（找 aapt2 校验包名）并连着一台设备。
# 原因：脚本会 mkdir/cp 到 /storage/emulated/0/...（Windows 上会被当成
#       相对路径，在工作区里造出垃圾目录），并用 pm install 装机。
# 其他平台请走替代路径，见同一节的「不支持面」表。
# 确需强跑：WMB_FORCE_UNSUPPORTED=1 sh 本脚本
# ─────────────────────────────────────────────────────────────
if [ "${WMB_FORCE_UNSUPPORTED:-0}" != "1" ]; then
    case "$(uname -s)" in
        Linux) ;;
        *)
            printf '%s\n' "✗ 本脚本不支持当前平台（$(uname -s)）。" >&2
            printf '%s\n' "  替代路径见 docs/multi-session-collaboration.md §2.7「不支持面」。" >&2
            exit 1
            ;;
    esac
fi

ROOT=$(git rev-parse --show-toplevel 2>/dev/null)
if [ -z "$ROOT" ]; then
    echo "错误：当前目录不是 git 仓库。" >&2
    exit 1
fi
cd "$ROOT" || exit 1

APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [ ! -f "$APK" ]; then
    echo "错误：找不到 APK：$APK" >&2
    echo "先构建：sh gradlew :app:assembleDebug -PparallelSuffix=.final" >&2
    exit 1
fi

# ---- ① 包名校验（装错包名 = 桌面多一个图标，会被明确批评）----
# ANDROID_HOME 由环境提供 —— **不写死本机 SDK 路径**：
# 写死会被 pre-commit 拦（它确实是"本机特有"信息），且换台机器就失效。
# 没设就跳过包名校验（不阻塞装机流程）。
AAPT2=""
if [ -n "${ANDROID_HOME:-}" ]; then
    AAPT2=$(find "$ANDROID_HOME/build-tools" -name aapt2 2>/dev/null | head -1)
fi
if [ -n "$AAPT2" ]; then
    PKG=$("$AAPT2" dump badging "$APK" 2>/dev/null | head -1 | sed -n "s/^package: name='\([^']*\)'.*/\1/p")
    echo "包名：$PKG"
    if [ "$PKG" != "com.watermarkbox.final" ]; then
        echo "⚠️ 包名不是 com.watermarkbox.final —— 装上去会是**另一个应用**（桌面多一个图标）。" >&2
        echo "   请确认构建时带了 -PparallelSuffix=.final" >&2
        exit 1
    fi
    echo "✅ 包名正确"
else
    echo "（未找到 aapt2，跳过包名校验 —— 请自行确认是 com.watermarkbox.final）"
fi

# ---- ② 归档到 /sdcard（便于 shell 读取）----
ARCHIVE_DIR="/storage/emulated/0/OperitAI/builds/WatermarkBox"
mkdir -p "$ARCHIVE_DIR"
DEST="$ARCHIVE_DIR/$(basename "$APK")"
cp "$APK" "$DEST" || { echo "错误：归档失败" >&2; exit 1; }
MD5=$(md5sum "$APK" | awk '{print $1}')
echo "已归档：$DEST"
echo "md5：$MD5"

# ---- ③ 打印待执行的 shell 命令 ----
echo ""
echo "═══════════════════════════════════════════════════"
echo " 接下来：在 shell（Shizuku / Root）里执行下面这几条"
echo "═══════════════════════════════════════════════════"
echo ""
echo "# 复制到 /data/local/tmp（system_server 能读的位置）"
echo "cp /sdcard/OperitAI/builds/WatermarkBox/$(basename "$APK") /data/local/tmp/wmb.apk"
echo ""
echo "# 安装"
echo "pm install -r -d --user 0 /data/local/tmp/wmb.apk"
echo ""
echo "# 核实装的是这一版（md5 应等于 $MD5）"
echo "APKPATH=\$(pm path com.watermarkbox.final | head -1 | sed 's/package://')"
echo "md5sum \"\$APKPATH\""
echo ""
echo "⚠️ 别在 proot 里执行上面几条 —— proot 访问不了 /data/local/tmp。"
echo "详见 docs/multi-session-collaboration.md §5.17"

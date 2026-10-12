#!/system/bin/sh
# ---------------------------------------------------------------------------
# 分批运行仪器化测试（规避本 ROM 的 instrumentation 会话限制）
#
# ## 为什么需要分批
#
# 实测：`am instrument` 一次性跑 9 个测试时，前 3 个通过后进程会被
# ActivityManager 以 `stop com.watermarkbox due to finished inst` 强杀
# （日志伴随 `Unable to freeze binder: -11`）。
#
# 这不是 OOM，也不是测试失败 —— 是 MIUI 的进程冻结机制与
# instrumentation 生命周期冲突。**一个测试一个独立会话**则稳定。
#
# ## 为什么需要重试
#
# 即使每次只跑一个测试，仍有概率遇到「会话未建立」（`am instrument`
# 输出为空）—— 因为上一个会话在 AMS 里的记录还没完全释放。
# 实测：**进程消失后还要再等一会儿**才可靠。
#
# 本脚本的做法（刻意保持简单）：
#   1. 等进程消失
#   2. **固定冷却 2 秒**（不依赖任何 dumpsys 探测 —— 试过，判断条件
#      容易写错反而更慢）
#   3. 失败则**最多重试 3 次**，每次重试前再等 3 秒
#
# ## 执行环境
#
# `am` / `pm` 只存在于 **Android shell**；Ubuntu/proot 终端没有。
# Android shell 也无 `bash`，故本脚本是 POSIX sh 兼容（无数组、无 local）。
#
# ## 用法
#
#   # 跑全部（可能因系统回收进程而中断，建议分批）
#   sh tools/run_instrumented_tests.sh
#
#   # 只跑指定项（可传多个，空格分隔）—— 推荐用法
#   sh tools/run_instrumented_tests.sh ClassA#m1 ClassB#m2 ...
#
#   # 冒烟：跑第一个
#   sh tools/run_instrumented_tests.sh --quick
#
# 结果建议重定向到文件，避免长输出被截断：
#   sh ... > wmb_test_out.txt 2>&1
# ---------------------------------------------------------------------------

TEST_PKG="com.watermarkbox.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"

# 换行分隔的列表（POSIX sh 没有数组）
METHODS="
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#embed_then_extract_recovers_text
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#survives_jpeg_quality_90
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#survives_jpeg_quality_80
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#survives_two_rounds_of_jpeg
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#survives_png_round_trip
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#clean_image_reports_no_watermark
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#different_strength_engines_can_still_embed
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#longer_text_is_recoverable
com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#embed_does_not_visibly_change_pixels
com.watermarkbox.util.ExifChineseEncodingTest#chinese_survives_round_trip_through_real_exif
com.watermarkbox.util.ExifChineseEncodingTest#without_codec_chinese_would_break
com.watermarkbox.watermark.WatermarkRendererInstrumentedTest
com.watermarkbox.watermark.ImageWatermarkRendererTest
com.watermarkbox.watermark.PreviewDerivedReactivityTest
com.watermarkbox.watermark.RenderPerformanceTest
com.watermarkbox.ui.component.PreviewFitMeasurementTest
"

if [ "${1:-}" = "--quick" ]; then
  METHODS="com.watermarkbox.invisible.InvisibleWatermarkInstrumentedTest#embed_then_extract_recovers_text"
elif [ $# -gt 0 ]; then
  # 命令行显式指定了目标 → 用它们
  METHODS=""
  for a in "$@"; do
    METHODS="$METHODS
$a"
  done
fi

PASS=0
FAIL=0
SKIP=0

# 等进程退出 + 固定冷却
settle() {
  i=0
  while [ $i -lt 20 ]; do
    pidof com.watermarkbox >/dev/null 2>&1 || break
    sleep 0.5
    i=$((i + 1))
  done
  pidof com.watermarkbox >/dev/null 2>&1 && am force-stop com.watermarkbox >/dev/null 2>&1
  sleep 2
  return 0
}

echo "=============================================="
echo " WatermarkBox - instrumented tests (batched)"
echo "=============================================="
echo ""

printf '%s\n' "$METHODS" | while IFS= read -r target; do
  [ -z "$target" ] && continue
  short=$(printf '%s' "$target" | sed 's/.*[#.]//')

  attempt=0
  done_flag=0
  while [ $attempt -lt 4 ]; do
    settle
    out=$(am instrument -w -e class "$target" "$TEST_PKG/$RUNNER" 2>&1)

    if printf '%s' "$out" | grep -q "OK ("; then
      n=$(printf '%s' "$out" | grep -oE "OK \([0-9]+" | grep -oE "[0-9]+" | head -1)
      echo "  PASS  $short  (${n:-?} tests)"
      done_flag=1
      break
    elif printf '%s' "$out" | grep -q "FAILURES"; then
      echo "  FAIL  $short  -- assertion failed"
      printf '%s' "$out" | grep -E "^[0-9]+\) " | head -3 | sed 's/^/        /'
      done_flag=2
      break
    fi

    attempt=$((attempt + 1))
    [ $attempt -lt 4 ] && echo "  retry #$attempt $short ..." && sleep 3
  done

  [ $done_flag -eq 0 ] && echo "  SKIP  $short  -- session not established (3 retries)"
done

echo ""
echo "=============================================="
echo " done (see PASS/FAIL/SKIP lines above)"
echo "=============================================="
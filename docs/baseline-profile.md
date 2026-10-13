# Baseline Profile（启动优化）现状与做法

> 结论先行：**包里已有库的 profile，但应用自身的代码覆盖率是 0**。
> 想真正优化启动，只有 Macrobenchmark 生成这一条路 ——
> 而它需要往设备安装 release 包，当前被系统拦着（见文末）。

---

## 1. 包里现在有什么（实测）

release APK 的 `assets/dexopt/` 下**已经**带着 profile：

| 文件 | 大小 | 来源 |
|---|---|---|
| `baseline.prof` | 约 6.7 KB | AGP 自动合并各 AndroidX AAR 自带的 baseline profile |
| `baseline.profm` | 约 0.7 KB | 同上 |

也就是说：**Compose 运行时、Navigation、Lifecycle 等库的热路径已经是 AOT 编译的**。
这部分不需要我们操心。

## 2. 应用自己的代码：覆盖率 0（实测）

用 R8 的 mapping 表反查混淆名后统计（脚本：`tools/check_profile_coverage.py`）：

```
本应用类总数     : 366
profile 总行数   : 13798
命中本应用的行数 : 0
命中本应用的类数 : 0
```

原因：**从来没有人提供过「应用自身的 profile」**。
库的 profile 只管库，我们的 `MainActivity` / 导航 / 首页这一串
仍然是首次运行时靠 JIT 现场编译 —— 这正是「刚开始有点卡、用过就顺了」的体感来源。

## 3. 试过但**没用**的路子：手写通配符种子

曾经在 `app/src/main/baseline-prof.txt` 手写：

```
HSPLcom/watermarkbox/MainActivity;->**(**)
HSPLcom/watermarkbox/ui/navigation/**->**(**)
...
```

实测**不起作用**：

- AGP 确实把它合并进了 merged profile（`merged_art_profile/.../baseline-prof.txt` 里能看到这 9 行）
- 但在 R8 重写 / 通配展开之后，**最终 profile 里本应用规则 0 条**
- AGP 9.0.0 内部有 `ExpandArtProfileWildcardsTask`（通配符展开能力存在），
  但翻过 AGP jar，**应用侧没有公开的 Gradle 属性**可以打开它

结论：这条路在当前 AGP 版本下走不通，该文件已删除 ——
留着一个「看起来在优化、实际是空操作」的配置，比没有更糟。

> ⚠️ 教训：加完 profile 一定要**验证覆盖率**，不能只看文件在不在。

## 4. 正确做法（等条件具备时）

1. 新增 `benchmark` 模块（Macrobenchmark）：

```kotlin
// benchmark/src/main/java/.../BaselineProfileGenerator.kt
@OptIn(ExperimentalBaselineProfilesApi::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.watermarkbox") {
        startActivityAndWait()
        // 再把主要路径各走一遍，profile 才「像真实用户」
        // 例如：进编辑页 → 拖一下参数面板 → 返回
    }
}
```

2. 跑生成任务（会安装 profileable 包并测量）：

```
./gradlew :benchmark:generateBaselineProfile
```

3. 生成物拷到 `app/src/main/baseline-prof.txt`
4. **验证覆盖率**：

```
./gradlew :app:assembleRelease
python3 tools/check_profile_coverage.py     # 「命中本应用的类数」必须非 0
```

5. 用 `StartupBenchmark` 对比前后（`StartupTimingMetric`），确认真的有收益再提交。

## 5. 现在为什么没做

Macrobenchmark 必须往设备上**安装 release / profileable 包**。
本机是 MIUI，`pm install` 被系统拒绝：

```
INSTALL_FAILED_USER_RESTRICTED: Install canceled by user
```

需要在开发者选项里打开「USB 安装」（或手动点安装）。
**这条限制同时也是「release 版对比实测」的阻塞点** ——
两者都需要先能装 release 包。

## 6. 顺带一提：debug 版不受益

baseline profile 只对**安装后由 dexopt 处理的 release 包**生效。
日常开发用的 debug 包不走这套流程 ——
所以「平时测试觉得卡」首先要确认测的是哪个包：

| 包 | dex 个数 | 体积 | 说明 |
|---|---|---|---|
| debug（`.test`） | **16 个 dex** | 22.4 MB | 不混淆、每个类首次使用才 JIT/验证 |
| release（R8） | **1 个 dex** | 2.6 MB | 直接 AOT |

debug 版的「首屏发涩」主要是这个差异造成的，不是代码问题。

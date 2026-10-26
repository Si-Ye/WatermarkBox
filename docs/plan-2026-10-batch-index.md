# 下一版计划 · 批量序号与失败重跑（2026-10）

> 状态：**已实现，待老大实机验收**（8 步已全部走完；实机手点验收仍归老大，见 §5）
> 范围：**只做开发，不发版** —— 不升 `versionCode` / `versionName`，不打 tag，不 push。
> 本文档是实施契约：改动点、数据契约、测试与验收都写死在这里，实施中若偏离要回来改它（偏离见 §8）。
>
> **实施结果（2026-10-26）**：单元测试 505 例全绿（本版新增 31 例）；
> `assembleDebug` + `lintDebug` 通过（lint 0 error，5 warning）；
> MuMu（Android 15, x86_64）上仪器化测试 **57/57 通过**，APK 安装后冷启动无崩溃。
> ⚠️ `MediaProvider` 同名冲突（§6.1）仍是**待实机确认**项，见 §5。

---

## 1. 已锁定的需求（来自 8 轮问答）

| # | 需求 | 明确结论 |
|---|---|---|
| R1 | 序号占位符语法 | 沿用现有 `{index}`，**扩位数为 `{index:04}`**（冒号后是位数，不写则不补零） |
| R2 | 位置与位数由谁决定 | **都由占位符本身决定**（`photo{index:04}_压缩` 与 `{index:03}` 都能用） |
| R3 | 设置页的「序号」开关 | **不是独立开关**，它只负责**插入 / 移除**该占位符；开关状态由模板里有没有占位符反推 |
| R4 | 实时预览 | 设置页输入框下方即时显示示例结果，**单张与批量各一行** |
| R5 | 起始号 | 设置里可填，**每批从它开始**（不记忆上次跑到几号） |
| R6 | 失败重跑的编号 | **只重跑失败项，序号与第一批完全一致**（不重号、不重新编号） |
| R7 | 单张导出时 `{index}` | 现在是**静默变空**（`照片{index}` → `照片`，`(index)` → `()`），要修 |
| R8 | 批量导出后分享 | 结果页直接分享这批成功的图（`ACTION_SEND_MULTIPLE`） |

**非目标（本版明确不做）**：网格选择、导出后日记/相册增强、滚动长截图、内置字体。它们各自是独立迭代，不塞进本版。

---

## 2. 现状事实（决定改法的依据，勿凭印象）

1. `app/src/main/java/com/watermarkbox/util/FileNameResolver.kt:25` 只有精确常量 `KEY_INDEX = "{index}"`；替换在 `:67-69`，**`index == null` 时替换为空串**（这就是 R7 的病灶）；`:74` 已有 `trim('.', '_', '-')`，能兜住**尾部分隔符**，但兜不住 `()`、`__`、`-_` 这类中段残留。
2. `app/src/main/java/com/watermarkbox/data/model/WatermarkPreset.kt:45` `fileNameTemplate` 与 `:41` `ExportSettings` 都是 `@Serializable`；`PresetFileCodec` 与 `SettingsRepository` 均已 `ignoreUnknownKeys = true` → **给 `ExportSettings` 加字段对旧预设安全**。
3. `app/src/main/java/com/watermarkbox/worker/BatchWatermarkWorker.kt:53` 输入 `KEY_URIS: StringArray`；`:118-125` 输出 `KEY_FAILED_URIS`（失败 uri）与四个原因计数。**序号来自 `forEachIndexed` 的下标，不是数据** → 想「保号重跑」必须让序号变成**输入数据**。
4. `app/src/main/java/com/watermarkbox/ui/screen/batch/BatchViewModel.kt` 的 UiState **没有保存失败 uri 清单**（只有 `failureReasons` 计数）。Worker 明明输出了 `KEY_FAILED_URIS`，UI 层没收 → R6 必须先补这条链。
5. `BatchConfigScreen.kt:785` 起是结果摘要（`success` / `failureReasons`），分享按钮与「重试失败项」都该加在这里。
6. `ExportRepository.saveBitmap(...)` 返回 `Uri?`（`contentResolver.insert`，见 `:99`）→ 成功 uri 天然拿得到，不用改签名。
7. **WorkManager `Data` 有 10KB 上限**。100 张图的 uri 清单 ≈ 8KB，加失败清单必爆 → 清单**不能走 Data**。

---

## 3. 逐文件改动

### 3.1 `util/FileNameResolver.kt`（核心，纯函数，无 Android 依赖）

```kotlin
private val INDEX_REGEX = Regex("""\{index(?::(\d{1,6}))?\}""")

fun build(template, sourceName, format, index: Int? = null, indexStart: Int = 1, now: Date)
```

- `index == null`（单张）：**整段删除**占位符，再 `collapseSeparators()` 收敛删除留下的 `()`、`__`、`-_`、`._`。
- `index != null`：位数 `N = 组1?.toInt()`；`N == null` → 直接 `toString()`；否则 `toString().padStart(N, '0')`（**超过 N 位不截断**，宁可 5 位也不能丢号）。
- `hasPlaceholder()` 改成正则判断（否则 `{index:04}` 会被判成「无占位符」，设置页校验会误报）。
- 新增 `fun indexPlaceholder(digits: Int?): String` 供设置页开关与位数选择复用，避免 UI 里手写字符串。
- `indexStart`：**不在这里用**。起始号是批量的概念，由调用方把 `index = start + i` 算好传进来 —— 纯函数只认「这一张是几号」，不认「这批从几号开始」。

### 3.2 `data/model/WatermarkPreset.kt` · `ExportSettings`

```kotlin
val indexStart: Int = 1,   // 批量序号起始；1 起，兼容「没这个字段的老预设」
```

放 `ExportSettings` 而不是全局设置，理由：它是「导出行为」，跟 `fileNameTemplate` 必须一起保存/一起分享，拆开会出现「模板里有序号、起始号却不是这台的」这种错位。

### 3.3 `ui/screen/settings/` 导出分类（`SettingsExportCategory.kt`）

- 模板输入框下加一行 **「序号」开关**：
  - 开：`clickable` 里对模板做 `if (hasIndex) template else template + "_" + indexPlaceholder(digits)`；
  - 关：正则移除后 `collapseSeparators()`。
  - **状态完全由 `hasIndex(template)` 反推**，不存第二份布尔值（两份状态必然不同步）。
- 开关右侧「位数」下拉（1–6，默认 4）：改动时**替换原位置**的占位符，不动前后分隔符。
- 下方两行预览（用 `remember(template, format, indexStart) { System.currentTimeMillis() }` **冻结时间戳**，否则 `{date}`/`{time}` 每秒跳）：
  - 批量：`build(template, "IMG_2041", format, index = indexStart, now = frozen)` → `IMG_2041_0001.jpg`
  - 单张：`build(template, "IMG_2041", format, index = null, now = frozen)` → `IMG_2041.jpg`
- 「起始号」数字输入（1–9999），仅在开关打开时显示。

### 3.4 `worker/BatchWatermarkWorker.kt` + 新增 `data/repository/BatchRunStore.kt`

**输入新增** `KEY_INDICES: IntArray`（与 `KEY_URIS` 等长；缺省则按 `1..n` 兼容老调用）。**输出只放计数与 `requestId`**，清单落盘。

`BatchRunStore`（应用私有目录 JSON，按 requestId 命名，`Json { ignoreUnknownKeys = true }`）：

```json
{ "success": ["content://..."], "failed": [{"uri": "content://...", "index": 3, "reason": "SOURCE_TOO_LARGE"}] }
```

- 为什么落盘而不是塞 Data：见 §2-7 的 10KB 上限。
- 成功清单顺手喂 R8 分享；失败清单顺手喂 R6 重跑 —— 一份数据两个用途。
- 过期清理：启动时删掉除最近 3 次以外的文件（不做后台定时清理，避免过度设计）。

### 3.5 `ui/screen/batch/BatchViewModel.kt`

- UiState 新增 `failed: List<FailedItem(uri, index, reason)>`、`successUris: List<Uri>`、`runId: String?`。
- 提交任务时算 `indices = images.indices.map { exportSettings.indexStart + it }`。
- 新增 `retryFailed()`：用**失败集的 uri + 原序号**再提交一个请求（配置复用当前 state，不发新参数的 snapshot → 保证「同一批同一套参数」）。
- 重跑前先清空上一轮 `failed`，否则按钮会重复触发。

### 3.6 `ui/screen/batch/BatchConfigScreen.kt` 结果摘要

- 「重试失败项（N）」：仅 `failed.isNotEmpty()` 时显示。
- 「分享 N 张」：`ACTION_SEND_MULTIPLE` + `FLAG_GRANT_READ_URI_PERMISSION`，无图时不显示。
- 一行说明文案：重跑可能与已有文件同名，系统会另存为 `xxx (1).jpg` —— **不覆盖、不删除**（见 §6 风险 1）。

### 3.7 `ui/screen/editor/EditorViewModel.kt:547`（单张导出）

`index = null` 保持不变 —— R7 的修法在 `FileNameResolver` 内部（删段 + 收敛分隔符），调用方不用改。这也是把修复放纯函数里的好处：单张、批量、预览三条路径同时受益。

---

## 4. 测试计划（先写测试再改实现的地方标 ★）

| 测试 | 覆盖 |
|---|---|
| ★ `FileNameResolverTest` | `{index}` 无位数 / `{index:2}` / `{index:04}` / `{index:06}`；位数超长不截断；`index=null` 删段；`照片{index}_压缩` → `照片_压缩`；`照片({index})` → `照片`；`[]` `()` 收敛；`hasPlaceholder` 认带位数形式 |
| ★ `BatchWatermarkWorkerTest`（现有） | `KEY_INDICES` 缺省时按 1..n；给了则按给定值命名；输出 `requestId` |
| 新 `BatchRunStoreTest` | 写入-读回对齐；损坏 JSON 不崩（回落空）；只保留最近 3 份 |
| `BatchViewModelTest`（若有） | `indexStart=5` + 3 张 → 5/6/7；`retryFailed()` 只带失败项且序号沿用 |
| 兼容性 | 用**老格式**预设 JSON（无 `indexStart`）反序列化 → 起始号为 1，不抛异常 |

基线不许降：当前 **474 单测 / 0 失败**（含 C2 新增的 6 个边界用例）。

---

## 5. 验收（MuMu 上跑，界面操作由老大点）

1. 设置页：开序号 → 模板变 `{原文件名}_watermarked_{index:04}`，预览 `IMG_2041_watermarked_0001.jpg`；关掉 → 尾部 `_` 被收敛掉，预览 `IMG_2041_watermarked.jpg`。
2. 位数改 2 → 预览 `..._01.jpg`；模板手改成 `photo_{index}_x` → 预览 `photo_1_x.jpg`（不补零，位置跟随）。
3. 批量 3 张、起始号 7 → 文件名 `..._0007/_0008/_0009`。
4. 故意混一张坏图（或超大图）→ 结果页出现「重试失败项（1）」→ 点它 → 重跑成功项的文件名**仍是那一个序号**，没重号。
5. 结果页「分享」→ 系统分享面板带出这批成功图。
6. 单张导出（模板含 `{index:04}`）→ 文件名**不含** `()`、`__` 残留。
7. 老预设（本版前导出的 json）导入 → 模板与起始号正常，无崩溃。

仪器化 57 例照旧全绿（`MuMuManager` 接 adb 后跑 `tools/run_instrumented_tests.sh`）。

---

## 6. 风险与未定项（实施中要盯）

1. **同名冲突**：重跑保号后，`MediaProvider` 极可能把同名文件存成 `xxx (1).jpg`（`ensureUniqueFileColumns`）。**序号是对的、文件名多后缀**。计划是接受 + 文案说明；**不覆盖、不删除**（不可逆操作需老大单独授权）。此项要在 MuMu 上**实测确认**，不能凭文档断言。
2. **分享的 uri 权限**：`content://media/...` 直接跨应用可能被拒；必要时用 `FileProvider` 包装。先试 MediaStore uri，失败再上 FileProvider —— 按「先跑通再抽象」的顺序。
3. **模板里 `{}` 之外的未知占位符**（如 `{foo}`）仍会被当字面量保留，本版不改（现有行为，无需求）。
4. **10KB 上限**只在成功/失败清单上绕开了；`KEY_URIS` 本身在 100 张时已接近上限（现状就存在，非本版引入）——若实测 100 张提交失败，再单独开一项治理。

---

## 7. 实施顺序（每步可独立编译 + 跑测试）

1. `FileNameResolver` + 测试（纯函数，最快拿到确定性收益，且 R7 顺带修掉）
2. `ExportSettings.indexStart` + 兼容性测试
3. 设置页开关 / 位数 / 预览 / 起始号
4. `BatchRunStore` + Worker 序号入参与清单输出 + 测试
5. ViewModel 接线（含 `retryFailed`）
6. 结果页「重试失败项」「分享」
7. 全量 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` + `tools/pre_push_audit.sh`
8. MuMu 验收（§5）→ 文档收口（本节改「已实现」、pending-fixes 记录本版引入/关闭的项）

---

## 8. 实施记录：与计划的偏离（写于实现完成时）

计划是开跑前写的，落地时有几处**必须记下来**的差异——后来的人按计划读代码会对不上：

1. **`ExportSettings` 不在预设里，在通用设置里。**
   它是 `SettingsRepository` 的 DataStore 字段（`app/src/main/java/com/watermarkbox/data/repository/SettingsRepository.kt`），
   与预设（`WatermarkPreset`）是两套数据。所以 §3 说的「预设兼容性」实际落在
   `ExportSettingsTest` 的 DataStore 反序列化上，不是预设快照。
2. **设置页的 UI 落在 `SettingsExportCategory.kt`，不是 `SettingsScreen.kt`。**
   而编辑页 / 批量页的参数框复用**同一个**新组件
   `app/src/main/java/com/watermarkbox/ui/component/FileNameTemplateSection.kt`
   —— 三处入口、一份实现、一份行为。
3. **位数占位符的语法**：实现了 `{index:04}`。
   字母可以是 `M`（千分位 `,`）或 `m`（零填充 `0`），数字 = 宽度；
   `{index:4}`（只有数字）**不认**，原样保留。
4. **`KEY_FAILED_URIS` 被删掉了。** 它从来没有消费者（grep 全仓只剩 Worker 自己写），
   而 100 张图的 uri 数组接近 `Data` 的 10KB 上限——留着就是一颗定时炸弹。
   清单统一走 `BatchRunStore`。
5. **结果页的两个按钮在统计框内部**（`BatchSummaryDialog` 的 `text` 区），
   不是独立的底部按钮条：`canShare` / `canRetry` 为假时按钮直接不出现。

---

*本文档由 AI 会话在 2026-10-26 与老大 8 轮问答后生成；实施偏离必须回来改它。*

# 多会话协作须知

> **本文档面向「在本项目工作的所有 AI 会话（含未来的你）」。**
>
> 这个仓库由**多个 AI 会话 + 一位人类所有者（肆叶）同时推进**。
> 不同会话可能在看不同的坑、改不同的文件。
> 因此：**你看到的改动，未必是你做的；你遇到的"奇怪状态"，多半是别人刚推的。**
>
> 读本文档只需要 3 分钟，但能省下几小时的误判和返工。

---

## 0. 一句话总则

$$\textcolor{#0D47A1}{\textbf{\text{先搞清楚「现在是什么状态」，再决定「我要做什么」。}}}$$

工作前**必做 3 件事**（顺序不能变）：

```sh
# ① 看提交历史 —— 别人改了什么
cd "$GIT_REPO" && git log --oneline -10

# ② 看工作区与仓库是否一致 —— 有没有别人改到一半的东西
diff -rq "$WORKSPACE/app/src" "$GIT_REPO/app/src"

# ③ 看本节「当前状态」和「活跃任务」
#    就在下面，别跳过
```

> **路径约定**：本文档用两个占位符，避免把本机绝对路径写进公开仓库：
>
> | 占位符 | 含义 |
> |---|---|
> | `$WORKSPACE` | AI 工具可直接读写的工作区根目录 |
> | `$GIT_REPO` | 用于 git 提交与构建的本地仓库根目录 |
>
> **当前设备上的实际取值**见 `docs/build-artifacts.md` 或向人类确认。
> 不同机器/会话环境可能不同 —— 这也是为什么文档里不写死。

---

## 1. 项目坐标（两套路径，别搞混）

$$\fcolorbox{red}{#FFEBEE}{\textcolor{red}{\textbf{\text{代码有\textbf{两份}，必须靠 \texttt{cp} 同步 —— 这是最容易出错的点}}}}$$

| 位置 | 特点 |
|---|---|
| **工作区** `$WORKSPACE` | AI 工具直接读写；常位于 FUSE 挂载，**git 不可用（或极慢）** |
| **git 仓库** `$GIT_REPO` | ext4；只有 Ubuntu 终端能看见；**提交与构建都在这** |

**同步方向**：工作区 → 仓库（**单向**，改完工作区要手动同步过去）

```sh
# ✅ 正确写法（cp -r 递归覆盖，能带上新增文件）
cp -r "$WORKSPACE/app/src/." "$GIT_REPO/app/src/"

# ⚠️ 旧写法已废弃：早期用 diff 管道同步，
#    会【漏掉新增文件】—— 曾一次性补回 6 个丢失文件、+1529 行。
#    不要再用 diff | patch 那套。
```

**反向也要看一眼**：如果仓库里有工作区没有的改动（别人在仓库直接改的），
先搞清楚状况再继续，别直接 `cp` 覆盖掉。

```sh
# 反向检查（仓库有、工作区没有的文件）
diff -rq "$GIT_REPO/app/src" "$WORKSPACE/app/src"
```

---

## 2. 硬性规矩（违反会直接造成损失）

### 2.1 推送

$$\fcolorbox{red}{#FFEBEE}{\textcolor{red}{\textbf{\text{未经人类所有者明示「推送」，\textbf{绝不} \texttt{git push}}}}}$$

- 本地攒提交是**常态**，攒到十几个很正常
- 每轮结束**必须**报告：`本地 HEAD / 远端 / ahead N`
- 推送前有 pre-commit 钩子做敏感信息审计，**别绕过它**

### 2.2 装机

| 规则 | 说明 |
|---|---|
| **固定 debug 包名** | `com.watermarkbox.final`（构建加 `-PparallelSuffix=.final`）|
| **不要生成新包名** | 每次新包名 = 桌面上多一个图标，会被明确批评 |
| `com.watermarkbox` | **release 签名**包，debug 装不上去是**正常的**，别折腾 |
| **不要动 release 版** | 上述 `com.watermarkbox` 就是 release，只升级 debug 版 |
| **装前验包名** | `aapt2 dump badging xxx.apk \| head -1` 确认再装 |
| `parallelSuffix` | **唯一合法用途**：临时多开对比；用完必须 `pm uninstall` 清理 |

```sh
# 标准装机流程（APK 路径按你的构建输出调整）
APK="$WORKSPACE/app/build/outputs/apk/debug/app-debug.apk"
cp "$APK" "$TMPDIR/wmb.apk"
pm install -r -d --user 0 "$TMPDIR/wmb.apk"

# 核实装的是不是这一版（md5 必须一致）
APKPATH=$(pm path com.watermarkbox.final | head -1 | sed 's/package://')
md5sum "$APKPATH" "$APK"
```

> `$TMPDIR` 是设备上可写且已授权的临时目录（避免触发 shell 的重定向限制）。
> 实际取值结合你的环境确认。

### 2.3 构建

```sh
cd "$WORKSPACE"
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain
```

**每轮必跑全套**（测试 + 构建 + lint）。**测试基线不许下降**。

### 2.4 提交信息

写**为什么**、**取舍**、**踩坑**，不只写"改了什么"。
本项目已有习惯：提交信息可以很长，用 `═══` 分隔分节。**保持这个风格。**

---

## 3. 当前状态（**每次工作前刷新这一节**）

> ⚠️ 这一节是**易变**的。你读到的内容可能已经过期 ——
> 以 `git log` 和 `git status` 的**实际输出**为准，本节的文字只是补充说明。

| 项 | 值 |
|---|---|
| 本地 HEAD | `ac7bfca` |
| 远端 | `origin/main`（落后 25 个提交）|
| **ahead** | **25（未推送）** |
| 单测基线 | **406 个用例，全绿** |
| lint | 0 错误（6 条 baseline）|
| debug 包名 | `com.watermarkbox.final` |
| 版本 | 2.0.1 (versionCode 11) |

**更新约定**：任何会话在一轮工作结束时，如果 HEAD 变了，
**顺手更新上表的 HEAD 与 ahead**（一行的事，但对下一个人很关键）。

---

## 4. 活跃任务看板

> 状态标记：`🔄 进行中` / `⏳ 等人类验证` / `📋 待办` / `✅ 已完成未推送`

### 🔄 进行中

| 任务 | 负责 | 说明 |
|---|---|---|
| **导出内存预算修复的验证** | 等人类验证 | 见 §5.9，大图导出是否不再报「图片过大」|

### 📋 待办（需要人类拍板才能动）

| 任务 | 阻塞点 |
|---|---|
| README 截图 | 等人类提供真机截图 |
| Baseline Profile | 收益评估偏小，优先级低 |
| 是否发 2.0.2 | 等人类决定（**ahead 25 个提交已攒多，建议讨论推送/发版**）|
| **推送 ahead 个提交** | **必须等人类明示** |

### ✅ 近期完成（未推送，别惊讶于这些改动）

> **其他会话注意**：下面这些是**已完成的正常改动**。
> 如果你看到相关代码与你的印象不符，**大概率是这些**，不是 bug。

- `ac7bfca` 导出「图稍大就失败」：内存预算三重保守（largeHeap + 按需份数 + 系数）
- `e403895` 批量页预览卡顿：重组爆炸 + 降采样失效 + 分辨率过高（**三个独立原因**）
- `0c46fc8` / `afd79b6` / `793832a` / `c2f8d75` 预览频闪的**三次**修复（详见 §5.1）
- `ff02ab0` 检测设置两两并排 + 文案精简
- `b7cdc78` 检测按钮并排 / `07a7c6b` 新增重新检测按钮
- `b9e8868` DC-QIM 失真补偿 + 检测失败提示「可能旧版本导出」
- `0020c46` DCT 嵌入缺陷修复（整块缩放 → 逐像素裁剪）

---

## 5. 项目特有的「反直觉」知识点

> 这些是**踩坑换来的**。不了解它们，你会重复踩一遍。

### 5.1 预览频闪：一个修了三次的 bug

$$\fcolorbox{red}{#FFEBEE}{\textcolor{red}{\textbf{\text{症状：拖动字号/角度/密度/透明度时预览闪烁，松手就正常}}}}$$

**关键结论（第三次才找对）：**

```
渲染链路是【完全同步】的（无 delay、无线程切换、无 yield）
  ↓
Kotlin 协程的取消【只在挂起点生效】
  ↓
collectLatest 发出的取消对同步函数无效，只能"排队"
  ↓
每帧都开新渲染，旧的还在跑 → 多个渲染并发写同一批复用位图
  ↓
画面在各自半成品之间跳 → 频闪
  ↓
松手后不再有新请求 → 渲染重新串行 → 恢复正常
```

**修法**：`RenderCancelCheck` 一路透传，在平铺循环里按行检查。

**前两次的失败尝试**（别再往这些方向修）：
- ❌ 修 `PreviewRenderEngine` 的「失败时清空画面」—— 治了外围
- ❌ 修 `PreviewBuffers` 的保护窗口 —— 治了下游

**教训**：`collectLatest` 只对**有挂起点**的操作有效。
对同步代码，必须**显式传取消检查口**。

### 5.2 隐形水印（DCT）

| 知识点 | 结论 |
|---|---|
| **嵌入缺陷** | 浅色背景图曾因「整块统一缩放」导致 28.4% 的块 ΔY 被抹平 → 提取失败 |
| **修法** | 改「逐像素裁剪」，逐块正确率 54.17% → 99.50% |
| **代价** | PSNR −1.25 dB（真实场景 38 dB 左右），可接受 |
| **DC-QIM** | α=0.4 甜点：+4.34 dB 画质，代价 0.42% 位错误（被冗余吸收）|
| **JPEG 重压缩** | 零损失，Q15 以上 100%，崩溃点与普通 QIM 一致 |
| **几何攻击** | 旋转 1° 就崩到 55% —— **已决策不做**（成本 3~5 天不匹配场景）|

**关键约束**：提取端读的是**亮度**。任何写回策略的最终亮度必须与提取端假定的一致，否则不可逆。

详见 `docs/invisible-watermark-research.md`。

### 5.3 批量处理其实已经是「一张一张」

Worker 层 `uris.forEachIndexed { processOne(...) }` **本来就是顺序的**。
早期「看起来一起处理」是 **WorkInfo 的 10 秒级节流**导致进度不刷新。

**已修**：补了 250ms 自建轮询（`PROGRESS_POLL_MS`）+ 防进度回退护栏。

### 5.4 `androidx.work.await` 不能用

被 `@RestrictTo(LIBRARY_GROUP)` 限制 → lint 直接构建失败（RestrictedApi）。

**替代**：自桥 `awaitWorkInfo()`（见 `BatchViewModel`）
`suspendCancellableCoroutine` + `future.addListener` + `invokeOnCancellation { future.cancel(true) }`

### 5.5 检测页文案精简的由来

为了「两两并排」，主文案被大幅缩短（如「检查 EXIF 元数据」→「EXIF」）。
**完整说明保留在各项的 ⓘ 弹窗里**。改文案前先看 `docs/` 里的记录。

### 5.6 预览「不实时」：降采样在非正方形图上曾完全失效

$$\fcolorbox{red}{#FFEBEE}{\textcolor{red}{\textbf{\text{\texttt{calculateInSampleSize} 的 \&\& 写法会让非正方形图\textbf{一点不降}}}}}}$$

```kotlin
// ❌ 旧：要求两条边都能折半才降
while (w / 2 >= target && h / 2 >= target)
// ✅ 新：只要最长边还超就降
while (maxOf(w / 2, h / 2) >= target)
```

**实测影响**（target = 1024）：

| 原尺寸 | 旧 | 新 |
|---|---|---|
| **2048×1536** | **2048×1536（没降！）** | 1024×768 |
| 5760×3840 | 2880×1920 | 1440×960 |
| 5000×1500 | 5000×1500（没降！） | 1250×375 |

2048×1536 不降 = 每帧 `copy()` **12 MB** → 渲染一帧约 600ms
→ 拖滑块（60fps 每帧发请求）永远追不上 → **预览不实时**。

**不变量**：降采样后最长边 ∈ `[target, 2*target)`。

> ⚠️ **教训**：旧测试曾把 bug 写成断言
> （`assertEquals(1, calculateInSampleSize(5000, 1500, 1024))`）。
> **把 bug 固化成断言的测试比没有测试更危险** —— 它会阻止后来者修复。

### 5.7 批量页：只有当前页该参与渲染

`HorizontalPager` 会短暂组合相邻页。若每个 `WatermarkPreview`
都建缓冲池 + 引擎并提交渲染，会：
- 每实例一套三缓冲（大图各占数 MB）
- 拖参数时全部触发渲染，但相邻页 `bitmap == null`，结果用不到
- 实测 `submit` 579 次 vs `result` 143 次 —— 大量无效渲染抢资源

**修法**：`WatermarkPreview(enabled = isCurrent)`。
`enabled=false` 时不提交渲染、直接回显源图；
`enabled` 进 `LaunchedEffect` key，滑到该页时立即补渲染。

### 5.8 预览卡顿的完整排查链（5 轮实测）

> 这是本项目**排查最久**的一个问题。记下完整链路，
> 下次遇到"某个页面的交互卡"可以照着走。

**症状演化**：卡 → 修了还卡 → 修了还卡 → 好一些 → 终于跟手。

**每一轮的数据与结论**：

| 轮次 | 手段 | 发现 | 结论 |
|---|---|---|---|
| 1 | 加 `WMPerf` 打点 | `acquire≈0.03ms`，`draw≈31ms` | 排除缓冲池，锁定渲染 |
| 2 | 加 `WMCopy` / `WMDraw` | `outputFor` 占 98%（33.8ms） | 锁定「底图填充」 |
| 3 | 加 `WMInstance` 计数 | **0.5 秒 485 次 compose** | 发现**重组爆炸**（真凶之一）|
| 4 | 拆分参数 + `rememberUpdatedState` | 重组降到 30~50/秒 | ✅ 修好一半 |
| 5 | 查设备内存 | **`SwapPss=113MB`、Swap 用了 12GB** | 位图被换出 → 降分辨率 |

**三个独立原因**（叠加才导致"卡"）：

1. **pager 内容重组爆炸** —— `BatchPreviewPager(state)` 收整个 UI State，
   参数每帧变 → `HorizontalPager` 整体重组（重测量/重布局，很贵）
2. **降采样失效** —— 见 §5.6，2048×1536 完全不降
3. **分辨率过高触发 Swap** —— 内存吃紧时位图被换出，访问 = 磁盘读回

**关键教训**：

- **"开始流畅、后面变卡" = 资源渐进退化**，不要往"代码慢"方向查，
  先看 `dumpsys meminfo` 的 `SwapPss`
- **用数据切分耗时**，不要靠读代码猜。`outputFor=261ms / newCanvas=0.02ms /
  buildPaint=0.13ms` 这样的分解直接指出病灶
- **Compose 里传整个 State 给子组件是性能陷阱**：
  任何字段变化都会让子组件重组。用 `rememberUpdatedState` 包住
  频繁变化的参数，让调用点延迟读取

### 5.9 导出「图稍大就失败」：内存预算的三重保守

`BitmapBudget` 的预检**设计是对的**（渲染阶段的 `source.copy()`
没有兜底，真放不下会崩），但三处假设叠加后过于保守：

```
heapgrowthlimit = 256MB   ← 普通应用堆上限
HEAP_FRACTION   = 0.5     ← 只敢用一半
EXPORT_COPIES   = 3       ← 固定按 3 份算
────────────────────────
预算 128MB ÷ 3 = 42.7MB/张 → 约 1120 万像素
```

**而手机随手一拍就 1200 万像素以上** → 大量失败。三处修复：

1. **Manifest `largeHeap="true"`** —— 堆上限 256MB → 512MB
2. **按实际通道数算份数** —— 不开 DCT 时只需 2 份（`exportCopies(withDct)`）
3. **系数 0.5 → 0.6** —— API 26+ 位图内存走 `NativeAllocationRegistry`，
   **不计入 Java 堆**，"留一半给 Java 堆"的前提不成立

> ⚠️ **同一类教训又出现一次**：`BitmapBudgetTest` 里有断言写死了旧系数
> （`预算是堆上限的一半`），把"过度保守"固化成了正确预期。
> 这已经是**第二次**（上次是 `BitmapUtilsSampleSizeTest`）——
> **写测试时要问：这个断言是在描述"应该如此"，还是仅描述"现在如此"？**

### 5.10 `largeHeap` 的取舍

开启后系统在内存紧张时**更早**回收本进程。对本应用可接受：
前台交互型（导出完就走），且导出期间本来就需要这份内存。

若将来出现"后台被杀频繁"的反馈，可以考虑只在导出流程临时申请，
但**不要**因为这条顾虑就退回不开 —— 那是拿"必然失败"换"可能被杀"。

---

## 6. 资源共享与冲突预案

### 6.1 共享资源（最容易冲突的地方）

| 资源 | 冲突风险 | 建议 |
|---|---|---|
| **工作区 vs 仓库** | 高 | 每次改前先 `diff -rq` 看两边差异 |
| `strings.xml` / 文案 | 高 | 改前 `grep` 一遍，改后核对**中英数量一致** |
| 检测页 / 编辑页布局 | 中 | 布局改动互相影响，改前先读全文件 |
| 测试基线数字 | 中 | 别人加了测试，你的「391」可能已不是最新 |
| 装机包 | 高 | 只有一个 `com.watermarkbox.final`，**装之前先问是不是有人在测** |

### 6.2 发现别人改动的正确姿势

$$\fcolorbox{blue}{#E3F2FD}{\textcolor{#0D47A1}{\textbf{\text{不要"奇怪"，先"评估" —— 用 git 问，别用猜}}}}}$$

```sh
# 这个文件最近被谁、为什么改的？
git log -3 --format='%h %s' -- <文件路径>
git show <commit> -- <文件路径>

# 这些改动是我做的吗？（对比提交时间和你自己的操作）
git log --oneline --since='2 hours ago'
```

**三种情况的处理**：

| 情况 | 处理 |
|---|---|
| 改动合理、和我的任务无关 | **不动它**，继续自己的工作 |
| 改动合理、**影响**我的任务 | 评估影响，必要时同步给对方（在本文档 §4 留言）|
| 改动**疑似有 bug** | **先 `git show` 看清意图**，再评估；不确定就**问人类**，别直接改回去 |

### 6.3 绝对不要做的

- ❌ `git reset --hard` / `git checkout .` —— 会**抹掉别人的未提交改动**
- ❌ `git push -f` —— 会**抹掉别人的提交**
- ❌ 直接 `cp` 覆盖整个目录 —— 先 `diff` 确认方向
- ❌ 在没搞清状态下就开始改代码

---

## 7. 工作流模板（建议照做）

```
① 摸底
   git log --oneline -5
   diff -rq 工作区/src 仓库/src
   读本文档 §3 §4

② 定位
   grep_code / grep_context 找相关代码
   不要凭印象改 —— 先读实现

③ 改 + 验证
   改工作区 → 同步到仓库 → 跑全套构建
   单测数量不许下降

④ 装机（如果人类要验证）
   固定包名 com.watermarkbox.final
   核实 md5

⑤ 提交（不推送）
   提交信息写「为什么/取舍/踩坑」

⑥ 汇报
   本地 HEAD / 远端 / ahead N
   更新本文档 §3 §4
```

---

## 8. 给「下一个会话」的交接提示

> 每一轮工作结束，在**下面**追加一条（最新的在最上面）。
> 格式：时间 + 会话干了什么 + 留下什么未决。

```
[2026-10-13 23:5x] 导出「图稍大就失败」—— 内存预算三重保守
  - 提交 ac7bfca，ahead 25，单测 406
  - ① Manifest 加 largeHeap（堆 256MB → 512MB）
  - ② exportCopies(withDct) 按实际通道数算份数（不做最坏假设）
  - ③ 系数 0.5 → 0.6（位图不计入 Java 堆）
  - ⚠️ BitmapBudgetTest 断言写死了旧系数 —— **同类教训第二次**
  - ⏳ 等人类验证大图（12MP+）导出

[2026-10-13 23:4x] 批量页预览卡顿 —— 三个独立原因
  - 提交 e403895，ahead 24，单测 402
  - ① pager 重组爆炸（0.5s 485 次 compose）→ 拆参数 + rememberUpdatedState
  - ② calculateInSampleSize 的 && 缺陷 → 改 maxOf（见 §5.6）
  - ③ 预览分辨率 1024 → 720（内存吃紧触发 Swap，见 §5.8）
  - ④ 顺带修掉自己引入的重影（copyPixelsFromBuffer 行对齐问题）
  - 顺带：三缓冲 → 双缓冲

[2026-10-13 23:3x] 建立多会话协作机制
  - 提交 d43eaf0，ahead 20
  - 新增 docs/multi-session-collaboration.md（本文档）
  - 修复：docs/build-artifacts.md 含本机路径却已被跟踪
    → 停止跟踪 + 加 .gitignore（文件保留在磁盘）
  - **新会话请先读本文档 §0 的「必做 3 件事」**

[2026-10-13 23:2x] 预览频闪真根因（协程取消无法中断同步渲染）
  - 提交 bb1c7c1，ahead 19
  - 新增 RenderCancelCheck，透传至 WatermarkRenderer 平铺循环
  - 顺带：reuseTarget 加 try/finally、新增 onRendered 回调
  - 遗留：ahead 25 个提交待人类明示后推送
```

---

## 9. 文档索引

| 文档 | 内容 |
|---|---|
| `README.md` | 项目介绍、构建/发布流程 |
| `CONTRIBUTING.md` | 代码约定、分层、注释规范 |
| `docs/build-artifacts.md` | **构建产物与装机规范**（必读）|
| `docs/invisible-watermark-research.md` | 隐形水印调研 + 实测数据 |
| `docs/baseline-profile.md` | Baseline Profile（低优先级）|
| **`docs/multi-session-collaboration.md`** | **本文档** |

---

*本文档由 AI 会话维护。如果你是 AI：**发现规则过时，直接改**；发现新坑，**补进 §5**。*

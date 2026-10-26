# 待修复清单 · 2026-10

> **来源**：2026-10-16 一次「Windows 环境可行性验证 + `app/src` 第二轮静态审查」
> **范围**：① 环境与工具链的 Windows 适配 ② `app/src` 代码新发现 ③ 文档一致性欠账
> **原则**：本清单**只记录问题**。修完请更新 §0 状态表并注明提交，不要在这里追加流水账。
> **本机专用取值**（SDK 路径、临时目录等）一律不写进本文，记 `docs/build-artifacts.md`（不入库）。
>
> ⚠️ 所有「静态审查」项**未经真机复现**，属代码阅读结论；「实测」项是本机跑出来的。

---

## 0. 总览

> ⚠️ **环境类 11 项（高 3 / 中 6 / 低 2）· 代码类 11 项（高 3 / 中 6 / 低 2）· 文档类 6 项（中 3 / 低 3）**
>
> **修订状态（2026-10-26 · 第四轮「C6 收口」）**：已修 **23 项**
> （E1–E11、C1、C2、C3、C5、C6、C7、D1–D6），未修 **5 项**（C4、C8、C9、C10、C11）。
> 已修项均已落盘；代码类（E5、C1、C2、C3、C5、C6、C7）另有全量构建 + **481 单测** + lint 复验（见 §6）。
> 「已修」指**问题已消除或已收口为明确决策**，不等于已提交 —— 本轮改动按铁律只本地保留。
> 修完一项请把状态列改为 `✅ 已修` 并注明提交号，不要在这里追加流水账。
>
> **仍未修的 5 项（老大决策：副作用大于收益）**：C11（改载荷格式 = 影响已发布图片
> 能否解码，属高危区）· C8（378 行「生产零引用」已在 §4 / §5.18 记录为**保留决策**，
> 动它要改测试基线）· C9（DI 重构回归面最大，无真机验证不划算）· C10（改动落在导出主链路，
> 与 C5 拆开做）· C4（`typefaceCache` 上限，需先定长会话策略）。

### 状态表

| 编号 | 严重度 | 状态 | 位置 | 一句话 |
|---|---|---|---|---|
| E1 | 🔴 高 | ✅ 已修 | `tools/collab_status.py` | 单份代码下**同步闸门永久假绿**；快照写进 `.git` 目录，worktree 里静默失败 |
| E2 | 🔴 高 | ✅ 已修 | `tools/git-hooks/pre-commit:73` + 十余处文档 | 硬编码 `python3`；Windows 占位程序会**静默失败并误报成「工作区未同步」** |
| E3 | 🔴 高 | ✅ 已修 | `CONTRIBUTING.md` + `local.properties` | 文档教的写法在 Windows 上**必然让 `lintDebug` 失败**，且 lint 会缓存该结论 |
| E4 | 🟠 中 | ✅ 已修 | 仓库根 / `.git/hooks/` | 无 `.gitattributes` + 钩子从未安装 → 审计与同步两道闸门在 Windows 全关 |
| E5 | 🟠 中 | ✅ 已修 | `app/build.gradle.kts:149-155` | 强制 aapt2 走 `linux-aarch64` 分类器 —— 该分类器在 Maven 上**根本不存在** |
| E6 | 🟠 中 | ✅ 已修 | `README.md:285` | 写 build-tools 35.0.0，AGP 9.0.0 实际要 **36.0.0** |
| E7 | 🟠 中 | ✅ 已修 | `tools/install_debug.sh` / `run_instrumented_tests.sh` | 换的是**执行模型**（`pm`/`am`/`pidof`），不是路径 |
| E8 | 🟠 中 | ✅ 已修 | `tools/build_release_apk.py` / `push_release.sh` | 找 `apksigner`（Windows 是 `.bat`）；`push_release.sh` 有**未定义函数**且同步段已无意义 |
| E9 | 🟠 中 | ✅ 已修 | `tools/export_changelog.py` | 改写为**默认干跑**（`--apply` 才写盘）+ `--help`；「任何调用直接重写」已消除 |
| E10 | 🟡 低 | ✅ 已修 | 文档 / CI | JDK 版本文档写 17，实测环境是 21/25 |
| E11 | 🟡 低 | ✅ 已修 | `setup_android_env.sh` | 纯 aarch64/Linux 脚本躺在仓库根，缺「勿在其他平台执行」警告 |
| C1 | 🔴 高 | ✅ 已修 | `ui/screen/detect/DetectViewModel.kt:58` | 检测**缺并发令牌** → 结论可能挂到另一张图上 |
| C2 | 🔴 高 | ✅ 已修 | `util/BitmapBudget.kt` + `ImageRepository.kt:52` | 预览缓存张数改由 `BitmapBudget.previewCacheCapacity()` 按堆预算算出（1–3），不再写死 2 |
| C3 | 🔴 高 | ✅ 已修 | `ui/screen/editor/EditorViewModel.kt:167` | `loadImage()` 补并发令牌（与 C1 / 批量页同模式），乱序回写不再可能 |
| C4 | 🟠 中 | ⏳ 待修 | `watermark/WatermarkRenderer.kt:69` | `typefaceCache` **无上限无淘汰**，长会话持续增长 |
| C5 | 🟠 中 | ✅ 已修 | `invisible/WatermarkDetector.kt` + `EditorViewModel` + `ExportRepository` | 原判据有误（见本节末）：真问题是**兜成「原因未知」**，检测侧则**根本没兜**（OOM 直崩进程） |
| C6 | 🟠 中 | ✅ 已修 | `ui/screen/update/UpdateViewModel.kt` · `util/ApkInstaller.kt` · `util/UpdateChecker.kt` | 下载/安装链路静默吞错 → 表现为「点了没反应」 |
| C7 | 🟠 中 | ✅ 已修 | `ui/screen/settings/SettingsScreen.kt:168` | 挂/摘观察者合为一路，`onDispose` 里不再有空的契约分支 |
| C8 | 🟠 中 | ⏳ 待修 | `watermark/PreviewRenderEngine.kt` / `PreviewBuffers.kt` | 378 行**生产零引用**，却有测试把它钉成「线上架构」 |
| C9 | 🟠 中 | ⏳ 待修 | 多处 | 无 DI 容器；Composable 内自建仓库、ViewModel 自建仓库 |
| C10 | 🟡 低 | ⏳ 待修 | `util/ExifCopier.kt` / `ExportRepository.kt` | 元数据与压缩失败静默，仍报「成功」 |
| C11 | 🟡 低 | ⏳ 待修 | `invisible/WatermarkPayload.kt` | 载荷**无版本字段**，将来改格式缺机器可判的新旧区分点 |
| D1 | 🟠 中 | ✅ 已修 | `docs/multi-session-collaboration.md:170` vs `:211` | 两个 `2.6` 撞号（**第三次**），引用「§2.6」已无法判断指向 |
| D2 | 🟠 中 | ✅ 已修 | 同文件 `:59-87` vs `:322/:890/:925` | §1 还在教 `cp -r` 双份同步，与 §2.6 的 worktree 流程并存矛盾 |
| D3 | 🟠 中 | ✅ 已修 | `CONTEXT.md` / `multi-session-setup-prompt.md` | 「开工三步 / 必做 3 件事」已被单条自检命令取代，引用未更新 |
| D4 | 🟡 低 | ✅ 已修 | `RELEASE_PROCESS.md` 等多处 | `./gradlew` 与 `sh gradlew` 两套写法并存 |
| D5 | 🟡 低 | ✅ 已修 | `README.md` | 链接指向**不入库**的 `docs/build-artifacts.md`（克隆后必 404）；Linux 专属路径写进跨平台文档 |
| D6 | 🟡 低 | ✅ 已修 | `docs/multi-session-collaboration.md:798/:1028` | Operit 手机端的工具参数（`environment=linux`）写进了通用流程 |

> **D3 / D4 的残余**：历史「交接记录」段落里的旧写法（`§0 必做 3 件事`、旧命令行）
> **按「不篡改历史记录」保留**，只改活文档；看到旧记录请以本文档 §0 与 `AGENTS.md` 为准。

---

## 1. 环境与工具链（Windows 适配）

> 这一节全部属于**「项目脚本/文档假定 Linux + aarch64 + FUSE」**这一类问题。
> 纯本机层面的问题（例如系统 `Path` 被写坏、`python3` 是商店占位程序）**不算项目缺陷**，
> 只在 §6 留事实记录。

### 🔴 E1 — `collab_status.py` 的单目录假设会让同步闸门永久放行

**位置**：`tools/collab_status.py:111-116`（只判 `ws.exists()`，**没有 `ws == ROOT` 守卫**）、
`:248-321`（只比 `app/src`/`docs`/`tools`/`.github` 四目录）、`:452-479`（`--check-sync` 无差异即 `return 0`）

**现象（实测）**：本机只有一份代码（`$WORKSPACE` 就是仓库本身）。若把 `--workspace` 指向仓库自己，
`diff -rq A A` 必然无输出 → 脚本打印「工作区 ↔ 仓库差异」为空、报告「一致 ✅」，
`--check-sync` **永远放行**。这正是 `AGENTS.md:33-38` 警告过的「假状态」，只是换了成因。

**附带**：`:49` 的 `SNAPSHOT = ROOT/".git"/"collab-state.json"` 假定 `.git` 是**目录**。
worktree 里 `.git` 是**文件** → 读写被 `:71-82` 的 `except Exception: pass` 静默吞掉 →
「与上次自检相比发生了什么变化」**永远为空**。
（`tools/git-hooks/prepare-commit-msg:55` 已经用 `git rev-parse --git-dir` 规避了同一问题，
`collab_status.py` 没跟上 —— 同一坑只修了一半。）

**另外**：`:215` / `:268` 依赖外部 GNU `diff`，两处 `subprocess` 都没有 try。
从 PowerShell/cmd 启动（PATH 上只有 `git.exe`）会抛未捕获的 `FileNotFoundError`。

**建议**：① 加「`--workspace` 与仓库同路径 → 打印『单份代码模式，跳过差异比较』」；
② 快照路径改用 `git rev-parse --git-common-dir`，写失败至少打一行警告；
③ `diff` 换成 `filecmp` 纯 Python 实现，或明确规定「必须从 Git-Bash 启动」。

---

### 🔴 E2 — `python3` 硬编码：Windows 上会**静默**失败并被误报成别的原因

**位置**：`tools/git-hooks/pre-commit:73-74`，以及 `AGENTS.md:30`、`CONTEXT.md:25`、
`docs/multi-session-collaboration.md:21/275/276/929`、`docs/RELEASE_PROCESS.md` 十余处、
`README.md:301/303`、`README.en.md:313/315`、`docs/baseline-profile.md:89`

**现象（实测）**：Windows 上 `python3` 解析到应用商店占位程序 → **退出码 49、零输出**；`python` 才是真解释器。

**为什么是问题（比「命令跑不了」严重）**：
`command -v python3` **会通过**（占位程序也算「存在」）→ pre-commit 调它拿到 49 →
钩子判定「工作区未同步」→ **每次提交都被拦，且给出的理由是错的**。
这类「失败得很像另一个问题」最难排查。

**建议**：① 钩子改为 `PY=$(command -v python3 || command -v python)`，并对非零退出码区分处理；
② 文档补一句「Windows 用 `python`」，或在本机装 `python3` 垫片。
（CI 在 ubuntu 上不受影响。）

---

### 🔴 E3 — 文档教的 `local.properties` 写法在 Windows 上必然让 `lintDebug` 失败

**位置**：`CONTRIBUTING.md` 的 `echo "sdk.dir=/path/to/Android/Sdk" > local.properties`；
`AGENTS.md` / `docs/multi-session-collaboration.md` §2.3 的「每轮必跑全套」

**现象（实测）**：
```
local.properties:2: Error: Windows file separators (\) and drive letter separators (':')
must be escaped (\\) in property files; use C\:/path/to/Android/Sdk [PropertyEscape]
```
→ `lintDebug` **BUILD FAILED**，即项目规定的「每轮必跑」命令在 Windows 上根本过不去。

**两个关键结论（都实测过）**：

1. **转义写法能过**：`sdk.dir=C\:/path/to/Android/Sdk`（冒号前加反斜杠）→ lint **0 errors**。
2. **lint 会缓存这个分析结果**：改完 `local.properties` 后不清 `app/build/intermediates/lint*`，
   lint **继续报旧错误**（我被它骗了两轮）。这一条值得写进文档 ——
   否则下一个人会以为「我明明改了怎么还报」。

> 实测补充：**完全不建 `local.properties`**（改用 `ANDROID_HOME` 环境变量）lint 也干净。
> 两条路都行，文档至少要说清 Windows 该走哪条。

**建议**：`CONTRIBUTING.md` 的 Windows 段落写清转义写法 + 提示 lint 缓存；
或在 `app/lint-baseline.xml` 里显式记录 `PropertyEscape` 的处置理由。

---

### 🟠 E4 — 两道自动化闸门在 Windows 上是关闭的

**位置**：仓库根（无 `.gitattributes`）、`.git/hooks/`（实测只有 `*.sample`）、`tools/install_hooks.sh:54`

**现象**：

- **钩子从未安装** → pre-commit 的敏感信息审计、`prepare-commit-msg` 的 `Session-Start` **全关**。
- `install_hooks.sh` 用 `ln -s`，而本机 `core.symlinks=false` → 即使装了也可能退化成**副本或 `.lnk`**，
  脚本仍会打印「已链接」，但**改了 `tools/git-hooks/*` 不生效**。
  （具体落到哪个分支**待确认** —— 值得单独在临时目录验一次。）
- 本机 `core.autocrlf=true` 且无 `.gitattributes` → 实测工作区文件**全是 CRLF**
  （`gradlew`、`tools/session.sh`、`tools/git-hooks/pre-commit` 均如此，blob 是 LF）。

**现状不是立刻致命**：MSYS 的 `sh` 容忍 CRLF（实测 `sh -n` 全过、`tools/session.sh` 与
`tools/pre_push_audit.sh --staged` 实跑通过）。但换机器/改 `autocrlf` 就会产生**整库假改动**，
与 §5.15 记录的 `100755→100644` 是同一类坑，也是 Git for Windows 钩子的经典故障源。

**建议**：加 `.gitattributes`（`* text=auto eol=lf`，至少覆盖 `*.sh` / `gradlew` / `tools/git-hooks/*` / `*.py`）
后 `git add --renormalize .`；用 `readlink` 验证钩子是不是**真**软链，副本模式下打印「改动需重装」。

---

### 🟠 E5 — aapt2 被强制指向一个**不存在**的 Maven 分类器

**位置**：`app/build.gradle.kts:149-155`

```kotlin
// Force use of ARM64 binaries for AAPT2 in Proot environment
useTarget("com.android.tools.build:aapt2:${'$'}{requested.version}:linux-aarch64")
```

**实测（逐个版本核过 Maven）**：

| aapt2 版本 | `linux` | `windows` | `linux-aarch64` |
|---|---|---|---|
| `8.1.0-10154469` | 200 | 200 | **404** |
| `8.6.0-11315950` | 200 | 200 | **404** |
| `9.0.0-14304508`（AGP 9.0.0 在用） | 200 | 200 | **404** |
| `9.0.0-rc01-14304508` | 200 | 200 | **404** |

即：**Google 从未发布过 `linux-aarch64` 分类器**。
Windows 上构建**没有被它绊住**（实测 `assembleDebug` 通过），说明它实际上未生效 ——
但这意味着它是**误导源**：维护者会以为构建依赖它，而真正的 aarch64 方案在
`setup_android_env.sh`（用捆绑二进制覆盖）那边。

**建议**：在非 aarch64 环境**显式关掉**（用 `OperatingSystem`/`os.arch` 判断），
或在注释里写明它已失效、真正的机制在哪。别让它继续声称「Force use of ARM64 binaries」。

---

### 🟠 E6 — README 的 build-tools 版本与 AGP 实际要求不符

**位置**：`README.md:285`（「compileSdk 35、build-tools **35.0.0**」）

**实测**：AGP 9.0.0 触发的自动安装是 **`build-tools;36.0.0`**：
```
Failed to install the following Android SDK packages as some licences have not been accepted.
     build-tools;36.0.0 Android SDK Build-Tools 36
```
**建议**：文档改为 36.0.0（或改成「以 AGP 自动选择为准」），并补一句「缺失时 AGP 会自行安装，
但需要 `$ANDROID_HOME/licenses/` 里已有许可」。

---

### 🟠 E7 — 两个设备侧脚本在 Windows 上不是「改路径」能救的

**位置**：`tools/install_debug.sh:51`（`find "$ANDROID_HOME/build-tools" -name aapt2`）、
`:67`（`/storage/emulated/0/...`）、`:78-91`（打印 `/data/local/tmp` + `pm install`）；
`tools/run_instrumented_tests.sh:1`（`#!/system/bin/sh`）、`:60`（`pm list instrumentation`）、`:134`（`am instrument`）

**现象**：① Windows 的二进制叫 `aapt2.exe`，`-name aapt2` **永远匹配不到**；
② `mkdir -p /storage/emulated/0/...` 在 Git-Bash 下会造出 `C:\...\storage\emulated\0\...` **垃圾目录**；
③ `pm`/`am`/`pidof` 在 Windows 无对应物；④ 本机 `adb` 不在 PATH（在 `$ANDROID_HOME/platform-tools/` 里）。

**建议**：这两个脚本保留设备侧版本，另加一层
`adb push ... /data/local/tmp/ && adb shell sh ...`，或直接改用 `gradlew connectedDebugAndroidTest`。

---

### 🟠 E8 — 发版辅助脚本在 Windows 上不可用（其中一个带真 bug）

**位置**：

- `tools/build_release_apk.py:93-111` 用 `glob("build-tools/*/apksigner")`，回退目录是
  `~/Android`、`/opt/android-sdk`、`/usr/lib/android-sdk` 等 Linux 路径 → Windows 上是 `apksigner.bat`，
  glob **永远命中不了**，最后 `:320` `sys.exit("找不到 apksigner")`。
- `tools/push_release.sh:56-72` 的 GIT_DIR/SRC_DIR 候选全是 `$HOME/wmb-git`、`/tmp/wmb-git`、
  `/storage/emulated/0/...`、`/sdcard/...`；`:103-113` 用 tar 管道跨目录同步；
  **`:175` 与 `:186` 调用了未定义的 `info()`**（脚本只定义了 `step/ok/warn/die`）。
  `set -u` 不会因此退出，所以只是噪音 —— 但这恰好说明**这条推送路径从没被真正跑过**。

**建议**：`apksigner` glob 放宽为 `apksigner*` 并优先 `.bat`，或要求 `APKSIGNER` 环境变量；
`push_release.sh` 在单份代码以后**删掉 tar 同步段**（属删除功能，不是重写），补上 `info` 或改用 `step`。

---

### 🟠 E9 — `export_changelog.py` 没有干跑开关，一调就覆写发布历史

**位置**：`tools/export_changelog.py`（`:26` 的用法说明只写了无参形式）；
`docs/RELEASE_PROCESS.md:450` 把它列进速查表

**真实事故（本轮）**：执行 `python tools/export_changelog.py --help` →
**直接重写** `docs/changelog.json`，从 13 个版本砍到最新 `2.1.0`，**删掉 108 行**
（`v2.1.1` / `v2.2.0` 不是 tag 推导得出来的）。
已用 `git checkout -- docs/changelog.json` 还原，还原后 `git status` 为空（已核对）。

**建议**：加 `--dry-run` / `--yes`，**默认只打印不落盘**；速查表标注「会覆盖 `changelog.json`」。

---

### 🟡 E10 — JDK 版本文档与实际不符

**位置**：`README.md:284`、`.github/workflows/build.yml`（JDK 17）

**实测**：本机 `JAVA_HOME` 指向 **JDK 21**，PATH 上的 `java` 是 **25**；构建在 21 下**通过**。
两套 JDK 并存还会带来「命令行 java 与 Gradle 用的 java 不是同一个」的排查噪音。

**建议**：文档写明「17 为最低基线，21 亦可」，或在仓库里固定 `toolchain`。

---

### 🟡 E11 — `setup_android_env.sh` 缺平台警告

**位置**：`setup_android_env.sh:187/194/197/207`（`apt-get`）、`:274`（`yes | sdkmanager --licenses`）、
`:546-596`（用捆绑的 `tools/aapt2/aapt2-arm64-v8a` 覆盖 SDK build-tools 与 Gradle 缓存里的
`aapt2-*-linux.jar`，`:383` 注释点名 proot）

`CONTRIBUTING.md:156-165` 已把它隔离在「手机端（Termux / proot，aarch64）」小节 —— **不算矛盾**，
但是在 Windows/macOS/x86 上**绝对不能跑**（会把 ARM64 二进制塞进工具链）。
另外 `tools/aapt2/` 被 `.gitignore:44` 排除 → 纯克隆后 `:549` 的兜底必然失败。

**建议**：文件头部加「仅 aarch64 Linux / Termux；其他平台请勿执行」，CONTRIBUTING 同段加反向警告。

---

## 2. 代码新发现（本轮静态审查）

> ⚠️ 本节全部为**代码阅读结论**，未在真机上复现。`WatermarkRenderer.kt`（1007 行）的
> 9 个绘制分支内部几何数学**未逐行审**。
> 上一轮欠账见 `docs/code-review-2026-10.md`（15 项，13 修 2 不改），本节与之不重叠。

### 🔴 C1 — 检测页缺并发令牌：结论可能挂到另一张图上

**位置**：`app/src/main/java/com/watermarkbox/ui/screen/detect/DetectViewModel.kt:58-65`

`detect(uri)` **无令牌、无 Job 取消、无 `isDetecting` 防重入**（同文件 `recheck():82` 有防重入 —— 漏的是 `detect`）。

**为什么是严重问题**：检测页的语义就是「**这张图**有没有本应用水印」。
选中 A 未跑完再选 B 时，A 的协程后完成会把 `result` 覆盖成 A 的结论，而 `_uiState.imageUri` 已是 B ——
**等于直接给用户错误答案**。

**建议**：照 `app/src/main/java/com/watermarkbox/ui/screen/batch/BatchViewModel.kt:669-672` 的
`previewToken` 写法加 `detectToken`，或在 `detect()` 里存 `Job` 并 `cancel()`；写状态前比对令牌。
约 4 行改动。

---

### 🔴 C2 — 预览内存口径失真：按 4096 边解码，却仍按 1024 边记账

**位置**：

- `app/src/main/java/com/watermarkbox/util/BitmapUtils.kt:115` `const val PREVIEW_MAX_EDGE = 4096`
- `app/src/main/java/com/watermarkbox/data/repository/ImageRepository.kt:47`
  `previewCache = LruCacheMap<Uri, Bitmap>(capacity = 2)`
- 与之矛盾的旧文案：`app/src/main/java/com/watermarkbox/util/BitmapBudget.kt:8`
  （「预览会降采样到 **1024** 边（约 3 MB），所以预览永远不会炸」）、
  `ImageRepository.kt:37`（「每张预览图最大边 **1024** → 约 4 MB」）

**为什么是问题**：单张 4096 边 ARGB_8888 ≈ 48 MB，缓存 2 张 ≈ **96 MB 常驻**；
而 `BitmapBudget` **只拦导出/批量的 `loadFullResolution`，完全不覆盖预览路径**。
叠加 `app/src/main/AndroidManifest.xml:57` 的 `largeHeap`，
进程峰值 = 预览 96 MB + 导出 3×48 MB，**中低端机是真实 OOM 面**。
更危险的是「预览永远不会炸」这句注释会让人**不去防它**。

**建议**：① 先把 `BitmapBudget.kt:8` 与 `ImageRepository.kt:37` 的过期数值改成 4096 的事实；
② 缓存改按**字节上限**而非张数限容；③ 再决定 `PREVIEW_MAX_EDGE` 是否回收。
（预览改 4096 是 2.2.0 的既定决策，见 `docs/multi-session-collaboration.md` §5.18 —— 这里只要求**口径对齐**。）

**实际做法（2026-10-16 修复）**：本项按「**口径对齐**」收口，未动既定的 4096：

- `util/BitmapBudget.kt` 新增 `previewCacheCapacity(maxEdge, maxHeapBytes = Runtime.maxMemory())`：
  单张按 **4:3**（而不是正方形，否则高估 1/3）估 `bytesOf(maxEdge, maxEdge*3/4, 1)`，
  取预算的 `PREVIEW_CACHE_SHARE = 0.35` 除以单张大小，夹到 `[1, PREVIEW_CACHE_MAX_IMAGES=3]`。
  校核：512 MB 堆 → 2 张（与过去写死 2 的体感一致）；256 MB 堆 → 1 张。
- `data/repository/ImageRepository.kt` 的 `previewCache` 改为
  `LruCacheMap(capacity = BitmapBudget.previewCacheCapacity(BitmapUtils.PREVIEW_MAX_EDGE))`，
  并删掉写死的 `PREVIEW_CACHE_CAPACITY` 常量与「1024 边 → 4 MB → 3 张 ≈ 12 MB」那段过期 KDoc；
  同时删掉 `BitmapBudget.kt` 顶部「预览会降采样到 1024 边……所以预览永远不会炸」的错误结论。
- **未做**建议里的 ②「按字节上限限容」：那要给 `LruCacheMap` 加 `sizeOf`，
  改动面比缺陷本身大，本轮只把张数变成算得出来的（见上面的取舍说明）。
- 新增 6 个单测钉住 `previewCacheCapacity` 的边界（大堆 2 张 / 小堆 1 张 / 上限 3 / 下限 1 /
  非法边长兜底 1 / 默认参数可用），单测总数 468 → **474**。

---

### 🔴 C3 — 编辑页 `loadImage()` 无令牌，快速换图会乱序回写

**位置**：`app/src/main/java/com/watermarkbox/ui/screen/editor/EditorViewModel.kt:167-228`

每次 `viewModelScope.launch` 无令牌、无 Job 取消；`queryDisplayName`(:170)、`lowRes`(:186-193)、
`hiRes`(:221-223)、默认 logo(:245-250) 各自在**挂起点之后**回写状态。

**为什么是问题**：同一 VM 迅速换图（从批量页返回、连续选图）时，先发起的那次可以把
`previewBitmap` / `sourceFileName` 写乱 —— 而 `sourceFileName` 直接决定**导出文件名**（导出路径 `:463`）。
同一缺陷批量页已用令牌解决，编辑器**没跟上**：属「已知模式的漏用」。

**建议**：编辑器对齐 `newPreviewToken()` 写法。

---

### 🟠 C4 — `typefaceCache` 无上限无淘汰

**位置**：`app/src/main/java/com/watermarkbox/watermark/WatermarkRenderer.kt:69`
`private val typefaceCache = HashMap<TypefaceKey, Typeface>(16)`

初始容量 16 但**无淘汰、无上限**；自定义字体每个 size 一个 `Typeface` 常驻并持有字体资源。
长会话里导入多款字体 + 反复调字号会持续增长（`Typeface` 也不参与 Bitmap 那套 LRU 回收）。

**建议**：换项目已有的 `util/LruCacheMap.kt`，或加上限 + 换字体时清理。

---

### 🟠 C5 — 全仓只有一处 OOM 兜底

**位置**：`app/src/main/java/com/watermarkbox/util/BitmapUtils.kt:374` `catch (oom: OutOfMemoryError)`

而 `loadBitmap()`（`BitmapUtils.kt:144`，**预览路径**）、`data/repository/ExportRepository.kt`、
`worker/BatchWatermarkWorker.kt:156` 的原图解码**都没有**对应兜底 →
在 OOM 边界上是**直接崩进程**，而不是「失败可重试 / 可提示」。

**建议**：把 OOM 兜底下沉到解码层共用，或在各调用方统一判定。

**修订（2026-10-16 动手修时核出，原判据有误）**：真实情况与上面的描述不同 ——

- 批量（`worker/BatchWatermarkWorker.kt`）与导出（`data/repository/ExportRepository.kt`）
  **本来就有 `catch (t: Throwable)`**，OOM 是 `Error` 也会被它吃掉，所以**不是「直接崩进程」**；
  真正的病灶是**兜成了「原因未知」**：用户看到「失败 N 张 / 原因未知」，
  而正确答案是「图太大、内存不够」—— 两者的处方相反（一个该换小图，一个该重试）。
- 真正**没有任何 catch** 的是频域检测路径 `invisible/WatermarkDetector.kt`
  （`loadFullResolution` 之外还要开 `FloatArray(w * height)`，旋转兜底再多一张副本），
  那里的 OOM 会顺着协程把进程带走 —— 也就是「点检测，应用闪退」。

**本轮实际修法**：① 检测侧补 `catch (oom: OutOfMemoryError)` → 返回既有的
`ERROR_IMAGE_TOO_LARGE`；② 导出侧在 `catch (t: Throwable)` **之前**加 OOM 分支，
清掉半成品后**原样抛给 ViewModel**，由 ViewModel 转成专门话术 `editor_error_export_oom`
（「内存不足……请先缩小图片再试」，中英各一条）；③ 批量侧的 OOM 由 `Throwable` 分支
单独判定为 `SOURCE_TOO_LARGE` 并打日志；④ 异常不再无声：三条路径都补了 `Log.e`。

**仍留在账上的部分**：`BitmapUtils.loadBitmap()`（预览路径）依旧没有 OOM 兜底 ——
预览走 ImageView/Compose 自带解码，OOM 面比导出小，且改它会碰到 C8 的边界，本轮未动。

---

### ✅ C6 — 更新安装链路静默吞错（已修）

**位置**：`app/src/main/java/com/watermarkbox/ui/screen/update/UpdateViewModel.kt`、
`app/src/main/java/com/watermarkbox/util/ApkInstaller.kt`、`app/src/main/java/com/watermarkbox/util/UpdateChecker.kt`

**原判据**：下载/安装 APK 失败无记录、无上报 → 用户看到「点了没反应」。

**实际做法（三处）**：

1. **分类不再是「一律网络失败」** —— 新增 `UpdateChecker.classifyFailure(t: Throwable)`
   （纯函数、可单测）：沿 cause 链找 `SocketTimeoutException`（→ `TIMEOUT`）/
   `UnknownHostException`、`SocketException`（→ `NETWORK`），再回退到消息前缀
   `HTTP nnn`（→ `HTTP`），其余 → `UNKNOWN`。`downloadInApp` 的 catch 改用它的结果。
2. **失败不再静默** —— `catch` 里 `Log.e(TAG, …, e)`；校验失败删不掉的 APK 也 `Log.w` 留痕。
3. **安装失败不再一律「去授权」** —— `ApkInstaller.install` 返回值由 `Boolean` 改成
   `InstallOutcome { Started / NoPermission / NoFile / FailedToLaunch }`。
   原来三种失败都压成 `false`，上层全进 `NeedPermission`，用户被引导去设置授权，
   可权限早给了、回来重试还是同一句提示（死循环）。现在只有 `NoPermission` 才引导授权。

**顺带修掉的文案缺陷**：`update_error_unknown` 原文是「检查更新失败（%1$s）」，
而 `SettingsScreen.kt` 的 `messageRes()` 分支**不带格式化参数**使用它 ——
`UNKNOWN` 一旦发生，界面上会原样显示 `%1$s`。已改为无占位符的「更新失败，请稍后重试」
（中英双份）。

**测试**：`app/src/test/java/com/watermarkbox/util/UpdateCheckerTest.kt` 新增 7 例
（超时 / 被包装的超时 / DNS / 连接被拒 / HTTP / 写盘失败归 UNKNOWN / 异常链自引用不挂死）。

---

### 🟠 C7 — `DisposableEffect` 里的空 `onDispose {}` 掩盖意图

**位置**：`app/src/main/java/com/watermarkbox/ui/screen/settings/SettingsScreen.kt:168-185`

`DisposableEffect(lifecycleOwner, needPermissionAudit)` 内 `if (!needPermissionAudit) { onDispose {} }`；
observer 闭包捕获的是创建时那一版 `state.inAppDownload` / `awaitingPermission`，**靠 key 变化重建才自愈**。

**为什么是问题**：空 block 让「这里故意不注册观察者」看起来像遗漏；
后人若误改 `key` 会静默产生「权限状态永远不校正」。

**建议**：改成 `if (!needPermissionAudit) return@DisposableEffect`，或显式注释 key 依赖。

---

### 🟠 C8 — 378 行死代码正在冒充线上架构

**位置**：`app/src/main/java/com/watermarkbox/watermark/PreviewRenderEngine.kt`（231 行）、
`app/src/main/java/com/watermarkbox/watermark/PreviewBuffers.kt`（147 行）

**注意：这不是「没人知道」** —— §4 的近期完成与 §5.18 已经明确记录了「
`PreviewRenderEngine` / `PreviewBuffers` **保留但预览不再使用**」。
本项要说的只是：**决定保留了，却没有把配套的东西一起收口**。

**证据**：全仓生产代码**零引用** —— 只有它们自身、`WatermarkRenderer.kt:214` 的 KDoc 提到、
以及两个测试文件（`app/src/test/java/com/watermarkbox/watermark/PreviewRenderEngineTest.kt`、
`PreviewBuffersSlotTest.kt`）引用。
`WatermarkRenderer.kt:219` 的 `var reuseTarget: Bitmap? = null` **没有任何赋值点**，同属死机制。

**线上真实路径**是 `app/src/main/java/com/watermarkbox/ui/component/WatermarkPreview.kt:383`
在 Compose `Canvas` 里同步调 `renderer.drawInto(...)`(:392) —— 即水印绘制**在 UI 线程每帧跑**
（指令量小、成本可控，但**与引擎 KDoc 承诺的「UI 线程永远不做渲染 / 只保留最新请求」不符**）。

**为什么必须处理**：测试全绿会让人误判「预览渲染已工程化」，而实际链路是另一条；
同时这 378 行会持续消耗每一轮审查的预算 —— 本轮就重复读了一遍并得出错误架构结论。

**建议（三选一，别继续悬着）**：
① 删除两个类 + 对应的两个测试文件 + `WatermarkRenderer.kt:214` 的 KDoc 提及 + `reuseTarget`；
② 或保留代码但**改掉 KDoc 里「UI 线程永远不做渲染 / 只保留最新请求」的承诺**
（它与线上 `drawInto` 每帧同步绘制的事实相反），并把测试改名为「归档/待启用」；
③ 或真接回 `drawInto` 做节流。

---

### 🟠 C9 — 没有 DI 容器，边界靠自觉

**位置**：`ui/screen/settings/AboutScreen.kt:106`（Composable 内 `remember(...) { CreditsRepository(it) }`）、
`ui/component/WatermarkPreview.kt:169`（`remember { WatermarkRenderer() }`）、
`EditorViewModel.kt:131-135`、`BatchViewModel.kt:142-143`、`DetectViewModel.kt:41`（ViewModel 自建仓库）、
`ui/screen/presets/PresetsViewModel.kt:280/312`（直接取 `ContentResolver` 做 IO）

`CONTRIBUTING.md` 只明文禁「UI 直接碰 Bitmap 的 IO」，所以**这不是硬违规**，但无 `AppContainer` 会让测试与替换都难。

**建议**：引入最小 `AppContainer`；或在 `CONTRIBUTING.md` 补一条边界条款
（例如「ViewModel 允许自建仓库，Composable 不允许」），把现状**写成规则**而不是留在灰色地带。

---

### 🟡 C10 — 元数据与压缩失败静默，仍报「成功」

**位置**：`util/ExifCopier.kt`（`copyExif` / `writeUserComment` / `readUserComment` / `writeXmp` / `readXmp`
五处 `catch → 0/false/null`）、`worker/BatchWatermarkWorker.kt`（`embed` 返回 null 时 DCT 通道静默不写、
但任务记成功）、`data/repository/ExportRepository.kt`（忽略 `bitmap.compress()` 的 Boolean、忽略 ExifCopier 返回值）

**为什么值得管**：溯源是这项目的**核心卖点**，写入失败却对用户完全不可见。
用户会拿到一张「成功」但没水印的图。

**建议**：接入已有的 `BatchFailureReason` 体系，或至少落日志；若判定可接受，**在注释里钉住「为什么可以忽略」**。

---

### 🟡 C11 — 载荷没有版本字段

**位置**：`invisible/WatermarkPayload.kt`（`SYNC = 0xA5C3`，格式为 `SYNC + LEN + CRC + DATA`）、
`invisible/WatermarkSigner.kt:149-153`（现有 legacy 签名回退）

**现状**：载荷自描述但没有**格式版本号**；真要动格式时，缺一个机器可判的新旧区分点，
只能靠「解不出就当旧图」的隐式回退。

**补充（避免误伤）**：本轮逐条核过 `invisible/` 的常量 ——
`ReedSolomon.kt:30-39`、`InvisibleWatermarkEngine.kt:62/97-104/195/215`、`WatermarkSigner.kt:64`、
`WatermarkPayload.kt:40`、`InvisibleContentResolver.kt:166/169` —— **全部带「为什么是这个值」的注释，无裸魔数**。
此项只是「将来改格式时会不会踩」的结构性提醒，**不需要现在动手**。

---

## 3. 文档一致性欠账

### 🟠 D1 — 两个 `2.6` 撞号（第三次）

`docs/multi-session-collaboration.md:170` 是 `### 2.6 git 钩子（自动生效，别绕过）`，
`:211` 是 `## 2.6 会话工作区（三层方案的第 1、3 层）` —— **同号、且层级不同**。
上一个提交 `488f2d6` 的标题正是「清掉 4 处文档欠账（§7 过时流程 / **§2.5 撞号** / …）」。

连锁影响：`AGENTS.md:93` 与 `docs/multi-session-setup-prompt.md:8` 引用「§2.6」时，
读者无法判断指 worktree 那节还是钩子那节（`:322/:890/:925/:989` 同样歧义）。

**建议**：worktree 那节升为独立顶级编号（`## 2.7` 或并入后续序列），`### 2.6 git 钩子` 保持不动；
并把规矩写成「新增章节前必须 `grep -n '^#\{2,4\} '` 全文查号」。

### 🟠 D2 — §1 的 `cp` 双份同步与 §2.6 的 worktree 流程并存矛盾

`docs/multi-session-collaboration.md:59-87` 仍在教 `cp -r "$WORKSPACE/app/src/." "$GIT_REPO/app/src/"`
和 `diff -rq` 反向核对，把双份同步当**现行规则**；而 `:322`、`:890`、`:925` 明说「别再手动 cp」。
**建议**：§1 只保留两个占位符的**定义**，cp 段整段降级为「历史记录 / 已废弃」。

### 🟠 D3 — 「开工三步 / 必做 3 件事」已在三处过时

`CONTEXT.md:4`、`docs/multi-session-collaboration.md:1063`、`docs/multi-session-setup-prompt.md:60/140`
都还在引用它，而 `AGENTS.md:38` 明写那一条命令**顶替了**「必做 3 件事」。
**建议**：三处统一改成「一条开工自检命令」。

### 🟡 D4 — `./gradlew` 与 `sh gradlew` 两套写法并存

`docs/RELEASE_PROCESS.md:28/268/271/552` 与 `:443-446` 速查表用 `./gradlew`；
`AGENTS.md:131`、`CONTRIBUTING.md:162`、`multi-session §2.3` 要求 `sh gradlew`（理由是 FUSE 无执行位）。
实测本机 `gradlew` blob 是 `100755`、`sh -n` 通过，**不是立刻炸**，但两套写法会让「为什么有人这么写」永远解释不清。
**建议**：统一 `sh gradlew`（Linux / Windows / FUSE 都成立），`RELEASE_PROCESS.md` 全文替换。

### 🟡 D5 — 链接指向不入库文件 + Linux 专属路径进了跨平台文档

- `README.md:467` 写作 `[docs/build-artifacts.md](build-artifacts.md)`（相对路径也错），
  而该文件被 `.gitignore:55` 排除 → GitHub 上**必然 404**。
  同类引用还有 `AGENTS.md:64/116`、`CONTEXT.md:96`、`multi-session §…:44/54/637/1080`、
  `docs/RELEASE_PROCESS.md:433/576`、`docs/multi-session-setup-prompt.md:175`。
- `docs/RELEASE_PROCESS.md:31/339/362/556-557`（`/tmp/commit_msg.txt`）、`:433` 与 `README.md:461`
  （`/storage/emulated/0/...`）属 Linux / 设备专属路径。

**建议**：统一标注「本机文件（不入库，克隆后不存在）」，路径改用 `$TMPDIR` / `$BUILD_DIR` 占位符。

### 🟡 D6 — Operit 手机端的工具语义写进了通用流程

`docs/multi-session-collaboration.md:704-709`（「AI 工具读 ext4 必须显式传 `environment=linux`」、
「`grep_code`/`find_files` 不支持 `environment=linux`」）、`:934`（§7 工作流里同样要求）。
这是手机端工具参数，在 Windows / DSH 环境里不存在。
**建议**：标注「Operit 手机端专有」，或改写成通用表述。

---

## 4. 建议修复顺序

> **进度（2026-10-26 第四轮）**：**P0 全部完成**（E2 / E3 / E4 / C1），
> **P1 全部完成**（C2 / E1 / E5），**P2 完成 C3 / E9（C8 按决策保留）**，
> P3 完成 E6–E8、E10、E11、**C5、C6、C7**，**P4 的 D1–D6 全部完成**。
> 剩余：**C4（`typefaceCache` 上限，需先定长会话策略）**、**C8（保留决策，非缺陷）**、
> **C9（DI 重构）**、**C10（导出主链路）**、**C11（高危区载荷格式）** —— 即 §0 列出的 5 项。
>
> 每完成一批请同步更新本条进度；本轮代码类改动已跑全量构建 + 481 单测 + lint（见 §6）。

| 批次 | 内容 | 理由 |
|---|---|---|
| **P0** | E2、E3、E4 | 都是**静默失败**类：不修就会让下一个会话得出错误结论（尤其 E2 会把 Python 挂掉误报成「工作区未同步」）|
| **P0** | C1 | 唯一会产出**错误结论**的代码缺陷，约 4 行 |
| **P1** | C2、E1、E5 | 真实 OOM 面 + 两条「假绿」防线 |
| **P2** | C3、C8、E9 | 已知模式的漏用 / 死代码定性 / 防误写 |
| **P3** | E6、E7、E8、E10、E11、C4–C7、C9–C11 | 适配与健壮性，可批量做 |
| **P4** | D1–D6 | 文档欠账，建议单独一个 `docs` 提交 |

---

## 5. 本轮未覆盖（**别默认已全查**）

- **未逐行读完** `app/src/main/java/com/watermarkbox/watermark/WatermarkRenderer.kt`（1007 行）——
  只审了 `render` / `drawInto` / `outputFor` / `reuseTarget` / 入口签名与类型缓存。
- **未审**：`ui/component/DraggableBottomPanel.kt`、`PanelDragHandle.kt`、`Controls.kt`、`ui/onboarding/*`、
  除 `SettingsScreen` / `WatermarkPreview` 外的所有 Screen 排版代码、`ui/theme/*`；
  `util/{ApkVerifier,ExifTextCodec,XmpCodec,PresetFileCodec,MarkdownParser,UpdateChecker,PlaceholderResolver,FileNameResolver,AppLanguage,UiMessage}.kt`
  的逐行内容；`data/model/*` 全部文件。
- `invisible/` 只核了**常量、注释、签名与错误处理**，**未复核算法数学与 DCT 系数正确性**。
- **未审** Gradle 配置细节、ProGuard/R8 规则、`res/values*/strings.xml` 中英对齐、`assets/tutorial*.md`。
- ~~**未跑**仪器化测试~~ —— ⚠️ **已过时，2026-10-26 跑完了**：`sh gradlew :app:connectedDebugAndroidTest`
  在 Android 15 / x86_64 模拟器上 **57 例全部通过**（0 失败 / 0 跳过 / 约 1m35s），明细见 §6「第四轮」。
  但**仍未逐行读过**用例内容 —— 验的是「跑得过」，不是「断言写得对不对」。
- **未在设备上复现** C1–C11 中的任何一条；触发概率依赖用户操作路径，只有静态证据。
  （模拟器已可装包启动，但 §2 的 5 项修复都需要**人在界面上操作**才能触发，见 §6「第四轮」的手工清单。）

---

## 6. 事实记录：Windows 环境已跑通（不含项目缺陷）

> 这一节**不是待办**，是给下一位在 Windows 上开工的人的**已知有效路径**。
> 本机具体取值见 `docs/build-artifacts.md`（不入库）。

**实测可用的全量验证（与 §2.3 同一条命令）**：

```sh
cd "$WORKSPACE"
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain
# → BUILD SUCCESSFUL
#   单测 468 / 0 失败 / 0 错误（与文档基线一致）
#   lint 0 errors, 2 warnings（6 条被 lint-baseline 豁免）
#   app-debug.apk ≈ 22.9 MB，package=com.watermarkbox.final versionCode=14 versionName=2.2.0
```

**第二轮（2026-10-16，落实「划界支持」后）复验** —— 加 `--rerun-tasks` 强制全部任务真执行：

```sh
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain --rerun-tasks
# → BUILD SUCCESSFUL in 2m 30s，53/53 任务 executed（无 up-to-date 复用）
#   单测 43 个测试类 / 468 tests / 0 failures / 0 errors
#   lint 0 errors, 2 warnings
#   app-debug.apk 22,941,018 字节，package=com.watermarkbox.final versionCode=14 versionName=2.2.0
```

同轮另跑：`sh tools/pre_push_audit.sh` → 5/5 全通过（含「`.gitattributes` 存在且设置了默认行尾规则」）；
`python tools/clean_doc_latex.py --check <改动文档>` → 干净；`python tools/check_release_consistency.py` → 三处版本一致。

**第三轮（2026-10-16，修 E9 / C2 / C3 / C5 / C7 后）复验**：

```sh
cd "$WORKSPACE"
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain
# → BUILD SUCCESSFUL in 2m 13s，53 个任务（24 executed / 29 up-to-date）
#   单测 43 个测试类 / 474 tests / 0 failures / 0 errors / 0 skipped
#   —— 468 → 474，多出的 6 个是 C2 新增的 previewCacheCapacity 边界用例
#   lint 0 errors, 2 warnings（6 条被 lint-baseline 豁免）
#   app-debug.apk = 22,941,594 字节，package=com.watermarkbox.final versionCode=14 versionName=2.2.0
```

**第四轮（2026-10-26，修 C6 后）复验**：

```sh
cd "$WORKSPACE"
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain
# → BUILD SUCCESSFUL in 2m 11s，53 个任务（25 executed / 28 up-to-date）
#   单测 43 个测试类 / 481 tests / 0 failures / 0 errors / 0 skipped
#   —— 474 → 481，多出的 7 个是 C6 新增的 classifyFailure 分类用例
#   （UpdateCheckerTest 单文件 37 例）
#   lint 0 errors, 2 warnings（6 条被 lint-baseline 豁免）
#   app-debug.apk = 23,886,616 字节（增量构建，按上面的表不可与 22,941,594 直接比）
```

⚠️ **APK 体积的读法（本轮实测，影响上面所有「≈22.9 MB」的数字）**：debug 包的字节数
**不是构建间完全一致**的，别把某一次的精确字节当基线：

| 构建方式 | 实测字节 |
|---|---|
| 全量 `--rerun-tasks`（连跑两次） | **22,941,594**（两次一致）；更早一次 22,941,018 |
| 增量构建（24 executed / 29 up-to-date） | **23,886,028**（多出约 945 KB） |

即**增量构建的 debug 包会明显偏大**（dex/合并产物没重算干净）。要对比体积或产物内容时，
请一律先跑 `--rerun-tasks`，否则会把「增量残留」记成「代码变胖了」。

E9 的验证方式（随手改脚本的人请照抄这个姿势）：

```sh
md5sum docs/changelog.json                 # 先记指纹
python tools/export_changelog.py           # 干跑：exit 0，打印「行数 681 → 574」，不写盘
md5sum docs/changelog.json                 # 指纹不变 = 安全
python tools/export_changelog.py --help    # 现在有 -h，不会再「一调就重写」
```

⚠️ **顺带发现（未修，也不是缺陷）**：干跑显示**重建结果与现存的 `docs/changelog.json` 不一致**
（现 681 行 → 重建 574 行）。原因是该文件在导出脚本之外被**手工维护**过。
所以 `--apply` **不要**随手跑 —— 它会用「由 `ReleaseHistory.kt` + `strings.xml` 重建」
的结果覆盖人工维护的内容。真要用，先 `--out` 输出到临时文件比对差异再决定。

**第四轮（2026-10-26）：实装测试首次跑通（模拟器 + 仪器化 57 例）**

> 结论：**Android 15 / x86_64 模拟器上，应用能装、能起、57 个仪器化测试一次全绿。**
> 这是本项目**第一次**在本机把 `app/src/androidTest` 真正跑起来（此前只有「按文件名统计过 57 个」）。

```sh
# 1) 装包（debug 变体，包名 com.watermarkbox.final）
export MSYS_NO_PATHCONV=1          # ← Git-Bash 必须，否则 adb 的 /sdcard 路径被改写
adb install -r app/build/outputs/apk/debug/app-debug.apk
#   → Success: streamed 22941594 bytes，primaryCpuAbi=x86_64（说明 x86_64 .so 匹配成功）

# 2) 起应用（注意：launchable-activity 是 com.watermarkbox.MainActivity，
#    不是 .MainActivity —— 用 `aapt2 dump badging | grep launchable-activity` 查）
adb shell am start -n com.watermarkbox.final/com.watermarkbox.MainActivity
#   → Displayed ... +1s846ms，pidof 有值，crash buffer 为空

# 3) 仪器化测试（模拟器不是 MIUI，**不需要**分批脚本）
ANDROID_SERIAL=<serial> sh gradlew :app:connectedDebugAndroidTest -PparallelSuffix=.final
#   → BUILD SUCCESSFUL in 1m 35s
#     57 tests / 0 failed / 0 skipped
```

分项（8 个测试类）：`ApkVerifier` 8 · `ExifChineseEncoding` 2 · `ImageWatermarkRenderer` 7 ·
**`InvisibleWatermark` 14** · `PreviewDerivedReactivity` 7 · `PreviewFitMeasurement` 5 ·
`RenderPerformance` 6 · `WatermarkRenderer` 8。

**模拟器的能力边界（实测口径，别记错）**：

| 能替代真机 | 参考价值低 | 仍需真机 |
|---|---|---|
| 功能正确性、并发竞态、仪器化全套 | **OOM**（堆上限与真机不同，要测需把实例内存调小）、卡顿体感 | 相册 / SAF / 存储权限对话框、厂商相册落盘与 EXIF 归属 |

⚠️ 两条会踩的坑（已写进 `docs/multi-session-collaboration.md` §2.7「Windows 已知坑」7 / 8）：
① Git-Bash 会把 adb 的 `/sdcard` 当 Windows 路径改写 → 加 `MSYS_NO_PATHCONV=1`；
② 模拟器实例**首次启动**可能报 `VERR_NO_PAGE_MEMORY`（并非真的缺内存）→
`shutdown` 等进程退干净再 `launch`；另 `adb devices` 会同时列出 `emulator-5554` 与
`127.0.0.1:<port>` **两个 transport**（同一台），跑 connected 测试前 `adb disconnect` 掉一个或指定 `ANDROID_SERIAL`。

**仍待人工在界面上验的 5 项**（模拟器已备好，测试图已 push 到 `/sdcard/Pictures/WMBTest/`）：
C1 检测换图竞态 · C3 编辑页快速换图 · C2 预览缓存张数 · **C5 超大图 OOM 话术** · C7 设置页状态自校正。

**已就位**（记录备查）：

1. Gradle 9.1.0 发行包 —— 官方源实测约 **105 B/s**（不可用），改走国内镜像约 **6.4 MB/s**。
2. `$ANDROID_HOME` 下的 `platforms/android-35`、`platform-tools`；
   `build-tools/36.0.0` 由 **AGP 自动安装**（前提是 `$ANDROID_HOME/licenses/` 已有许可文件）。
3. `sdkmanager` 直连仓库会报 `Failed to connect to remote repository`，
   但 AGP 自己的下载器在同样网络下**能成功** —— 即该目标「时通时不通」，**不要依赖**。
4. `local.properties` 用**转义写法**（见 E3）。
5. **本机前提**：系统级 `Path` 中曾混入一个未配对的 `"`，会让 Gradle fork 的测试 JVM
   命令行被切断，报出「找不到或无法加载主类 <PATH 片段>」。**已由人类修复**；
   注意 Windows 只对**修复之后启动的进程**生效 —— 修复前已运行的宿主进程（及其子终端）
   仍持有旧值，需**重启该宿主**才能自动拿到干净环境。
6. **`sh` 不在 Windows `PATH` 上**：Git for Windows 默认只把 `Git\cmd` 加进 `PATH`，
   该目录里只有 `git.exe` / `git-lfs.exe` 等，**没有 `sh.exe`**（`sh.exe` 在 `Git\bin`）。
   于是文档里的 `sh <脚本>` / `sh gradlew ...` 在 PowerShell / cmd 里会报
   `The term 'sh' is not recognized`。三种跑法见
   `docs/multi-session-collaboration.md` §2.7「Windows 已知坑」第 6 条。
   ⚠️ 这属于**平台使用姿势**，不是项目缺陷 —— 别为了它去改系统 `PATH`。
7. **日志**：`Formatting [Incubating]`、`Problems report` 之类提示是 Gradle 9 的正常噪声。

---

## 7. 批量序号版（2026-10-26）：本版引入 / 关闭

本版按 [docs/plan-2026-10-batch-index.md](plan-2026-10-batch-index.md) 实现「文件名序号 + 失败重跑保号 + 分享成功项」。与清单的交集只有下面这些：

**关闭**

- **R7 / 单张导出带 `{index}` 的烂尾**：`FileNameResolver` 重写后，
  `index == null` 时整段占位符（连同紧邻的分隔符）一起删除，不再残留空括号或双下划线。

**本版新引入、需要盯的**

1. **同名冲突未实测**（§6.1 of 计划）：重跑保号后 MediaProvider 会存成 `xxx (1).jpg`。
   序号对、文件名多后缀 —— 计划是接受并写进文案，但**必须在真机/MuMu 上确认实际表现**，
   不能凭文档断言。确认前不要向用户承诺"重跑不重名"。
2. **`KEY_FAILED_URIS` 已删除**：10KB 上限的风险随之消失，但**如果还有外部脚本读
   Worker 的 outputData，会读不到**。全仓 grep 过，无消费者；以后加消费者请走 `BatchRunStore`。
3. **`filesDir/batch-runs/` 只保留最近 3 份**（`BatchRunStore.prune`）：
   分享按钮依赖这份清单，理论上「跑完 3 批后再回来点第 1 批的分享」会读不到。
   实际交互上结果页是模态的，跑不到那个时序；若将来做「历史批次」再改成按需清理。

---

## 8. 首页自适应版（2026-10-26）：本版引入 / 关闭

本版起因是一个**用户可见缺陷**（老大提的）：小屏手机上首页第三张入口卡片被挤压成扁条、难点。
与清单的交集只有一条 R8。

**关闭**

- **R8 / 首页第三张卡片被压扁**：`HomeScreen.kt` 重写，卡片高度改为**按可用高度推导**
  并设可点下限（竖排 88dp / 并排 140dp）；空间不足时按「缩 Logo → 藏副标题 → 藏页脚 →
  最后才滚动」的顺序让位。根因与规则见 `multi-session-collaboration.md` §5.20。

**本版实测矩阵**（MuMu 实例 0，`wm size` 固定 1080×1920 + `wm density` 调 dp，非估算）

| dp 尺寸 | 对应设备 | 结果 |
|---|---|---|
| 360×640（原问题尺寸） | 5 寸 1080p@480dpi | 三卡等高、全可见，不再压扁 |
| 411×731 | 主流手机 1080p@420dpi | 同上 |
| 288×512（极窄矮） | 老小屏 | 三卡 + 页脚仍全可见，副标题换两行完整显示 |
| 320×480 | 4 寸老机 | 同 288×512（走同一分支）|
| 720×1280 / 800×1280 | 7 寸 / 10 寸平板竖屏 | 自动并排，三卡一行 |
| 731×411（手机横屏） | 横屏 | 自动并排 + 藏副标题与页脚，卡片仍 ≥ 140dp |

**本版还做了**

1. `app/src/test/java/com/watermarkbox/ui/screen/home/HomeLayoutMetricsTest.kt` 新增 **6 条**
   纯算术回归测试（13 个主流尺寸 × 卡片下限 / 内容不溢出 / Logo 单调 / 并排判据），
   单测 **505 → 511**。
2. **工作区行尾归一化**：213 个文本文件 CRLF → LF，本机 `core.autocrlf=false`。
   起因是 `tools/run_instrumented_tests.sh` 在设备上报 `syntax error: unexpected '|'`
   —— 续行符被 CRLF 打断。详见 `multi-session-collaboration.md` §5.21。
3. **`tools/run_instrumented_tests.sh` 补漏**：加入 `survives_right_angle_rotations` 与
   整个 `ApkVerifierInstrumentedTest`，设备侧用例 **44 → 53**（57 个 `@Test` 中只剩 4 个
   `diagnose_*` 实验用例不跑）。**此前有 13 例从未在设备上执行过。**

**本版新引入、需要盯的**

1. **并排布局下副标题会被省略号截断**：横屏与平板上第三张卡的文案
   （"检查图片是否含本应用写入的隐藏水印"）超出一行，显示为 "…写入的…"，最多 2 行。
   目前接受（副标题本来就是辅助信息，入口标题完整）。若将来要完整显示，
   应**缩短文案**而不是加高卡片 —— 加高会挤掉卡片本身。
2. **平板并排时三张卡的标题基线不齐**：第三张副标题两行、另两张一行。
   视觉上可接受，未处理。
3. **极矮屏（< 约 430dp 高）才出现滚动**：这让"三张卡一定同屏"不再是硬保证。
   这是**刻意的**：宁可滚，也不把可点区域压到 88dp 以下。
4. **8 个文件带纯行尾变更**（`build.gradle.kts`、两个 `res/xml/*_rules.xml`、
   `.github/PULL_REQUEST_TEMPLATE.md`、`README.en.md`、`docs/RELEASE_PROCESS.md`、
   `docs/baseline-profile.md`、`docs/multi-session-setup-prompt.md`）——
   提交时它们会混进 diff，注意别把这些当成逻辑改动去 review。
5. **`wm density` 调出来的 dp 不等于真机像素**：本版验证靠的是"改 density 模拟 dp"，
   真机上字体缩放（`fontScale`）也存在。**字体放大到 1.3× 以上的用户未实测**，
   副标题换行变多可能挤占卡片内容，属已知空白。

**复验（2026-10-26）**

```
assembleDebug + testDebugUnitTest + lintDebug   BUILD SUCCESSFUL（2m 04s）
单测 511 / 0 失败 / 0 错误（+6）
lint 0 errors, 2 warnings（均为既有，无新增）
设备侧仪器化 53 PASS / 0 FAIL / 0 SKIP
```

---

*本清单由 AI 会话维护。修完一项请更新 §0 状态列（✅ 已修 + 提交号），不要新开同名文档。*

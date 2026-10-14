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

> ⚠️ **先搞清楚「现在是什么状态」，再决定「我要做什么」。**

### 工作前必做：跑一条命令

```sh
cd "$GIT_REPO"
python3 tools/collab_status.py --workspace "$WORKSPACE"
```

**这一条命令顶替了过去的「必做 3 件事」**（看提交历史 / 看工作区与仓库差异 / 看状态）。

它一次输出：仓库 HEAD 与远端差异、未提交改动、未跟踪文件、
工作区↔仓库差异（区分「需同步的代码」与「本机临时文件」）、
**以及「与上次自检相比发生了什么变化」**。

> **为什么要专门做这个脚本**（三个真实失败，同一根因）：
>
> | 失败 | 后果 |
> |---|---|
> | 用 `git status -sb \| head -1` 看状态 | 漏看了 7 个未提交改动 |
> | 没核对 git 就直接写记忆 | 把「2.1.1 已发布」写进记忆 —— 而它**已被人类要求回退** |
> | 把 HEAD 写进状态表 | 提交后立即过期，形成死循环 |
>
> 根因是**口头规矩没有机制强制**。脚本解决了前两个：
> 一条命令降低"跳过"的概率；**快照对比**专门解决「我以为还是 A，其实变成 B」。

**退出码**：有「需要注意」的变化时为 `1`，否则 `0` ——
可放进自动化流程（`--quiet` 只在有变化时输出）。

> **路径说明**：`$GIT_REPO` / `$WORKSPACE` 的实际取值见 `docs/build-artifacts.md`
> （该文档含本机路径，已在 `.gitignore` 里，不入库）。

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

> ⚠️ **代码有两份，必须靠 `cp` 同步 —— 这是最容易出错的点**

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

> ⚠️ **未经人类所有者明示「推送」，绝不 `git push`**

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

### 2.5 改完要留注解（交接的燃料）

> ⚠️ **下一个人只有代码，没有你的上下文**

这是 `CONTRIBUTING.md`「注释」一节的**协同视角补充** ——
那条讲"写什么"（只写为什么，不写是什么），这条讲**为谁写、写到什么程度**。

**规则**：任何非平凡改动，都要在**被改的那一行附近**留下「为什么」。
接手的人（很可能就是另一个会话）看到的是**改完之后的世界**，
他不知道这里曾经有个坑、也不知道你权衡过什么。

本项目具体的习惯：

| 改了什么 | 至少留什么 |
|---|---|
| 修 bug | 症状 + 根因 + 为何这样修（带**实测数据**更好）|
| 改函数**签名 / 契约** | 同步更新 KDoc（`@param` / `@return` / 语义变化）——**最容易漏** |
| 加约束（禁止某种写法）| 明写「别改回去」+ 依据 |
| 有取舍（A vs B）| 为什么不选另一个 |
| 踩了环境坑 | 现象 + 正确做法（例：proot 里 `/data/local/tmp` 不可写，日志要写 `/tmp`）|

**反例（真实发生）**：一条「更新日志必须覆盖当前版本」的测试，
因为版本常量停在 `1.4.1` 而**永远通过**，结果 2.1.0 发布时漏加条目，
测试完全没能拦住（同类教训见 §5.6 / §5.9）。
这类「看起来有防线、实际没有」的地方，最需要注释点破。

> ⚠️ 别写成流水账。注释的价值在「解释反直觉的部分」，
> 不必为每行配一句中文翻译 —— 那是噪音，反而让人不读注释。

---

## 2.5 git 钩子（自动生效，**别绕过**）

仓库装了两个钩子，`sh tools/install_hooks.sh` 安装：

| 钩子 | 做什么 |
|---|---|
| `pre-commit` | ① **工作区同步检查**：工作区改了没同步到仓库 → **拦下提交**<br>② 敏感信息审计：本机绝对路径 / 密码 / 令牌 |
| `prepare-commit-msg` | 自动追加 `Session-Start: <时间>` —— 同一批工作的多个提交**共享同一时间戳**，便于日后分辨「这批改动属于哪次会话工作」 |

### 为什么加「工作区同步检查」

以前改完工作区要**手动 `cp`** 到仓库，**漏一次就提交了旧代码**
（本轮真实踩过，手打 cp 六次以上）。现在漏了会被钩子当场拦下。

### 为什么加「会话标识」

多会话并行时，翻 `git log` 分不清哪些改动属于同一次工作。

### ⚠️ 两个真实踩过的坑（都靠「故意制造未同步去测拦截」才发现）

> ⚠️ **钩子「装上了」不等于「会跑」—— 两种静默失效**

1. **悬空软链**：`install_hooks.sh` 里的相对路径写成 `../tools/...`，
   内核解析成 `.git/tools/...`（不存在）→ **钩子静默不执行**，
   但 `ls .git/hooks/` 看着它在，极具迷惑性。
2. **缺可执行位**：钩子源文件没有 `x` 权限 → git 直接跳过。

**修法**：路径改 `../../tools/...`；源文件 `chmod +x`；
并且 `install_hooks.sh` **安装后立刻自检**（目标可达 + 可执行），当场暴露问题。

### 别用 `--no-verify` 绕过

那会让这两道闸门形同虚设。
确实无需同步时（例如只改了本机文件），钩子会说明原因并给出正确做法。

---

## 2.6 会话工作区（三层方案的第 1、3 层）

### 为什么要用

以前所有会话**共用一份代码**，改完要**手动 `cp`** 到 git 仓库。三个后果：

1. 同步容易漏 → 提交旧代码
2. 改动互相覆盖 → 后同步的把前者的活冲掉
3. 日志分不清 → 不知道哪批改动属于哪次工作

**现在**：每个会话在**自己的 git worktree** 里干活 —— 它本身就是 git 工作树，
**「手动同步」这一步彻底消失了**。

### 用法

```sh
sh tools/session.sh new <名字>       # 创建：<仓库父目录>/wmb-wt-<名字>，分支 session/<名字>
sh tools/session.sh list             # 列出所有会话工作区
sh tools/session.sh review <名字>    # 合并前审查：改了什么 + 冲突预判
sh tools/session.sh merge <名字>     # 审查 → --no-ff 合并 → 全量测试
sh tools/session.sh rm <名字>        # 删除工作区（有未提交改动时**拒绝**）
```

**注意**：`merge` 冲突时**不自动裁决** —— 脚本停下来，由人类决断。
合并后**必跑全量测试**（两边改动可能语义冲突，静态看不出来）。

### 实测数据（落地前就先验过）

| 项 | 结果 |
|---|---|
| `.git` 大小 | 36M（worktree 共享它，开销很小）|
| worktree 里构建 | BUILD SUCCESSFUL in 34s |
| 需要本机配置 | **零** —— SDK 走环境变量；`keystore` 只在 release 时需要 |
| AI 工具访问 | `linux` 环境可直接读写 worktree 文件 |

### ⚠️ 五个实测踩到的坑（全是「看起来对、实际没跑」）

| # | 坑 | 症状 |
|---|---|---|
| 1 | `install_hooks.sh` 软链少一级 | **悬空软链** → 钩子静默不执行，但 `ls` 看着它在 |
| 2 | 钩子源文件缺可执行位 | git 直接跳过钩子 |
| 3 | `gradlew` 缺可执行位 | 合并后测试 `Permission denied` —— **合并本身却成功了**，极具迷惑性 |
| 4 | `merge` 把未跟踪文件也算作「不干净」 | 待脱敏文档让 merge 被**永久**误拦 |
| 5 | worktree 里 `.git` 是个**文件**（不是目录） | 会话状态路径写不进去，`Session-Start` 静默退回当前时间 |

> ⚠️ **共同教训：「装上了 / 配好了」≠「真的会跑」**
>
> 这条比任何一条具体规则都重要 —— 本轮所有坑都属于这一类。

**必须验证防线的实际行为**，而且要**在真实场景里验**：

- 验证「未同步会被拦」→ 故意制造未同步，去提交（本轮 1、2 号坑就是这样暴露的）
- 验证「合并后跑测试」→ 真的跑一次 merge（3 号坑）
- 验证「worktree 里钩子正常」→ 真的在 worktree 里提交一次（5 号坑）

只看着配置对、只跑 `sh -n` 语法检查，**发现不了这些**。

### 🔧 配套工具：`tools/clean_doc_latex.py`

AI 在**对话框里**会用 LaTeX 做卡片 / 高亮（界面能渲染），
但同一套写法写进 `.md` 后，在 GitHub 上**只是一堆原始命令**。
这个脚本负责把这类误用的渲染语法转成普通 Markdown：

```sh
python3 tools/clean_doc_latex.py --check docs/*.md   # 只报告
python3 tools/clean_doc_latex.py --fix   docs/*.md   # 就地转换
```

**写文档时请直接用普通 Markdown**（`**加粗**`、`> 引用`），
不要从对话框里整段复制带 `$$...$$` 的内容。

---

## 3. 当前状态（**每次工作前刷新这一节**）

> ⚠️ 这一节是**易变**的。你读到的内容可能已经过期 ——
> 以 `git log` 和 `git status` 的**实际输出**为准，本节的文字只是补充说明。

| 项 | 值 |
|---|---|
| **发版流程** | **`docs/RELEASE_PROCESS.md`（发版必须照它做）** |
| 本地 HEAD | 见 `git log`（本轮收尾：三层方案落地 + 文档渲染语法清理 + M3/M5 修复）|
| 远端 | `origin/main` = `b1a254e`（tag `v2.1.0`）—— **2.1.1 的推送 / tag / Release 已按人类要求全部回退** |
| **ahead** | **15（未推送；人类已明确不回推 2.1.1）** |
| 单测基线 | **436 个用例，全绿** |
| lint | 0 错误（6 条 baseline）|
| debug 包名 | `com.watermarkbox.final` |
| 版本 | 远端发布 **2.1.0**；本地代码已含 2.1.1（versionCode 13，**未推送**）|

**更新约定**：任何会话在一轮工作结束时，如果 HEAD 变了，
**顺手更新上表的 HEAD 与 ahead**（一行的事，但对下一个人很关键）。

---

## 4. 活跃任务看板

> 状态标记：`🔄 进行中` / `⏳ 等人类验证` / `📋 待办` / `✅ 已完成未推送`

### 📢 给其他会话的通知（重要变更）

> **如果你已经在干活**（不会重读 `AGENTS.md`），请花 30 秒扫一眼这里。
> 最新在最上面。

| 时间 | 变更 | 你该做什么 |
|---|---|---|
| 2026-10-14 | **`merge` 现在会跑 `assembleDebug`**（全量验证含打包） | 每轮 merge 因此**慢约 1 分钟**（实测 1m53s）—— 别以为卡住了。好处：**merge 全绿 = 能装机**。详见 `tools/session.sh` 的 `TEST_CMD` 注释 |
| 2026-10-14 | **协作流程改为 worktree 隔离**（`tools/session.sh`） | **别再手动 `cp` 同步**。新任务用 `sh tools/session.sh new <名字>`；已经在主工作区改到一半的，可按原方式收尾，但**下次请换新流程**（详见 §2.6） |
| 2026-10-14 | **`AGENTS.md` 已入库** | 它现在是 AI 会话的**入口文件**。如果你开工前没读过它，现在读一遍 |
| 2026-10-14 | **开工自检改为一条命令** | `python3 tools/collab_status.py --workspace "$WORKSPACE"`（取代过去的「必做 3 件事」） |

### 🔄 进行中

| 任务 | 负责 | 说明 |
|---|---|---|
| **导出内存预算修复的验证** | 等人类验证 | 见 §5.9，大图导出是否不再报「图片过大」|

### 📋 待办（需要人类拍板或处理）

| 任务 | 阻塞点 |
|---|---|
| README 截图 | 等人类提供真机截图 |
| Baseline Profile | 收益评估偏小，优先级低 |
| **推送本地提交** | **必须等人类明示** |
| **代码审查报告剩余项** | `docs/code-review-2026-10.md` 共 15 项，**已收口**：13 项已修（H1 / H2 / M1–M5 / L1 / L2 / L5 / L6 / L7 / L8），2 项复核后判定**不改**（L3 / L4，收益极小）。详见报告 §0 的「修订状态」表 |

### ✅ 近期完成（别惊讶于这些改动）

> **其他会话注意**：下面这些是**已完成的正常改动**。
> 如果你看到相关代码与你的印象不符，**大概率是这些**，不是 bug。

- **（本轮）`docs/rust-migration-feasibility.md` 已脱敏并入库**
  （提交 `33d4c41`，由其他会话完成；本机路径已换成 `$ANDROID_HOME` 等占位符）
- **（本轮）预览架构重构：底图/水印分离 + GPU 合成**（详见 §5.18）
  - 性能：每帧**只画水印**（不再整块拷贝底图）→ 拖动跟手
  - 画质：预览改用**原图分辨率**（`PREVIEW_MAX_EDGE` 720 → 4096）
  - 回归修复：水印层 `clipRect` 到图片区域（否则溢出画到图片外）
  - **渐进式加载**（§5.18.1）：先低清占位（720）→ 再高清替换（4096）——
    解决「上原图分辨率后首屏变慢」；低清**不写缓存**、命中缓存时跳过占位
  - 顺带清理：删 `rememberRenderedPreview` / `PreviewRequest` /
    `sourceWidth`/`sourceHeight` 参数链（新架构 base == target，不再需要传参）；
    `PreviewRenderEngine` / `PreviewBuffers` **保留但预览不再使用**

- **（本轮）更新说明改为在线获取 + 发布流程文档**（`docs/changelog.json` 成为唯一数据源；
  CI 加发版一致性校验）
  > ⚠️ 该批提交原本含「发布 2.1.1」，但其**推送 / tag / Release 已按人类要求全部回退**；
  > 代码本身仍完整保留在**本地**（`ahead 5`）。远端最新发布仍是 **2.1.0**。
  > 不要因为看到本地有 `versionCode 13` 就去推它。
- `ac812bb` 补 v2.1.0 更新日志 + 预览基准 EXIF 换算 + 批量预览竞态（详见 §8）
- `b1a254e` 发布 2.1.0（tag `v2.1.0`）
- `5bec32c` 描边颜色重叠 + 隐藏水印过渡动画 + 导出对话框作用域语义 + 字符串里的假 Markdown
- `3fc9091` 预览与导出的水印大小不一致（基准坐标系没接上）—— `ac812bb` 补修其 EXIF 旋转遗留
- `ac7bfca` 导出「图稍大就失败」：内存预算三重保守（largeHeap + 按需份数 + 系数）
- `e403895` 批量页预览卡顿：重组爆炸 + 降采样失效 + 分辨率过高（**三个独立原因**）
- `0c46fc8` / `afd79b6` / `793832a` / `c2f8d75` 预览频闪的**三次**修复（详见 §5.1）
- `ff02ab0` 检测设置两两并排 + 文案精简
- `b7cdc78` 检测按钮并排 / `07a7c6b` 新增重新检测按钮
- `b9e8868` DC-QIM 失真补偿 + 检测失败提示「可能旧版本导出」
- `0020c46` DCT 嵌入缺陷修复（整块缩放 → 逐像素裁剪）
- **（本轮）三层协作方案落地**：`tools/session.sh` + `CONTEXT.md` + 合并审查流程。
  实测揪出 **5 个「装上了但没跑」的 bug**（钩子悬空软链 / 缺可执行位 /
  `gradlew` 权限 / merge 误拦未跟踪文件 / worktree 里 `.git` 是文件）。
  并用它真实跑通一次任务（M3+M5 修复），端到端验证。
- **（本轮）文档渲染语法清理**：新增 `tools/clean_doc_latex.py`（含回归样例），
  清理 4 个文档共 21 处误用的 `$$...$$` 美化语法
- **（本轮）修 M3 + M5**：EXIF 编解码边界（空 payload / `B64:` 往返）+
  批量 Worker 两通道守卫不对称。单测 433 → **436**

---

## 5. 项目特有的「反直觉」知识点

> 这些是**踩坑换来的**。不了解它们，你会重复踩一遍。

### 5.1 预览频闪：一个修了三次的 bug

> ⚠️ **症状：拖动字号/角度/密度/透明度时预览闪烁，松手就正常**

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

> ⚠️ ****`calculateInSampleSize` 的 && 写法会让非正方形图**一点不降**

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

### 5.11 ⚠️ 终端不能用 heredoc（会卡住）

> ⚠️ ****多行命令会停在续行模式，**命令不执行**

```bash
# ❌ 这样写会卡住（shell 停在 ">" 续行提示符，永远等不到结尾）
cat > /tmp/msg.txt <<'EOF'
第一行
第二行
EOF
```

**原因**：终端工具在传递含换行的命令时，换行被当成"用户还在继续输入"，
于是 shell 一直等你输入结束标记 —— 而那个标记已经被当作普通文本处理了。

**怎么判断卡住了**：工具返回的输出里有 `>` 开头的提示符，且**没有命令结果**。

**怎么脱困**：用 `terminal_input` 发 Ctrl+C（`control=ctrl, input=c`）。

**正确写法**：

| 想做的事 | ❌ 别用 | ✅ 改用 |
|---|---|---|
| 写多行文件 | `cat > f <<'EOF'` | `create_file` 工具，或 `printf 'a\nb\n' > f` |
| 提交信息 | `cat > msg <<'EOF'` | 多次 `-m`，或先写文件再 `git commit -F` |
| 多行 Python | `python3 - <<'PY'` | `python3 -c "…"` 单行（用 `;` 分隔）|
| git 多行 message | heredoc | `git commit -m A -m B` |

> 这条**与平台无关**，纯工具链行为 —— 人类在真实终端里 heredoc 是正常的，
> 但**通过 AI 会话执行时必须避开**。
> 发版流程里的命令已全部改为单行形式，见 `docs/RELEASE_PROCESS.md`。

### 5.13 文档里别写本机绝对路径（钩子会拦）

> ⚠️ **pre-commit 钩子的第 [3/5] 项专查本机路径，含它就别想提交**

**真实案例**：一份技术调研文档记录了本机实测环境，
里面有写死的 SDK 绝对路径 —— 提交时被钩子拦下，输出形如：

```
[3/5] 本机路径与内网地址
  ✗ 无类 Unix 本机绝对路径
      docs/<文件名>.md:62:ANDROID_HOME  → <一段绝对路径>
```

**为什么拦**：本机路径泄露环境结构，且换台机器就失效 ——
对读文档的人没有价值，只有风险。

**怎么改**：换成变量或占位符。

| 写的形态 | 改成 |
|---|---|
| 写死的 SDK 绝对路径 | `$ANDROID_HOME/...` |
| 工作区绝对路径 | `$WORKSPACE`（见 §1 的占位符约定）|
| 临时目录绝对路径 | `$TMPDIR/...` |

**本机实际取值**统一记在 `docs/build-artifacts.md`（该文件已在 `.gitignore` 里，
专放这类"本机专用"信息）。

> ⚠️ 别用 `git commit --no-verify` 绕过 —— 那会把本机信息永久写进公开历史。
> 正确做法是脱敏后再提交。

> 🪞 **本节自己踩过这个坑**：初版在「反例」表格里直接写了**真实的**绝对路径，
> 于是**这一节本身也被钩子拦了**（写"不要写 X"的文章里写了 X）。
>
> **教训**：讲"不要写某类内容"时，**举例也不能写出真正的那类内容** ——
> 要用通用描述或虚构占位符。同理，**引用被拦的报错输出时要去掉敏感片段**，
> 否则引用一次又被拦一次。

### 5.14 更新说明已改为「在线获取」

应用内的更新说明（`ChangelogScreen`）**不再硬编码在 APK 里**：

```
① 网络：docs/changelog.json（raw.githubusercontent.com，最新）
     ↓ 失败
② 本地缓存：上次拉到的
     ↓ 没有
③ 内置兜底：ReleaseHistory（随 APK 发布，可能滞后）
```

**这对会话的影响**：

- **新版本说明写在 `docs/changelog.json`**，不是 `ReleaseHistory.kt`
- `ReleaseHistory.kt` 已退化为「离线兜底快照」，**不必随版本更新**
- 漏写会被 `tools/check_release_consistency.py` 在 CI 拦下
  （这是 2.1.0 发布漏写更新说明的根治措施）

详见 `docs/RELEASE_PROCESS.md`。

### 5.15 worktree 必须放 ext4，别放 FUSE（sdcard）

> ⚠️ **症状：worktree 刚建好就报一堆改动，全是「文件模式差异」**

实测（2026-10-14）——把 worktree 建到 sdcard 后：

    $ git status --porcelain | wc -l
    16                                  ← 全是假改动
    $ git diff gradlew | head -3
    old mode 100755
    new mode 100644                     ← FUSE 不支持可执行位

**根因**：FUSE 挂载不保留 Unix 可执行位。git 在 ext4 上记的是 `100755`，
检出到 sdcard 后变成 `100644` → git 认为「被改动」，全程误报。

**为什么危险**：这些假改动会

- 让 `git status` 永远不干净（**掩盖真实改动**）
- 让 `session.sh` 的「有未提交改动就拒绝删除」**永久误拦**

**结论**：**worktree 放 ext4**（即 `$GIT_REPO` 的父目录）—— 这是 `session.sh`
的默认行为，**别改**。

若确实要放 FUSE（不推荐），必须两条都做：

    git config extensions.worktreeConfig true
    git -C <worktree> config --worktree core.fileMode false

实测这样假改动归零，且**不影响主仓库**的 `fileMode=true`。

> 🪞 **附带一条教训**：AI 工具读 `/root`（ext4）**必须显式传 `environment=linux`**，
> 默认的 `android` 环境够不到。我一开始只试了默认值就断言「worktree 用不了」，
> 白做了一轮探针实验 —— **别用一个失败的尝试就下结论，先把参数试全。**
>
> 同理：`grep_code` / `find_files` 等工具**不支持** `environment=linux`，
> 在 ext4 上定位行号要用终端 `grep -n`。

### 5.16 merge 被「未跟踪文件」拒绝：主仓库有同名未跟踪副本

> ⚠️ **症状：`session.sh merge` 报 `Merge with strategy ort failed`，但「冲突文件」是空的**

真实踩到（2026-10-14，入库 Rust 调研文档时）：

```
error: The following untracked working tree files would be overwritten by merge:
	docs/rust-migration-feasibility.md
Please move or remove them before you merge.
Aborting
Merge with strategy ort failed.
```

**根因**：待入库的文件在**主仓库里已存在一份未跟踪的副本**
（例如之前被手动 `cp` 过去"等脱敏"）。git 合并要写入同一路径时，
**拒绝覆盖未跟踪文件** → abort。

**为什么有迷惑性**：

- 脚本把它归为「合并冲突」，但 `git diff --name-only --diff-filter=U` 是**空的**
  —— 因为根本不是内容冲突
- 容易误判成「两个会话改了同一文件」

**怎么处理**：

1. 先确认那份未跟踪副本的内容（`diff` 比一下）
2. 确认无害后**移走**（`mv` 到 `/tmp` 备份，别硬删）
3. 重新 merge

**怎么预防**：把「待入库文件」放进 **worktree**（`session.sh new` 后 cp 进去提交），
不要往主仓库丢未跟踪副本 —— 那正是 merge 的绊脚石。

> 🔧 `session.sh merge` 已加友好提示：合并失败且无内容冲突时，
> 直接列出主仓库的未跟踪文件并指向本节。

### 5.17 装机：`pm install` 读不了 /sdcard（SELinux）

> ⚠️ **症状：`pm install /sdcard/xxx.apk` 报 `Can't open file`，但文件明明存在**

真实踩到（2026-10-14，装机验证 UI 改动时）：

```
avc: denied { read } for scontext=u:r:system_server:s0 tcontext=u:object_r:fuse:s0
System server has no access to read file context u:object_r:fuse:s0
Error: Unable to open file: /sdcard/.../WatermarkBox-2.1.1-debug.apk
Consider using a file under /data/local/tmp/
```

**根因**：`pm install` 由 **system_server** 执行，而它**无权读 FUSE 挂载**（`/sdcard`）——
这是 SELinux 策略，不是「权限没给够」。

**正确做法**：先把 APK 放到 `/data/local/tmp/`（system_server 能读），再装：

```sh
# ① 复制过去（这步需要能读 /sdcard —— shell / root 可以）
cp /sdcard/.../app-debug.apk /data/local/tmp/wmb.apk
# ② 安装
pm install -r -d --user 0 /data/local/tmp/wmb.apk
```

> ⚠️ **proot（Ubuntu 终端）访问不了 `/data/local/tmp`**（Permission denied）——
> 所以「复制到 tmp」这一步要用 **shell 工具（Shizuku / Root）**，别在 proot 里试。

**已固化**：`tools/install_debug.sh`（做 proot 段：校验包名 + 归档 + 打印待执行的 shell 命令）。

---

### 5.18 预览架构：底图与水印**分两层**（GPU 合成）

> ⚠️ **预览不再把「底图 + 水印」合成到一张位图**

旧路径每帧都要 `outputFor()`（擦除 + 整块拷贝底图），成本**与像素数成正比** ——
文档实测 768×1024（78 万像素）就要 33.8ms，按比例**原图分辨率约 520ms/帧**，
根本没法跟手。这就是「预览只能压到 720 边」的原因。

但**拖动参数时底图一个像素都没变**，变的只有水印。所以改成两层：

| 层 | 画什么 | 成本 |
|---|---|---|
| `Image(baseBitmap)` | 底图 | GPU 纹理，只上传一次，之后**零拷贝** |
| `Canvas { drawInto }` | 水印 | 每帧几十条绘制命令，**与像素数无关** |

**关键实现**：用 `withTransform` 把画布坐标系**映射成「原图坐标系」**，
于是 `WatermarkRenderer` 里所有绘制逻辑（字号 / 边距 / 旋转 / 平铺）**一行都不用改**，
且与导出**天然一致**（同一坐标系、同一套代码）。

**由此得到的两个结论（别改回去）**：

1. `PREVIEW_MAX_EDGE` 从 720 提到 **4096** —— 不再有「拷贝底图」的成本，
   可以按**原图分辨率**显示（4096 是 GPU 纹理上限的安全值，
   覆盖绝大多数手机照片：12MP = 4032×3024）
2. **水印层必须 `clipRect` 到图片区域** —— 导出时溢出部分被**位图边界**自然裁掉，
   而 Compose 的 Canvas 是**容器**尺寸（Fit 后四周有留白），
   不裁剪的话水印会画到图片外面（黑底上）

**已清理的旧设施**：`rememberRenderedPreview` / `PreviewRequest` 已删除
（新架构不需要「后台渲染 + 双缓冲」）。`PreviewRenderEngine` / `PreviewBuffers`
**保留**（有独立测试、是通用工具），但**预览路径不再使用**。

**副作用**：`sourceWidth` / `sourceHeight` 参数链（含 `queryImageSize` 调用）已删 ——
新架构 base == target，scale 恒为 1，「预览所见 = 导出所得」是**结构上保证**的，
不再依赖传参。`queryImageSize` 函数**保留**（通用工具 + 有测试覆盖）。

#### 5.18.1 渐进式加载：先低清占位 → 再高清替换

上原图分辨率后有个新代价：**首屏变慢**。
一张 1200 万像素的图解码出来约 **48 MB**，再加 GPU 纹理上传，
**要等几百毫秒** —— 这段时间界面上只有加载圈。

所以分两步（**编辑页 + 批量页都做了**）：

```
① 先解 720 边（约 1.5 MB，几十毫秒）  → 立刻上屏，用户马上看到图
② 后台解 4096 边（约 48 MB，几百毫秒）→ 完成后无缝替换
```

**三条取舍（别改错）**：

| 取舍 | 理由 |
|---|---|
| **低清不写 LRU 缓存**（`cache = false`）| 它只是"先看一眼"的临时物；缓存它会把**高清挤出去**（容量只有 2 张），下一帧又得重解，**反而更慢** |
| **高清已缓存 → 跳过占位**（`peekPreview` 命中）| 没必要先降级显示一遍 |
| **替换后不 recycle 低清** | 它可能正被某一帧绘制（与 `LruCacheMap`「淘汰不 recycle」的取舍一致）|

**相关常量 / 方法**：
- `BitmapUtils.PREVIEW_PLACEHOLDER_MAX_EDGE = 720`（占位）
- `BitmapUtils.PREVIEW_MAX_EDGE = 4096`（高清）
- `ImageRepository.loadPreviewBitmap(uri, maxEdge, cache)`

> ⚠️ 有一条测试钉住「**占位必须明显小于高清**」（`BitmapUtilsSampleSizeTest`）——
> 若两者被调成相等，策略就退化成「解两遍同一张图」：
> **白白多花一倍时间，而界面上看不出任何异常**。

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

> ⚠️ **不要"奇怪"，先"评估" —— 用 git 问，别用猜**

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
[2026-10-14 02:2x] 三层协作方案落地 + 文档渲染语法清理 + 修 M3/M5
  - 提交链：f51d8a3 → e16abb6 → 655c2ce → c42db5d → b0f3bb8 → e6de3d2
            → ffa06fd → cfa98e8 → f62cc18(merge)
  - ahead 15，单测 436（433 → +3），lint 0 错误
  - ① 三层方案落地（worktree 隔离 + CONTEXT.md + 合并审查）
       · tools/session.sh：new / list / review / merge / rm
       · 实测揪出 5 个「装上了但没跑」的 bug（详见 §2.6 的表格）
       · 并用 session.sh **真实跑通一次任务**（M3+M5），端到端验证
  - ② 文档渲染语法清理
       · 新增 tools/clean_doc_latex.py（--check / --fix + 回归样例）
       · 清理 4 个文档共 21 处误用的 $$...$$ 美化语法
       · 脚本自己踩了 5 个坑才正确（多参数命令残留花括号 / 嵌套加粗 /
         误伤行内加粗 / >> 重复引用符 / /tmp 假阴性）
  - ③ 修 code-review 的 M3 + M5（P1 两项）
       · M3：EXIF 编解码边界（空 payload 被当成功 / B64: 开头往返不成立）
       · M5：批量 Worker 两通道守卫不对称
       · H2 经核实**已由更早的提交修掉** —— 看板当时显示「进行中」是过期信息
  - ④ 新知识点：worktree 必须放 ext4，别放 FUSE（详见 §5.15）
  - ⏳ 遗留：
       · docs/rust-migration-feasibility.md 仍待脱敏
       · code-review 剩余项：M2 / L5 / M4 / H1 / M1 / L1–L4 / L6–L8
       · ahead 15 待人类明示推送

[2026-10-14 00:07] 更新说明改为在线获取 + 发布流程文档 + 发布 2.1.1
  - 提交 6e7848b，**已推送**（ahead 0），tag `v2.1.1` 已推
  - 新增 docs/changelog.json（14 个版本）—— 应用从 GitHub 拉取更新说明
  - 新增 docs/RELEASE_PROCESS.md —— **发版唯一依据**，命令均已实测
  - 新增 tools/check_release_consistency.py —— CI 三方校验版本号，
    漏写更新说明会让构建失败（这是 2.1.0 漏写的根治措施）
  - ChangelogScreen 改为三级回退：网络 → 本地缓存 → 内置快照
  - 收录了 code-review-2026-10.md（另一会话的审查报告）
  - 三个新教训：
      ① heredoc 在本项目终端会卡住 → 见 §5.11
      ② 「反例」里也不能写真实本机路径（本节自己踩过）→ 见 §5.13
      ③ 提交前先 grep 本机路径自检，比等钩子拦快一倍
  - ⏳ 遗留：docs/rust-migration-feasibility.md 含本机路径**未入库**
       （需脱敏后才能提交，见 §4 待办；文件完整留在工作区）

[2026-10-13 23:50] 2.1.0 交付后审阅：补更新日志 + 修 2 处遗留缺陷
  - 提交 ac812bb，ahead 1，单测 416（411 → +5，新增 BitmapUtilsExifOrientationTest）
  - ① 2.1.0 发布漏了 ReleaseHistory（b1a254e 只动了 README 与 docs/releases/v2.1.0.md）
       → 补 15 条条目 + strings 中英各 16 条
       → 测试为何没拦住：ReleaseHistoryTest 的版本常量长期停在 1.4.1，
         那条「更新日志覆盖当前版本」校验的是 1.4.1（列表里确实有）→ 永远通过
         （「把现状写成断言」第三次，见 §5.6 / §5.9）
  - ② BitmapUtils.queryImageSize 返回 bounds 原值，而位图经 EXIF 旋正
       → 90/270 度时行列互换，预览与导出的水印占比仍差 1.33 倍，
         且只对手机竖拍照片发作（横图完全正常，极难发现）
       → 改为返回「旋正后」尺寸，抽 swapsWidthHeight() 加纯 JVM 单测
  - ③ BatchViewModel 预览竞态两处：setPreviewIndex 未递增令牌
       （缓存命中切页时保护恒失效）；queryImageSize 新挂起点后未复查令牌
       → 顺带去掉 token 不匹配时的 recycle（会把已入 LRU 缓存的位图
         recycle 成僵尸条目，与 LruCacheMap 的取舍相悖）
  - ⚠️ 同时发现另一会话在做「更新说明远端化」（工作区有、仓库尚未见）：
       他们已删除 ReleaseHistoryTest 那条失效测试，防线搬到
       tools/check_release_consistency.py（CI 读 versionName 三方比对）
       —— 本提交里对 VERSION_NAME 的改动已被该方案取代，属正常演进
  - 教训：Ubuntu proot 访问不了设备端的临时目录（`$TMPDIR`），构建日志要写到 Ubuntu 侧的临时目录

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

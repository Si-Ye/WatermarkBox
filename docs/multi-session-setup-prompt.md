# 多会话协作 · 搭建提示词（跨设备移植）

> **用途**：把本项目的「多会话协作结构」移植到**另一台 Operit 设备**。
>
> **用法**：从下面的「提示词正文」开始**整段复制**给那台设备的 Operit 新对话。
>
> **背景**：本结构在 WatermarkBox 项目落地时，实测揪出过 **5 个「装上了但没跑」的 bug**
> （见 `docs/multi-session-collaboration.md` §2.6）。本提示词已把它们写成
> **必须实测的验证清单** —— 照做即可避开。

---

## 提示词正文（以下整段复制）

# 任务：搭建「多会话协作」基础设施

你要在一台新的 Operit 设备上，复刻一套供**多个 AI 会话并行推进同一项目**的协作结构。

## 核心思想

把「协作问题」转成「版本控制问题」——用 git 的成熟机制替代手动同步。

**三层方案**：

| 层 | 做什么 | 靠什么 |
|---|---|---|
| 1 · 隔离 | 每个会话一个 git worktree + 独立分支 | `tools/session.sh new` |
| 2 · 上下文 | 统一读 `CONTEXT.md`，明确职责边界 | 文档约定 |
| 3 · 合并审查 | 审查改动 → `--no-ff` 合并 → 全量测试 → 冲突上报人类 | `tools/session.sh review/merge` |

**为什么不再手动同步**：过去所有会话共用一份代码、改完手动 `cp` 到 git 仓库，
三个后果 —— 同步漏了（提交旧代码）、互相覆盖（冲掉别人的活）、日志分不清（不知道哪批属于谁）。
worktree 本身就是 git 工作树，这一步**彻底消失**。

## 第 0 步：环境侦察（必做，先报结果再动手）

1. **确定两个路径**（全程用占位符，**不要把本机绝对路径写进任何文档**）：
   - `$WORKSPACE`：AI 文件工具（默认 `android` 环境）能直接读写的目录
   - `$GIT_REPO`：**ext4** 文件系统、能正常跑 git 的目录
     - ⚠️ **绝不能放 FUSE / sdcard** —— FUSE 不保留 Unix 可执行位，
       git 会把 `100755 → 100644` 当成「文件被改动」，`git status` 永远不干净
2. **确认工具**：`git --version`、`python3 --version`、`sh` 可用
3. **确认 `$TMPDIR`**：终端可写的临时目录
   - ⚠️ 若终端是 proot / Ubuntu，**设备端临时目录通常不可写**（Permission denied），要用 Ubuntu 侧的临时目录
4. **确认构建环境**（Android 项目）：JDK 17、Android SDK
5. **把上面结果列成表，报给人类确认**，再继续

## 第 1 步：建立 git 仓库

若 `$GIT_REPO` 还不是仓库：

```sh
cd "$GIT_REPO" && git init && git add -A && git commit -m "chore: 初始提交"
```

## 第 2 步：创建协作文件（清单）

| 文件 | 职责 |
|---|---|
| `AGENTS.md` | **AI 会话第一入口**：三条铁律 + 开工三步 + 怎么改代码 + 高危区 |
| `CONTEXT.md` | **只写稳定信息**：项目是什么、已确认决策、职责边界 |
| `docs/multi-session-collaboration.md` | 协作硬性规矩、任务看板、**已知坑**、交接记录 |
| `tools/session.sh` | worktree 管理（new / list / review / merge / rm / sync） |
| `tools/collab_status.py` | 状态自检（`--workspace`、`--check-sync`） |
| `tools/install_hooks.sh` | 安装钩子（**含安装后自检**） |
| `tools/git-hooks/pre-commit` | 工作区同步检查 + 敏感信息审计 |
| `tools/git-hooks/prepare-commit-msg` | 自动追加 `Session-Start: <时间>` |
| `tools/pre_push_audit.sh` | 敏感信息审计（危险文件 / 凭据 / 本机路径 / 构建可移植性 / `.gitignore`） |

> **如果人类能提供现有仓库的这批文件，优先直接取用并适配路径**；
> 否则按下面「第 3 步」的规格生成。

## 第 3 步：关键实现规格（含真实踩过的坑）

### 3.1 `tools/session.sh`

- 用 `git rev-parse --show-toplevel` 定位仓库根；不在仓库内就报错退出
- worktree 路径：`<仓库父目录>/wmb-wt-<名字>`（**刻意放在仓库外**，避免被主仓库 git 扫描、避免误提交）
- 分支名：`session/<名字>`；**一律从 `main` 开**（保证所有会话起点一致，便于合并）
- 校验会话名：只允许 `[a-zA-Z0-9_-]`（防路径穿越）
- `merge` 必须：
  - 先检查 main 是否干净，**只看已跟踪文件**（`--untracked-files=no`）
    - ⚠️ 否则「待入库的未跟踪文件」会让 merge **被永久误拦**
  - 用 `--no-ff` 保留合并记录（便于回溯「这批改动来自哪次会话」）
  - 合并后**必跑全量测试**；失败要报告但**不自动回滚**（由人类决定）
  - 冲突时**停下交人类**，**绝不自动裁决**（两个会话可能各有合理之处）
- 全量测试命令用 **`sh ./gradlew`** 而不是 `./gradlew`
  - ⚠️ `gradlew` 常缺可执行位 → `Permission denied`，
    而**合并本身却成功了**，极具迷惑性

### 3.2 `tools/git-hooks/pre-commit`

- 找仓库根 → 跑 `tools/pre_push_audit.sh --staged`，不通过就 `exit 1`
- **额外**：工作区同步检查
  - 从 `.git/collab-config`（**本机文件、不入库**）读 `WORKSPACE=<路径>`
  - 用 `sed` 提取，**不要 `source`**（避免执行文件里的任意代码）
  - 没这个文件就**跳过**（别的机器 / 克隆里没有，不该因此阻塞提交）
  - worktree 里 `.git` 是**文件**（不是目录）→ 该配置自然不存在 → 自动跳过（**这是正确的**：
    worktree 本身就是 git 工作树，不存在「两套路径」问题）

### 3.3 `tools/git-hooks/prepare-commit-msg`

- `merge` 来源的提交跳过（自动生成的信息加标识没意义）
- 用 **`git rev-parse --git-dir`** 定位状态文件，**不要用 `$ROOT/.git`**
  - ⚠️ worktree 里 `$ROOT/.git` 是**文件**，写状态会**静默失败** → 时间戳不准
- 状态文件：`<git-dir>/collab-session`，存首次提交的 unix 时间
- **超过 6 小时**视为新会话、自动刷新
- 已含 `Session-Start:` 就不重复加（`git commit --amend` 会再次触发本钩子）

### 3.4 `tools/install_hooks.sh`

- 源目录 `tools/git-hooks/`，目标 `.git/hooks/`
  （**`.git/` 不随仓库分发**，所以每次克隆后要装一次）
- 优先建**相对软链**；相对路径必须从 **`.git/hooks/` 出发**算：

  ```
  .git/hooks/  --..-->  .git/  --../..-->  仓库根
  ```

  - ⚠️ 写成 `../tools/...` 会被内核解析成 `.git/tools/...`（不存在）
    → **悬空软链，钩子静默不执行**，而 `ls .git/hooks/` 看着它在，极具迷惑性
- 建链后**立刻验证目标可达**（`[ -e "$dst" ]`），不可达就退回复制 + `chmod +x`
- 装完**逐个自检可执行位**（防再次悬空）
- 源文件本身也要有 `x` 权限

### 3.5 `tools/collab_status.py`

一条命令看全状态：仓库 HEAD / 远端 / ahead / 未提交 / 未跟踪 /
**工作区 ↔ 仓库差异** / **与上次自检相比的变化**。

- `--workspace <路径>`：指定工作区
- `--check-sync`：只做同步检查（供 pre-commit 调用），不同步就返回非 0
- ⚠️ **必须在 `$GIT_REPO` 目录下运行**才有完整输出
  （在工作区跑读不到 git，HEAD / 远端会显示为空）

### 3.6 三份文档的分工

| 文档 | 只写什么 |
|---|---|
| `AGENTS.md` | 入口：铁律、开工三步、worktree 用法、高危区 |
| `CONTEXT.md` | **稳定信息**：项目目标、已确认决策、职责边界 |
| `docs/multi-session-collaboration.md` | 硬性规矩、任务看板、已知坑、**交接记录（最新在最上）** |

- ⚠️ **HEAD / 版本号 / 测试数一律不写进 `CONTEXT.md`** —— 它们分钟级变化，
  写下来就过期，会形成「改文档 → HEAD 变 → 再改」的**死循环**（本项目踩过）。
- ⚠️ **文档里不要写本机绝对路径** —— 用占位符（`$WORKSPACE` / `$GIT_REPO` / `$TMPDIR`），
  本机实际取值单独记在一份**不入库**的文件里。

## 第 4 步：必须实测的验证点

> ⚠️ **「装上了 / 配好了」≠「真的会跑」** —— 这是本项目最重要的教训。
> 只跑语法检查（`sh -n`）发现不了下面这些，**必须在真实场景里验**。

| # | 验证 | 怎么验 | 期望 |
|---|---|---|---|
| 1 | 未同步会被拦 | 故意改工作区、不同步，去提交 | 被 pre-commit 拦下 |
| 2 | 钩子真的执行 | 随便提交一次 | 看到 `[pre-commit]` 输出 |
| 3 | 钩子可执行位 | `ls -l .git/hooks/` | 非 `.sample` 的都可执行 |
| 4 | 合并后跑测试 | 真的跑一次 `session.sh merge` | 测试被执行（不是 Permission denied）|
| 5 | worktree 里钩子正常 | 在 worktree 里提交一次 | `Session-Start` 正常写入 |
| 6 | 假改动归零 | worktree 放 ext4 后 `git status --porcelain \| wc -l` | 0（或只有真实改动）|

## 第 5 步：写进 `AGENTS.md` 的硬性规矩

- **git 只本地 commit，绝不 push**（除非人类明确说「推 / 发版 / release / 上传」）
- **不主动升版本号**（等人类决定）
- **界面交互验证一律交人类**，AI 禁止自行点击 / 滑动 / 输入试探
- **删除等不可逆操作，先反复确认再执行**
- 每完成一项工作就**本地提交**；提交信息写「为什么 / 取舍 / 踩坑」

## 交付标准

1. 上述文件全部就位，且**第 4 步 6 项验证全部实测通过**
2. 人类能独立跑通：`sh tools/session.sh new <名字>` → 在 worktree 里提交 → `review` → `merge`
3. 「本机实际路径取值」单独记在一份**不入库**的文件里（如 `docs/build-artifacts.md`，并加进 `.gitignore`）
4. 最终把**侦察结果 + 验证结果 + 遗留问题**列成表报给人类

---

## 附：本结构在当前设备上的落地事实（供参考，勿照抄路径）

- worktree 位置：`<仓库父目录>/wmb-wt-<名字>`
- 本机配置：`.git/collab-config`（`WORKSPACE=<路径>`）、`.git/collab-session`（会话时间戳）—— 均**不入库**
- 全量测试命令：`sh ./gradlew testDebugUnitTest lintDebug --console=plain`
- 合并后脚本会自动把改动**同步回主工作区**（读 `collab-config`；没配就跳过）

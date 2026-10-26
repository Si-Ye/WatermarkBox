# AGENTS.md · 本项目 AI 会话启动须知

> 本文件是 AI 助手（含所有并行会话）进入本项目的**第一入口**。
> **动手前先读完本文件** —— 它只写「必须知道的事」，细节走下方索引。
>
> 人类所有者：肆叶。本项目由**多个 AI 会话并行推进**，你看到的改动未必是你做的。

---

## 0. 三条铁律（违反会直接造成损失）

**1. git 只本地 commit，不 push**

- 本地攒提交是常态。**只有老大明确说「推 / 发版 / release / 上传」才推送。**
- 「git 一下」「提交一下」**不算**推送授权。

**2. 未经明确同意，不做删除等不可逆操作**

**3. 界面交互验证一律交老大**

- AI **禁止**自行 `tap` / `swipe` / 输入文字去试探界面。
- AI 该做的是：改代码 → 编译 → 单测/lint → 打包安装，然后把「要点什么、预期是什么」列成清单交出去。

---

## 1. 开工先跑一条命令

```sh
cd "$GIT_REPO"
"$(command -v python3 || command -v python)" tools/collab_status.py --workspace "$WORKSPACE"
```

> ⚠️ **必须先 `cd "$GIT_REPO"`**：脚本要读 git 上下文。
> 在工作区（FUSE 挂载、无 `.git`）里跑，它读不到仓库，
> 会输出「HEAD 空 / 与远端一致」这种**假状态** —— 比不跑更危险。
> （2026-10-14 实测：在工作区跑时 `📌 仓库` 三项全空。）
> Windows 等**单份代码**平台：`$WORKSPACE` 与 `$GIT_REPO` 是同一个路径，见 §2 平台支持边界。

> ⚠️ **别硬写 `python3`**：Windows 上的 `python3` 可能是应用商店占位程序，
> 会**静默失败**（零输出 + 非零退出码），而 `command -v` 仍判定它「存在」——
> 于是失败会被误报成「工作区未同步」。上面那行 `command -v … || …` 两个平台都对。

这一条命令**顶替了过去的「必做 3 件事」**（看提交历史 / 看工作区差异 / 看状态）：

- 仓库 HEAD 与远端差异、未提交改动、未跟踪文件
- 工作区 ↔ 仓库差异（区分「需同步的代码」与「本机临时文件」）
- **以及「与上次自检相比发生了什么变化」** —— 专治「我以为还是 A，其实变成 B」

> **为什么要专门做这个脚本**：口头规矩没有机制强制，跳过是常态。
> 详细背景见 `docs/multi-session-collaboration.md` §0。

**然后读这三份**：

1. `CONTEXT.md` —— 项目是什么、已确认的决策、职责边界（**稳定信息**）
2. `docs/multi-session-collaboration.md` **§2 / §4 / §5**（硬性规矩 / 任务看板 / 已知坑）
3. 本文件下面的「§2 怎么改代码」

---

## 2. 怎么改代码（用 worktree，别再手动 `cp`）

### 两套路径（**仅手机端 aarch64 Linux 适用**）

| 占位符 | 特点 |
|---|---|
| `$WORKSPACE` | AI 工具**默认环境**直接读写；**FUSE 挂载，git 不可用** |
| `$GIT_REPO` | ext4；**提交与构建都在这里**；AI 工具需 `environment=linux` 才能读写 |

> ⚠️ **单份代码平台（Windows 等）不存在这个区分**：`$WORKSPACE` = `$GIT_REPO` = 仓库根，
> `--workspace` 可省，同步检查本就不适用。别在 Windows 上照抄「两套路径」的动作。

> 本机实际取值见 `docs/build-artifacts.md`（含本机信息，**不入库**）。
> 文档里一律用占位符 —— pre-commit 钩子会拦本机绝对路径。

### 旧流程（已废弃，别再这么干）

以前所有会话共用一份代码，改完要**手动 `cp`** 同步到仓库：

```sh
# ❌ 已废弃
cp -r "$WORKSPACE/app/src/." "$GIT_REPO/app/src/"
```

三个后果：**同步漏了** → 提交旧代码；**互相覆盖** → 冲掉别人的活；**日志分不清** → 不知道哪批改动属于谁。

### 新流程：worktree 隔离（三层方案的第 1、3 层）

```sh
sh tools/session.sh new <你的任务名>     # 建 worktree + 分支 session/<名字>
# → 在打印出的路径里干活、提交（不需要任何手动同步）
sh tools/session.sh review <名字>        # 合并前审查：动了哪些文件 + 冲突预判
sh tools/session.sh merge  <名字>        # 审查 → --no-ff 合并 → 全量测试
sh tools/session.sh rm     <名字>        # 清理（有未提交改动会拒绝）
```

- **不需要手动同步** —— worktree 本身就是 git 工作树
- **不需要抢 main** —— 每个会话有自己的分支
- 冲突**不自动裁决**，脚本会停下来交人类决断
- 合并后**必跑全量测试**（两边改动可能语义冲突，静态看不出来）

详见 `docs/multi-session-collaboration.md` **§2.6**。

### 平台支持边界（**划界支持**，2026-10-16 定）

项目**明确划分两个平台各自的支持责任**。跑到「不支持面」的工具上失败，
是**预期行为**（脚本会自己报错并给替代路径），不是你的环境坏了 —— 别去修它。

| | 正式支持 | 明确不支持 → 走替代路径 |
|---|---|---|
| **平台** | ① 手机 Termux / proot（aarch64 Linux）② **Windows**（Git-Bash + JDK 21 + Android SDK） | macOS、x86 Linux |
| **构建验证** | `testDebugUnitTest` + `assembleDebug` + `lintDebug` 三个平台都必须绿 | — |
| **协作工具** | `tools/session.sh`、`tools/pre_push_audit.sh`、纯 Python 校验脚本、git 钩子 | — |
| **不支持面** | — | `setup_android_env.sh`（仅 aarch64）、`tools/install_debug.sh`、`tools/run_instrumented_tests.sh`（仅设备 shell）、`tools/push_release.sh` 的 tar 同步段、`tools/build_release_apk.py` |

- 不支持面的脚本**自带平台守卫**：跑错平台**立即报错**并指向替代路径（不会静默做坏事）。
- 完整对照表、Windows 上手步骤与已知坑见 `docs/multi-session-collaboration.md` **§2.7**。
- 本轮待修项清单见 `docs/pending-fixes-2026-10.md`。

---

## 3. 项目是什么

**水印盒 WatermarkBox** —— Android 图片水印工具（Kotlin 2.3 + Jetpack Compose + Material 3）。

核心差异化能力是**隐藏水印**：EXIF `UserComment` + XMP + DCT 频域三通道，
可在不改变画面的前提下嵌入可溯源的签名信息，其中 DCT 通道能抗 JPEG 重压缩。

---

## 4. 必读索引

| 文档 | 内容 |
|---|---|
| **`AGENTS.md`** | **本文件** —— AI 会话入口，开工先读 |
| **`CONTEXT.md`** | **项目是什么、已确认的决策、职责边界（稳定信息）** |
| **`docs/multi-session-collaboration.md`** | **协作硬性规矩 + 任务看板 + 已知坑（必读 §2 / §4 / §5）** |
| **`docs/pending-fixes-2026-10.md`** | **待修复清单（环境 / 代码 / 文档三类，含修复顺序）** |
| `CONTRIBUTING.md` | 代码约定、分层规则、注释规范 |
| `README.md` | 项目介绍、构建 / 发布流程 |
| `docs/RELEASE_PROCESS.md` | 发版流程（**发版必须照它做**） |
| `docs/build-artifacts.md` | 构建产物与装机规范（含本机信息，不入库） |
| `tools/collab_status.py` | 状态自检（开工第一条命令） |
| `tools/session.sh` | 会话工作区管理（三层方案的第 1、3 层） |

---

## 5. 构建与验证

```sh
cd "$WORKSPACE"
sh gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
    -PparallelSuffix=.final --console=plain
```

- **每轮必跑全套**（测试 + 构建 + lint）；**测试基线不许下降**。
- ⚠️ 用 `sh gradlew`，**不要** `./gradlew` —— 手机端 `/sdcard` 是 FUSE 挂载、不支持执行位；
  而这行写法在 Linux / Windows / Git-Bash 下**都成立**，所以统一用它，别再写 `./gradlew`。
- ⚠️ **Windows 上 `sh` 默认不在 `PATH` 里**（Git for Windows 只加 `Git\cmd`，里面没有 `sh.exe`）：
  在 **Git Bash 终端**里跑最省事；PowerShell / cmd 里用全路径
  `& "$env:ProgramFiles\Git\bin\sh.exe" gradlew ...`，或改用 `cmd /c gradlew.bat ...`。
  **别去改系统 `PATH`**（`Path` 被写坏会让 Gradle 报「找不到或无法加载主类」）。
- ⚠️ 装机固定用 debug 包名 `com.watermarkbox.final`，**不要**动 release 签名的 `com.watermarkbox`。
- ⚠️ **Windows**：`local.properties` 里的盘符冒号**必须转义**（`sdk.dir=C\:/path/to/Sdk`），
  否则 `lintDebug` 直接失败；且 lint 会**缓存**对它的分析，改完要清 `app/build/intermediates/lint*`。
  详见 `docs/multi-session-collaboration.md` §2.7。

---

## 6. 高危区（改动前必须先在 issue 讨论）

```
app/src/main/java/com/watermarkbox/invisible/
```

隐藏水印算法对参数**极其敏感**：

- 改**选点策略** → 影响所有已发布图片能否被检出
- 改**步长公式** → 同上
- 改**载荷格式** → **会导致旧图无法解码**

---

*本文件由 AI 会话维护。发现规则过时，直接改；发现新坑，补进 `docs/multi-session-collaboration.md` §5。*

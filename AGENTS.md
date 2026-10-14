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
python3 tools/collab_status.py --workspace "$WORKSPACE"
```

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

### 两套路径（背景知识）

| 占位符 | 特点 |
|---|---|
| `$WORKSPACE` | AI 工具**默认环境**直接读写；**FUSE 挂载，git 不可用** |
| `$GIT_REPO` | ext4；**提交与构建都在这里**；AI 工具需 `environment=linux` 才能读写 |

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
- ⚠️ 用 `sh gradlew`，**不要** `./gradlew` —— `/sdcard` 是 FUSE 挂载，不支持执行位。
- ⚠️ 装机固定用 debug 包名 `com.watermarkbox.final`，**不要**动 release 签名的 `com.watermarkbox`。

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
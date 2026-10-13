# 参与贡献

感谢你有兴趣改进水印盒。这份文档说明项目的约定 ——
**不是门槛，而是为了让你的改动更快被接受。**

---

## ⚠️ 如果你是一个 AI 会话（或长期在本项目工作的助手），先读这个

> **本仓库由多个 AI 会话 + 人类所有者同时推进，存在大量"未推送的本地提交"。**
>
> 在你动手改任何代码**之前**，请先读：
>
> ### 👉 [`docs/multi-session-collaboration.md`](docs/multi-session-collaboration.md)
>
> 它包含：项目坐标（两套路径）、硬性规矩（推送/装机/构建）、
> **当前状态与活跃任务看板**、项目特有的反直觉知识点、
> 以及**发现别人改动时的正确姿势**。
>
> 忽略它的后果曾在过去多次出现：误判正常改动为 bug、
> 生成多余包名、同步时丢失新增文件、重复修同一个已修过的 bug。
>
> **读完只需 3 分钟。**

---

## 提 Issue

### 报 Bug

请用 [Bug 反馈模板](.github/ISSUE_TEMPLATE/bug_report.yml)，如实填写。

**最关键的三项**：

1. **复现步骤** —— 「打不开」没法定位，「选 4000px 以上的图再导出会闪退」可以
2. **设备信息** —— 有些问题只在特定 ROM 上出现
3. **版本号** —— 在「设置 → 关于」里

如果问题与特定图片有关，可以附上图片，但**请先确认它不涉及你的隐私**。

### 提功能建议

请用 [功能建议模板](.github/ISSUE_TEMPLATE/feature_request.yml)。

**先说问题，再说方案**：

```
✅ 好：批量处理 30 张图时，参数要反复调，很费时间
❌ 差：希望加一个"智能参数"按钮
```

因为前者留出了「也许有更简单解法」的空间。

---

## 提 Pull Request

### 基本要求

| 要求 | 原因 |
|---|---|
| `./gradlew testDebugUnitTest` 全绿 | 别把红的测试合进来 |
| `./gradlew assembleDebug` 能构建 | 编译不过的代码没法评审 |
| 新增逻辑有测试 | 这个项目的测试是用来「钉住不变量」的，不是凑数 |
| 改动聚焦 | 一个 PR 只做一件事，方便回退 |

### 分支与提交

分支名随意，提交信息建议参考现有风格：

```
feat: 批量页支持拖拽排序
fix: 大图导出时内存溢出
refactor: 把渲染参数收敛进 WatermarkConfig
docs: 补充隐藏水印的抗压缩说明
chore: 升级 ExifInterface 到 1.3.8
```

**正文**说明「为什么这么改」，而不只是「改了什么」——
因为 diff 已经说明了后者。

---

## 代码约定

### 分层

```
ui/          ← 界面。只持有 UiState，所有变更走 ViewModel
data/        ← 数据模型与仓储（IO、持久化）
watermark/   ← 可见水印的渲染逻辑（纯计算，不碰 Android UI）
invisible/   ← 隐藏水印的编解码（DCT / RS 纠错 / 负载封装）
util/        ← 无状态工具函数
worker/      ← 后台任务
```

**跨层规则**：

- UI **不直接**碰 `ContentResolver`、`File`、`Bitmap` 的 IO
- `watermark/` 与 `invisible/` **不依赖** Android UI 框架（可在 JVM 上单测）

### 注释

**只写"为什么"，不写"是什么"**：

```kotlin
// ❌ 废话注释
// 把 size 设为 0
size = 0

// ✅ 有用的注释
// 传 0 会让 ExifInterface 用原始尺寸，
// 传 -1 反而会抛异常（见其源码 setAttribute 分支）
size = 0
```

### 测试

这个项目的测试风格是**「钉住不变量」**：

```kotlin
@Test
fun `DCT 与 IDCT 必须互为逆变换`() {
    // 不测具体数值，而是测"变换回来还等于原值"
    // 这样即使优化了算法实现，只要数学性质成立，测试就不会误报
}
```

**优先测"性质"而不是"具体输出"** —— 后者会让每次微调都改测试。

---

## 关于隐藏水印的特殊说明

`invisible/` 目录的代码对**参数极其敏感**：

- 改动选点策略 → 会影响所有已发布的图片
- 改动步长公式 → 同上
- 改动载荷格式 → **会导致旧图无法解码**

> **改动这一块前，请先在 issue 里讨论。**

---

## 本地环境

### 常规机器（Windows / macOS / Linux）

```bash
git clone https://github.com/Si-Ye/WatermarkBox.git
cd WatermarkBox
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
./gradlew assembleDebug
```

### 手机端（Termux / proot，aarch64）

项目最初就是在手机上写的，附带一个环境准备脚本：

```bash
sh setup_android_env.sh
sh gradlew assembleDebug    # 注意用 sh，不要用 ./gradlew
```

原因：`/sdcard` 是 FUSE 挂载，不支持 Unix 权限位，`chmod +x` 无效。

---

## 推送前自查

改完东西准备推送时，先跑一遍：

```bash
sh tools/pre_push_audit.sh
```

它会检查有没有把密钥、密码、本机路径之类的敏感内容带进版本库。

---

## 许可证

本项目采用 [Apache-2.0](LICENSE)。

提交 PR 即表示你同意你的贡献以同样的许可证发布。
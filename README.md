<div align="center">

# 水印盒 · WatermarkBox

**一个安静、克制的 Android 图片水印工具。**

选图 → 调参数 → 实时预览 → 导出。没有账号、没有广告、不联网。

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3-7F52FF.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack-Compose-4285F4.svg)](https://developer.android.com/jetpack/compose)

[![Download](https://img.shields.io/github/v/release/Si-Ye/WatermarkBox?label=%E4%B8%8B%E8%BD%BD&color=green)](https://github.com/Si-Ye/WatermarkBox/releases/latest)

</div>

---

## 这是什么

给图片加水印的工具。文字也好，Logo 也好，平铺也好——调完就能看到效果，不用等导出才知道长什么样。

但它还做了一件别的事：**隐藏水印**。

你可以往图片里塞一段信息（比如「© 2026 肆叶」或者一串设备 ID），**画面看起来完全没变化**，但之后能检测出来。图片被转发出去了，还能查到源头。

这是给画师、摄影师、以及所有担心自己作品被盗用的人做的。

---

## 功能

### 单张编辑

- **Photo Picker 选图** —— 不需要存储权限
- **实时预览** —— 改任何参数，画面立刻响应
- **文字水印** —— 字号 6~300、字体族、**自定义字体导入**（ttf/otf）、颜色、加粗、斜体、描边、阴影
- **图片水印** —— 宽度占比、保持比例、透明度
- **布局** —— 单个（九宫格定位 + 边距）/ 平铺 / 对角线
- **导出** —— PNG（无损）或 JPEG（质量可调）

### 批量处理

- 最多 **50 张**一次处理
- **界面与单张完全一致**，同一套参数面板
- 预览可**左右滑动**切换图片，末尾有「添加更多」入口
- WorkManager 后台执行，实时进度，可取消
- 单张失败不影响其它，结束时给出统计

### 预设

- DataStore 持久化，重启不丢
- **可脱离图片单独编辑** —— 不选图也能改参数
- 同名保存时会询问：保留两者 / 替换 / 取消（不会静默覆盖）

### 隐藏水印

| 通道 | 载体 | 画质影响 | 抗压缩 |
|---|---|---|---|
| 一级 | EXIF `UserComment` | **0**（像素完全不变） | ❌ 一压就没 |
| 二级 | DCT 频域中频系数 | 峰值 ±40/255，平均 < 5 | ✅ 可扛 JPEG 80~90 |

两条通道同时写入。EXIF 是「明路」，DCT 是「暗路」——前者精确但脆弱，后者模糊但顽强。

**实测数据**（512×512 测试图）：

- 逐位准确率 **97%**（3994 / 4096）
- 抗 JPEG 质量 **90 / 80**
- 抗**连续两轮压缩**（90 → 85）
- 抗 PNG 无损往返

**也说说它做不到什么**（这很重要）：

- ❌ **截图、重拍会丢失** —— 任何水印都逃不过，这是物理限制
- ❌ JPEG 质量 < 60 时频域通道可能失效（若未被重编码，EXIF 仍在）
- ❌ 纯色 / 极平滑图片可承载的信息量有限

DCT 有画质代价，所以界面上给了**开关**，权衡交给你。

### 反向检测

拿一张图过来，能判断是否含本应用写入的隐藏水印，并显示内容、来源通道、置信度。

两级策略：**EXIF 优先 → 频域兜底**。

---

## 技术实现

### 架构

```
app/src/main/java/com/watermarkbox/
├── data/
│   ├── model/          # 数据模型（水印配置、预设、更新日志）
│   └── repository/     # ImageRepository / ExportRepository / SettingsRepository
├── invisible/          # 隐藏水印引擎（DCT、Reed-Solomon、QIM 全部自实现）
├── ui/
│   ├── component/      # WatermarkPreview、DraggableBottomPanel 等复用组件
│   ├── navigation/     # Compose Navigation 路由
│   ├── screen/         # 各功能页（home / editor / batch / presets / settings）
│   └── theme/          # Material 3 主题
├── util/               # 位图、EXIF、文件名模板、占位符解析
├── watermark/          # 水印渲染引擎（文字 / 图片 / 平铺 / 对角线）
└── worker/             # 批量处理 WorkManager 任务
```

**分层**：`MVVM + Repository + Coroutines/Flow`

- UI 层只持有 `UiState`，所有变更走 `ViewModel` 的方法
- Repository 负责 IO 与持久化，UI 不直接碰 `ContentResolver`
- 参数面板 `WatermarkPanel` 通过 `WatermarkPanelHost` 接口与 ViewModel 解耦，
  因此单张编辑页和批量页可以**复用同一套面板**

### 隐藏水印的技术要点

这部分是项目里最花心思的地方，简单说说：

- **选点** —— 只选 JPEG 亮度量化步长 ≤ 14 的频点，避开 (3,3)=22 这类「死亡区」
- **步长** —— 自适应 = 该频点量化步长 × 6 × 亮度因子，硬上限 60
- **纠错** —— Reed-Solomon(16,8)，每块可纠 4 个符号错误
- **冗余** —— 每块承载 1 bit，同一 bit 在图上散布 `totalBlocks / bitCount` 倍副本
- **投票** —— 提取时按置信度加权软判决
- **自恢复** —— 提取端枚举候选长度，靠 CRC-16 判定命中，**无需任何外部元数据**

**零第三方水印库**——DCT / Reed-Solomon / QIM 全部手写。

### 技术栈

| 分类 | 选用 |
|---|---|
| 语言 | Kotlin 2.3 |
| UI | Jetpack Compose + Material 3 |
| 架构 | MVVM + Repository + Coroutines/Flow |
| 导航 | Navigation Compose |
| 后台任务 | WorkManager |
| 持久化 | DataStore Preferences |
| 序列化 | Kotlinx Serialization |
| 图片加载 | Coil |
| 最低版本 | Android 8.0（API 26）/ targetSdk 35 |

---

## 构建

### 环境

- JDK 17
- Android SDK（compileSdk 35、build-tools 35.0.0）

### 命令

```bash
# 单元测试（154 个）
./gradlew testDebugUnitTest

# Debug 包
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

# Release 包
./gradlew assembleRelease
```

### Release 签名

在项目根目录创建 `keystore.properties`（**该文件已被 .gitignore 排除**）：

```properties
storeFile=keystore/your-release.jks
storePassword=****
keyAlias=your-alias
keyPassword=****
```

若该文件不存在，`assembleRelease` 会走未签名流程，**不会导致构建失败**。

### 测试

```bash
./gradlew testDebugUnitTest           # 单元测试（纯 JVM）
./gradlew connectedDebugAndroidTest   # 仪器化测试（需连接设备）
```

单元测试覆盖网络是有限度的——真正需要 Android 运行时的部分走仪器化测试：

| 测试类 | 钉住的不变量 |
|---|---|
| `WatermarkPayloadTest` | 负载往返、CRC 拒绝篡改、同步字校验 |
| `ReedSolomonTest` | 每块可纠 4 个符号错、不误改正确数据 |
| `DctMathTest` | **DCT ↔ IDCT 是真正的逆变换对**、QIM 经 IDCT 往返仍可读 |
| `BlockSelectionStabilityTest` | 判据在嵌入前后一致、位置序列可复现 |
| `MidBandSelectionTest` | 只选 JPEG 量化步长小的频点 |
| `EnginePureSimulationTest` | **纯 JVM 端到端**：嵌入 → 投票 → RS 解码全链路 |
| `AppLicenseTest` | 许可证常量一致性、仓库地址与展示文本指向同一仓库 |
| `InvisibleWatermarkInstrumentedTest` | 真机 JPEG 90/80/两轮、PNG 往返、像素改动统计 |

### 推送前自查

仓库内附带一个敏感信息扫描脚本，用于在 `git push` 前拦截误提交：

```bash
sh tools/pre_push_audit.sh            # 检查工作树 + 待推送的提交
sh tools/pre_push_audit.sh --staged   # 只查暂存区（提交前快速自查）
```

覆盖五类问题：

1. **危险文件入库** —— 签名密钥、安装包、产物目录、本机配置
2. **凭据与密码** —— 硬编码密码、GitHub 令牌、云服务密钥
3. **本机路径与内网地址** —— 泄露本机目录结构或内网拓扑
4. **构建配置可移植性** —— 例如 `gradle-wrapper.properties` 指向本地文件
5. **`.gitignore` 完整性** —— 关键排除规则是否齐全

退出码 `0` 为通过、`1` 为发现问题，可直接用于 CI 拦截。

> 脚本自身含有大量检测用的模式串，会必然自匹配，因此内置了自我排除。

### 发布

日常提交与推送可以走一键脚本：

```bash
sh tools/push_release.sh "提交说明"          # 同步 → 自查 → 提交 → 推送
sh tools/push_release.sh --file msg.txt      # 提交说明从文件读（多行）
sh tools/push_release.sh "说明" --dry-run    # 只自查，不推送
```

推送到 GitHub 推荐用 **SSH**：凭据保存在本机 `~/.ssh/`，无需在命令或环境变量里传递令牌。

```bash
git remote set-url origin git@github.com:<user>/<repo>.git
```

脚本会自动识别 remote 类型 —— 是 `git@` 开头的走 SSH 直推，
是 `https://` 的则回退到 `GITHUB_TOKEN` 环境变量（用完自动从配置中清除）。

### 自动构建与发布

仓库配有两个 GitHub Actions 工作流：

| 工作流 | 触发条件 | 做什么 |
|---|---|---|
| [Build](.github/workflows/build.yml) | push 到 main / 提 PR | 跑测试 + 编译，验证代码可构建 |
| [Release](.github/workflows/release.yml) | 推送 `v*` 标签 | 签名构建 APK 并创建 Release |

Release 的产物由 **GitHub 的服务器从公开代码编译**，
任何人都能核对「下载到的 APK」与「仓库里的代码」是否对应 ——
不必依赖发布者的自觉。

#### 一次性配置

要让 CI 能签出 release 包，需要把签名材料存进仓库 Secret：

**① 生成密钥的 base64 文本**

```bash
base64 -w0 keystore/watermarkbox-release.jks > keystore.b64
```

> `-w0` 表示不换行。macOS 上 `base64` 没有该选项，
> 可用 `base64 -i keystore/watermarkbox-release.jks | tr -d '\n'`。

**② 在 GitHub 添加四个 Secret**

进入 `Settings → Secrets and variables → Actions → New repository secret`：

| Secret 名称 | 值 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 上一步生成的整段 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 的 storePassword |
| `ANDROID_KEY_ALIAS` | 密钥别名（`watermarkbox`） |
| `ANDROID_KEY_PASSWORD` | keystore 的 keyPassword |

**③ 验证**

打一个测试标签并推送：

```bash
git tag v1.4.1-test && git push origin v1.4.1-test
```

去 Actions 页面能看到流水线跑起来，结束后 Release 页会出现附件。

> ⚠️ Secret 一旦保存**无法再次查看**（只能覆盖）。
> 如果填错，直接新建同名的覆盖即可。

#### 发版流程

```bash
# 1. 改版本号（app/build.gradle.kts 的 versionCode / versionName）
#    同时更新 app 内的更新说明（data/model/ReleaseHistory.kt）

# 2. 写这一版的对外说明 —— 必做，否则 Release 页只有通用内容
#    新建 docs/releases/vX.Y.Z.md
#    按 docs/releases/README.md 的模板，写清「新增 / 修复 / 优化」

# 3. 提交
sh tools/push_release.sh "chore: 发布 vX.Y.Z"

# 4. 打标签推送 —— 这一步会触发 CI 自动构建并发布
git tag vX.Y.Z && git push origin vX.Y.Z
```

之后在 Actions 页面等几分钟，Release 就自动生成好了。

> **为什么必须写 `docs/releases/vX.Y.Z.md`**：
> CI 会把它作为 Release 正文，并自动追加下载表格用的 SHA-256 校验值。
> 若是漏了，CI 会用一段通用说明兜底并在日志里告警 ——
> 用户看到的就是「没有更新内容」的空壳版本页。
>
> 若 CI 跑失败，可用 Actions 页面的 **Run workflow** 手动重试
> （`release.yml` 支持 `workflow_dispatch`）。

---

## 开发手记

写这个项目踩过不少坑，记下来以免别人重蹈覆辙。

### 1. 批量上限闪退 —— 真因是注册时机

```
IllegalStateException: attempting to register while current state is RESUMED
```

`registerForActivityResult` 被写成 `by lazy`，延迟到点击时才注册。

> **修复**：launcher 一律声明为**字段**（构造期注册）。

### 2. 透明度滑块无效 —— `setColor` 会覆盖 alpha

`Paint.setColor(argb)` 会把 alpha 通道一并覆盖。

> **修复**：先合成 RGB 与 alpha，再一次性 `setColor`。

### 3. 预览完全不动 —— `derivedStateOf` 闭包捕获

闭包捕获了普通参数而非 State，快照系统感知不到变化。

> **修复**：用 `rememberUpdatedState` 包成 State。

### 4. 图片尺寸不随面板变化 —— Column 解开了高度约束

`Column` 默认 `wrapContentHeight`，让 `Image` 的 `ContentScale.Fit` 只按宽度撑满。

> **修复**：`Image` 直接 `fillMaxSize()`，不套 `Column`。

### 5. 隐藏水印提取恒失败 —— 三处根因

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | 逐位准确率 70% | `LUMA_CLAMP=3` 事后裁剪像素，写入值 ≠ 预期系数 | 去掉像素裁剪 |
| 2 | 提取恒失败 | `ByteArray(payloadBytes)` 应为 `ByteArray(encodedBytes.size)` | 按 RS 编码长度重组字节 |
| 3 | 逐位准确率 50% | 同一块被多次写入（改动累积），提取端只读一次 | 每块只访问一次 |
| 4 | JPEG 90 就失败 | QIM 步长 < JPEG 该频点的量化步长 | 只选低频点 + 步长 ×6 |

第 3 条最隐蔽：`fullOrder` 生成 R 轮序列，同一块被反复访问，改动不断累积。
修复后准确率从 **50% → 97%**。

### 6. 中文隐藏水印变 `?`

EXIF `UserComment` 有字符集限制，直接写中文会丢失。

> **修复**：统一走 Base64 + 自定义转义编码。

---

## 权限

| 操作 | 方式 | 需要的权限 |
|---|---|---|
| 读取图片 | Photo Picker | **无** |
| 导入字体 | OpenDocument | **无** |
| 写入相册 | MediaStore | Android 10+ **无**；Android 9 及以下按需申请 |

---

## 参与贡献

欢迎提交 Issue 和 Pull Request。

- 修 bug：请附上**复现步骤**和**崩溃日志**
- 新功能：建议先开 Issue 讨论，避免白做
- 提交代码：保持现有代码风格，**新增逻辑请补测试**

---

## 许可证

[Apache License 2.0](LICENSE)

```
Copyright 2026 肆叶

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

---

<div align="center">

**水印盒 · WatermarkBox**

*v1.4.1*

Made with 🐺 by 肆叶

</div>

# 发布流程

> **这份文档是发版的唯一依据。** 每一步都**实际执行过**，
> 命令可直接复制运行（在仓库根目录执行）。
>
> 如果你要发版，**从头照做，不要跳步**。

---

## 0. 一页速查

一次完整发布 = **改 3 个文件 + 跑 1 次校验 + 打 1 个 tag**。

```bash
# ① 版本号（唯一权威源）
#    改 app/build.gradle.kts 里的 versionCode（+1）与 versionName

# ② 应用内更新说明
#    改 docs/changelog.json —— 在 releases 数组【开头】插入新版本

# ③ GitHub Release 正文
#    新建 docs/releases/vX.Y.Z.md

# ④ 本地校验（必须通过才能继续）
python3 tools/check_release_consistency.py

# ⑤ 构建与测试
sh gradlew testDebugUnitTest lintDebug assembleDebug

# ⑥ 提交并推送
git add -A && git commit -F /tmp/commit_msg.txt
git push origin main

# ⑦ 打 tag（这一步触发 CI 自动发布）
git tag -a vX.Y.Z -m "水印盒 vX.Y.Z"
git push origin vX.Y.Z
```

> ⚠️ **`python3 tools/check_release_consistency.py` 不能跳过。**
> 它会校验 ①②③ 三处版本信息是否一致，任何一个漏写都会红。

---

## 1. 为什么有这份文档

### 真实事故

**2026-10-13 发布 2.1.0 时，漏了应用内的更新说明。**

具体表现：用户装上新版、打开「更新说明」，**看不到 2.1.0 说了什么** ——
因为那份数据硬编码在 APK 里，而发布时没同步。

更糟的是**测试没拦住**。当时有一条断言「当前版本应当在更新日志里」，
但它比对的是一个**手写的版本号常量**，那个常量早已停在 `1.4.1`
（而实际版本是 2.1.0）—— 于是它校验的是 1.4.1，**永远通过**。

这是同一类问题的第三次出现：

> **把「现状」写成断言，会让测试失去发现问题的能力。**

（前两次见 `docs/multi-session-collaboration.md` §5.9 与 §5.7）

### 两次根治

| 问题 | 根治方式 |
|---|---|
| 更新说明跟着 APK 走，容易漏 | 改为**从 GitHub 在线拉取**，补写说明不必等发版 |
| 校验用的版本号有手写副本 | 校验脚本**只读 `build.gradle.kts`**，没有第二份 |

现在「漏写更新说明」会在 **CI 里直接构建失败**，不会流到用户手上。

---

## 2. 版本号规则

格式 `X.Y.Z`（三段数字）。参照语义化版本：

| 位 | 何时 +1 | 例子 |
|---|---|---|
| **X** | 大重构、不兼容变更 | 1.x → 2.0.0 |
| **Y** | 新功能、成批修复 | 2.1.0 → 2.2.0 |
| **Z** | 小修复、补完 | 2.1.0 → **2.1.1** |

**`versionCode` 每次发布必须 +1**（同版本号也 +1）——
它是系统判断"新版本"的唯一依据，不变的话用户装不上更新。

> ⚠️ **versionName 只在 `app/build.gradle.kts` 里写一次，别处不再手写**

---

## 3. 详细步骤

### 步骤 1 · 确定版本号

看当前版本：

```bash
grep -n 'versionCode\|versionName' app/build.gradle.kts
```

输出示例：

```
29:        versionCode = 12
30:        versionName = "2.1.0"
```

决定新版本号（比如 `2.1.1` / `versionCode` 改成 `13`）。

---

### 步骤 2 · 更新 `docs/changelog.json`

**这是应用内「更新说明」的数据源。**

在 `releases` 数组的**最前面**插入新版本条目（列表顺序即展示顺序，
最新的必须在顶部）：

```json
{
  "version": "2.1.1",
  "date": "2026-10-13",
  "type": "MINOR",
  "title": {
    "zh": "一句话主题（中文）",
    "en": "One-line theme (English)"
  },
  "changes": [
    {
      "kind": "FIXED",
      "zh": "修复：描述现象（中文）",
      "en": "Fixed: describe the symptom (English)"
    }
  ]
}
```

**字段说明**：

| 字段 | 取值 | 说明 |
|---|---|---|
| `version` | `X.Y.Z` | 必须与 `versionName` 一致 |
| `date` | `YYYY-MM-DD` | 发布日期 |
| `type` | `MAJOR` / `MINOR` | 决定界面上的徽章颜色 |
| `title` | `{zh, en}` | **两种语言都不能空** |
| `changes[].kind` | `ADDED` / `FIXED` / `IMPROVED` / `CHANGED` | 决定符号（＋ ✓ ↑ ~）|
| `changes[].zh` / `.en` | 字符串 | **都不能空** |

> 💡 **`kind` 的选择**
> - `ADDED`：新功能
> - `FIXED`：修 bug（**写用户看到的现象**，不是代码改动）
> - `IMPROVED`：优化（更快、更省内存…）
> - `CHANGED`：行为变更 / 需要说明的事项

**手改 JSON 有语法风险** —— 改完务必跑步骤 5 的校验。

也可以用 Python 插入（**单行命令**，保证格式正确）。下面是一条完整命令，
把 `2.1.1` / 日期 / 文案替换成你的即可：

```bash
python3 -c "import json; p='docs/changelog.json'; d=json.load(open(p,encoding='utf-8')); d['releases'].insert(0, {'version':'2.1.1','date':'2026-10-13','type':'MINOR','title':{'zh':'主题','en':'Theme'},'changes':[{'kind':'FIXED','zh':'修复：…','en':'Fixed: …'}]}); json.dump(d, open(p,'w',encoding='utf-8'), ensure_ascii=False, indent=2); print('ok, 现有', len(d['releases']), '个版本')"
```

> ⚠️ **不要用 heredoc（`<<'EOF'`）写多行命令。**
> 本项目的终端工具在传递多行文本时，换行会被当成"继续输入"，
> 导致 shell 停在续行模式、命令**不执行**（实测踩过）。
> 一律用**单行命令**，或先把内容写进文件再引用该文件。

---

### 步骤 3 · 写 Release 说明

新建 `docs/releases/vX.Y.Z.md`（**文件名必须是 `v` + 版本号**）。

**这份内容是 GitHub Release 页面的正文**，也是用户点进 Release 看到的东西。
CI 会读这个文件（见 `.github/workflows/release.yml`）。

**结构约定**（参照 `docs/releases/v2.1.0.md`）：

```markdown
## 水印盒 vX.Y.Z

一句话概括本版主题。

### 🔧 修复
- …（写用户视角的现象，必要时讲清原因）

### ✨ 新增
- …

### ⚡ 优化
- …

### ⚠️ 一个需要说明的变更     ← 可选，有破坏性变更时才写
- …

### 📦 下载
| 文件 | 说明 |
|---|---|
| `WatermarkBox-X.Y.Z-release.apk` | **推荐**，正式包 |
| `WatermarkBox-X.Y.Z-debug.apk` | 调试用，含额外日志，体积较大 |

> **注意**：两个包签名不同。装过其中一个后换装另一个，需先卸载（应用数据会丢失）。

### 📊 质量
- 单元测试 NNN 个，全部通过

### 系统要求
Android 8.0（API 26）及以上
```

> 💡 **写作要点**
> - 面向**用户**，不是面向开发者。写"用户会遇到什么"，而不是"改了哪个类"
> - 修复类条目建议带上**原因**（简短一句）—— 用户能判断是否与自己相关
> - 有**破坏性变更**时单独开一节说明，别混在列表里

---

### 步骤 4 · 改版本号

```bash
# 手工编辑 app/build.gradle.kts，改这两行：
#   versionCode = 13          ← 上一版 +1
#   versionName = "2.1.1"     ← 新版本号
```

验证已改到位：

```bash
grep -n 'versionCode\|versionName' app/build.gradle.kts
```

---

### 步骤 5 · 本地校验 ⭐ 不能跳过

```bash
python3 tools/check_release_consistency.py
```

**通过时**输出：

```
[release-check] 发版一致性校验
[release-check] 版本：2.1.1
[release-check] ✅ 三处版本信息一致，可以发布
```

**失败时**会列出具体问题，例如：

```
[release-check] ❌ 发现 2 个问题：
    - changelog.json 的最新版本是 2.1.0，而 versionName 是 2.1.1 —— 发版忘了更新说明（这是用户能直接看到的问题）
    - 缺少 docs/releases/v2.1.1.md —— GitHub Release 页面会没有正文（CI 的 release.yml 读这个文件）
```

**按提示修完再跑一次**，直到出现 `✅`。

> 这一步在 CI 里也会跑（`.github/workflows/build.yml`），
> 所以漏写不会流到用户手上 —— 但**本地跑能省一轮等 CI 的时间**。

---

### 步骤 6 · 构建与测试

```bash
# 测试 + lint + debug 包
sh gradlew testDebugUnitTest lintDebug assembleDebug

# Release 包
sh gradlew assembleRelease
```

**必须全部 BUILD SUCCESSFUL。**

> ⚠️ **Release 包可能缺 `AndroidManifest.xml`**（在 arm64/proot 等环境）——
> 这是已知问题，用仓库脚本重新组装：
>
> ```bash
> python3 tools/build_release_apk.py
> ```
>
> 发布前**务必校验产物完整性**：
>
> ```bash
> python3 tools/check_apk_integrity.py --require-signature app/build/outputs/apk/release/app-release.apk
> ```
>
> 通过时输出 `✅ 可以安装`。

核对包名与版本号：

```bash
aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
```

应显示 `package: name='com.watermarkbox' versionCode='13' versionName='2.1.1'`。

> `aapt2` 在 Android SDK 的 `build-tools/<版本>/` 下。若不在 PATH 里，
> 用完整路径调用（各人 SDK 位置不同，这里不写死）。
>
> **包名必须是 `com.watermarkbox`**（不带 `.debug` / `.final` 等后缀）——
> 后缀只用于同机装多个调试包时的临时区分。

---

### 步骤 7 · 装机验证（推荐）

装到真机上看一眼，主要确认：

1. 「更新说明」页能正常打开
2. 底部显示的是**新版本号**
3. 有网络时能看到**最新版本的条目**（可能要等 CDN，见下方"常见问题"）

```bash
# 装 release 包（注意：会覆盖同签名的已装版本）
adb install -r app/build/outputs/apk/release/app-release.apk
```

> 调试期也可以装 debug 包，但**不要用不同的包名后缀**产出额外安装项。

---

### 步骤 8 · 提交

**提交信息要写清"为什么"**，不只是"改了什么"。

**方式 A · 短信息**（一条命令，用多个 `-m` 分段）：

```bash
git add -A
git commit -m "release: 发布 2.1.1" \
           -m "三处版本信息已同步（versionCode 12→13，changelog.json 与 v2.1.1.md 已补）" \
           -m "本版修复竖拍照片水印偏小、批量页基准竞态；更新说明改为在线获取"
```

**方式 B · 长信息**（先把内容写进文件，再引用）：

先把提交信息写到 `/tmp/commit_msg.txt`（用你惯用的编辑器，
或 AI 会话用写文件工具创建），内容例如：

```
release: 发布 2.1.1

## 三处版本信息已同步
- versionCode 12 → 13，versionName 2.1.0 → 2.1.1
- docs/changelog.json 新增 2.1.1 条目
- docs/releases/v2.1.1.md 新建

## 本版内容
- 修复：手机竖拍照片的水印仍偏小约三分之一（EXIF 旋转导致基准尺寸取反）
- 修复：批量页连续滑动时预览用错基准（竞态令牌）
- 新增：更新说明改为在线获取（changelog.json + CI 一致性校验）

已通过 tools/check_release_consistency.py 校验。
```

然后：

```bash
git add -A
git commit -F /tmp/commit_msg.txt
```

> ⚠️ **别用 `cat > file <<'EOF'` 那种 heredoc 写法** ——
> 本项目的终端工具在传多行文本时会停在续行模式（实测踩过）。
> 写文件请用编辑器或工具，不要靠 heredoc。
>
> 仓库有 **pre-commit 钩子**做敏感信息审计（硬编码密码、本机绝对路径等）。
> 如果被拦，**按提示改**，不要用 `--no-verify` 绕过。

---

### 步骤 9 · 推送

```bash
git log --oneline -1          # 确认 HEAD 是要发的那个提交
git status -sb | head -1      # 确认工作区干净
git push origin main
```

---

### 步骤 10 · 打 tag（触发发布）

> ⚠️ **tag 名必须是 v + 版本号，且与 versionName 完全一致**

```bash
git tag -a v2.1.1 -m "水印盒 v2.1.1"
git push origin v2.1.1
```

推送 tag 会触发 `.github/workflows/release.yml`，它自动完成：

1. 跑测试
2. 用密钥签名构建 release APK
3. 读 `docs/releases/v2.1.1.md` 作为正文
4. 创建 GitHub Release 并附上 APK

> ⚠️ **tag 打错了不要删**（删了再打会让已下载的用户对不上号）。
> 确认打错就**发一个新版本号**。

---

### 步骤 11 · 验证发布结果

**① 看 CI 状态**

打开 `https://github.com/Si-Ye/WatermarkBox/actions`，
应当看到两个任务（Build 与 Release）都转绿。

**② 看 Release 页面**

`https://github.com/Si-Ye/WatermarkBox/releases`

确认：版本号正确、正文是刚写的说明、APK 附件在。

**③ 确认在线更新说明已生效**

应用内「更新说明」读的是：

```
https://raw.githubusercontent.com/Si-Ye/WatermarkBox/main/docs/changelog.json
```

直接在浏览器打开这个地址，确认能看到新版本条目。

> ⚠️ **CDN 有缓存**：推送后最长约 **5 分钟**才对所有节点可见。
> 若立刻打开还是旧的，等几分钟再试 —— 这是正常现象，不是发布失败。

**④ 归档产物**

把 release / debug 两个 APK 存到构建产物目录（见 `docs/build-artifacts.md`）。

---

## 4. 命令速查表

| 目的 | 命令 |
|---|---|
| 看当前版本 | `grep -n 'versionCode\|versionName' app/build.gradle.kts` |
| **发版校验** | `python3 tools/check_release_consistency.py` |
| 跑测试 | `sh gradlew testDebugUnitTest` |
| 静态检查 | `sh gradlew lintDebug` |
| 构建 debug | `sh gradlew assembleDebug` |
| 构建 release | `sh gradlew assembleRelease` |
| 重组 release 包 | `python3 tools/build_release_apk.py` |
| 校验 APK 完整性 | `python3 tools/check_apk_integrity.py --require-signature app/build/outputs/apk/release/app-release.apk` |
| 看包装名/版本 | `aapt2 dump badging <apk> \| head -1` |
| 从旧数据重建 changelog | `python3 tools/export_changelog.py --apply`（**不带 `--apply` 只干跑预览**，不再直接覆盖） |

---

## 5. 常见问题

### Q：校验脚本报「changelog.json 的最新版本是 X，而 versionName 是 Y」

**原因**：改了 `versionName` 但没更新 `docs/changelog.json`。

**处理**：回到步骤 2，在 `releases` **开头**插入新版本条目。

> 这是**最常见**的漏写，也是本脚本存在的主要理由。

---

### Q：校验脚本报「缺少 docs/releases/vX.Y.Z.md」

**原因**：没写 Release 说明，或文件名与版本号不一致（比如写成 `2.1.1.md` 少了 `v`）。

**处理**：按步骤 3 补一份，文件名带上 `v` 前缀。

---

### Q：改了 changelog.json 但应用里看不到新版本

按顺序排查：

1. **CDN 缓存**：等 5 分钟。这是最常见原因。
2. **本地缓存**：应用会缓存上次拉到的内容。清应用数据或重装即可强制刷新。
3. **没网**：此时显示的是随 APK 内置的快照（不是最新）。
4. **JSON 写坏了**：浏览器打开那个地址，把内容贴进任意 JSON 校验器试一下。

> 第 4 点本该在 CI 就被拦住 —— 若已发布才发现，说明校验脚本没跑或没通过。

---

### Q：`assembleRelease` 产出的包装不上

先校验完整性：

```bash
python3 tools/check_apk_integrity.py --require-signature <apk>
```

若提示缺 `AndroidManifest.xml` / `resources.arsc`，用重组脚本：

```bash
python3 tools/build_release_apk.py
```

---

### Q：pre-commit 钩子拦下了提交

钩子会列出行号与原因。常见两类：

| 提示 | 处理 |
|---|---|
| 硬编码密码 / 令牌 | 删掉，改用配置或环境变量 |
| 本机绝对路径 | 改成相对路径，或用占位符（见 `docs/multi-session-collaboration.md` §1）|

**不要用 `git commit --no-verify` 绕过** —— 那会把不该公开的东西推上去。

---

### Q：要发的是 debug 包怎么办

**不需要单独发版。** debug 包仅用于本机调试，
发布一律用 **release 包**（在 `docs/releases/vX.Y.Z.md` 的下载表里也这么写）。

---

## 6. 完整示例：2.1.1 的实际流程

以下是一次**真实执行过**的发布记录，可作为模板。

```bash
# ① 确认当前版本
$ grep -n 'versionCode\|versionName' app/build.gradle.kts
29:        versionCode = 12
30:        versionName = "2.1.0"

# ② 改版本号（13 / 2.1.1）
# ③ 在 docs/changelog.json 的 releases 开头插入 2.1.1 条目（4 条变更）
# ④ 新建 docs/releases/v2.1.1.md

# ⑤ 校验 —— 此时刻意先跑一次"还没补齐"的状态，确认校验有效：
$ python3 tools/check_release_consistency.py
[release-check] 版本：2.1.1
[release-check] ❌ 发现 2 个问题：
    - changelog.json 的最新版本是 2.1.0，而 versionName 是 2.1.1 —— 发版忘了更新说明（这是用户能直接看到的问题）
    - 缺少 docs/releases/v2.1.1.md —— GitHub Release 页面会没有正文（CI 的 release.yml 读这个文件）
退出码=1                     ← 校验确实拦住了

# ⑥ 补齐两个文件后重跑：
$ python3 tools/check_release_consistency.py
[release-check] 版本：2.1.1
[release-check] ✅ 三处版本信息一致，可以发布
退出码=0

# ⑦ 构建与测试
$ sh gradlew testDebugUnitTest lintDebug assembleDebug
BUILD SUCCESSFUL

# ⑧ 提交 / 推送 / 打 tag
#    提交信息先写进 /tmp/commit_msg.txt（编辑器或工具创建），再：
$ git add -A && git commit -F /tmp/commit_msg.txt
$ git push origin main
$ git tag -a v2.1.1 -m "水印盒 v2.1.1"
$ git push origin v2.1.1

# ⑨ 到 Actions 与 Releases 页面确认
```

> 第 ⑤ 步是**刻意的负向验证** —— 它证明校验不是"摆设"。
> 每次改完校验脚本本身，也建议这样验一次。

---

## 7. 相关文档

| 文档 | 内容 |
|---|---|
| `docs/releases/README.md` | Release 说明的写作约定与模板 |
| `docs/multi-session-collaboration.md` | 多会话协作规矩（含装机、推送的硬性要求）|
| `docs/build-artifacts.md` | 构建产物存放位置与命名 |
| `.github/workflows/build.yml` | 日常 CI（含发版一致性校验）|
| `.github/workflows/release.yml` | 打 tag 后的自动发布 |

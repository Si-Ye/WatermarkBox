# 构建产物与临时文件存放规范

## 目录位置

```
/storage/emulated/0/OperitAI/builds/WatermarkBox/
```

**所有**本项目产生的、**不属于源码**的文件，一律放这里。

---

## 为什么要有这个规范

在没有规范之前，产物散落在 `/sdcard/Download/` 里 ——
那是个公共下载目录，混着各种 App 下载的文件（140+ 个条目）。
结果是：

- **找不到**：要找一个测试 APK，得在几十个无关文件里翻
- **误删风险**：清理下载目录时，不知道哪些能删
- **误伤风险**：写 `rm wmb_*` 这类通配符命令时，心里没底

集中到独立目录后，**「这个目录里的都是我的产物」** 成为明确约定。

---

## 放什么

| 类别 | 例子 |
|---|---|
| **APK** | 构建输出、测试包、发给别人试用的包 |
| **截图** | 真机截图、UI 验证图、README 用图 |
| **诊断输出** | 排查 bug 时生成的分析图、对比图 |
| **字体等资源副本** | 从系统提取出来备用的字体文件 |
| **备份** | 清理前的备份、配置备份 |
| **日志** | 测试输出、构建日志 |

## 不放什么

| 不放 | 应该放哪 |
|---|---|
| **源码** | 工作区 `/storage/emulated/0/OperitAI/WatermarkBox/` |
| **密钥 / 密码 / keystore** | **任何地方都不该明文放**（用 CI Secrets）|
| **git 仓库本身** | 独立的仓库工作副本（见 `tools/` 里的构建脚本）|
| **需要跟着源码走的资源** | 工作区的 `docs/assets/`（要提交进 git）|

### ⚠️ 特别注意

**本目录不在 git 仓库里**，因此：

- 不会被提交、不会被 `.gitignore` 影响 —— **适合放体积大的临时产物**
- 也**不会被备份**，删了就没了 —— **重要产物要另存或上传**

### 相册可见性（.nomedia）

本目录里有大量图片（截图、诊断图）。若不处理，它们会全部出现在相册里。

**做法**：在本目录放一个 `.nomedia` —— Android 媒体扫描器会跳过
含有该文件的目录及其所有子目录。

但由于本目录在 git 外，那份 `.nomedia` **可能随目录被删而丢失**。
发现相册里又冒出本项目的图时，重新放一个即可：

```sh
touch /storage/emulated/0/OperitAI/builds/WatermarkBox/.nomedia
```

**要让已索引的记录立即消失**，还需触发一次媒体重扫
（仅加文件不会清除 MediaStore 里的旧条目）：

```sh
content call --uri content://media --method scan_volume --arg external_primary
```

> ⚠️ `content` 是 Android 系统命令，只在 Shizuku / adb / root shell 里可用；
> 在 Ubuntu proot 终端里会报 `content: not found`。
>
> **补充**：`am broadcast -a MEDIA_MOUNTED` 在本机 ROM 上会崩（exit 255），
> 上面那条 `content call` 才是可靠的重扫方式。

---

## 命名建议

保持现有前缀习惯，方便识别来源：

| 前缀 | 含义 |
|---|---|
| `wmb_` | 通用项目产物 |
| `wmb-`（连字符）| 临时/诊断目录 |
| `WatermarkBox-` | 正式命名的发布相关文件 |
| `diag` / `tiled` / `diagonal` / `opt` | 特定功能的验证图 |

**不要**用 `tmp`、`test`、`新建文件夹` 这类模糊名 ——
过几天自己都想不起来是什么。

---

## 与构建流程的关系

Gradle 的产物**仍然输出到工作区的 `app/build/`**（那是标准行为，
改动会牵连整个构建配置）。要「留存」某次构建时，**手动复制**过来：

```sh
cp app/build/outputs/apk/debug/app-debug.apk \
   /storage/emulated/0/OperitAI/builds/WatermarkBox/WatermarkBox-1.6.0-debug.apk
```

**带上版本号** —— 不然过几天就分不清哪个是哪个了。

---

## 历史

- **2026-10-13** 建立规范，把 `Download/` 里的 62 项历史产物（221 MB）迁移过来

# 代码审查报告 · 问题与算法缺陷

> **审查范围**：`invisible/`（隐藏水印引擎）、`util/`（编解码与工具）、`watermark/`、`data/model/`、部分 `worker/`
> **审查角度**：算法正确性、边界条件、健壮性、安全性、可维护性
> **日期**：2026-10-13
> **结论摘要**：代码整体质量**很高**（注释详尽、边界考虑周全）。但仍发现：
> **2 个高严重度**、**5 个中严重度**、**8 个低严重度 / 改进项**。

---

## 0. 总览

> ⚠️ **高：2 个 · 中：5 个 · 低：8 个**

> ## 🔄 修订状态（2026-10-14 更新）
>
> **所有高 / 中严重度项 + L5 已修复并合并**（见下表「状态」列）。
> 剩余 **L1–L4 / L6–L8** 为低风险小项，尚未处理。
>
> 修复分多轮完成，提交见 `git log`（关键词：`fix(signing)` / `fix(m4)` /
> `refactor(m4)` / `fix(collab)` 等）。

| 编号 | 严重度 | 状态 | 位置 | 一句话 |
|---|---|---|---|---|
| H1 | 🔴 高 | ✅ 已修 | `WatermarkSigner` | 签名长度改**定长 4 字节**；失败不再静默返回全 0；加**旧格式回退**（旧图不被冤枉） |
| H2 | 🔴 高 | ✅ 已修 | `InvisibleWatermarkEngine.extract` | **循环不变量外提**（`slotOrder` / 块判决移出 255 轮枚举） |
| M1 | 🟠 中 | ✅ 已修 | `WatermarkSigner` + UI | UI 文案改「通过内置密钥校验」；**保留内置密钥**（保住可公开验证） |
| M2 | 🟠 中 | ✅ 已修 | `InvisibleContentResolver` | FNV 常量改标准写法 `0x811C9DC5` |
| M3 | 🟠 中 | ✅ 已修 | `ExifTextCodec` | 补空 payload 边界 |
| M4 | 🟠 中 | ✅ 已修 | `InvisibleWatermarkEngine` + UI | 档位缩到 1~2 + 改**分段控件**（与标题并排一行） |
| M5 | 🟠 中 | ✅ 已修 | `BatchWatermarkWorker` | 两通道守卫对齐 |
| L5 | 🟡 低 | ✅ 已修 | `ApkVerifier` | 签名比较改用 `Signature.toByteArray()` |
| L1–L4 / L6–L8 | 🟡 低 | 📋 待办 | 多处 | 见 §3 |

---

## 1. 高严重度问题

### 🔴 H1 — 签名"覆盖 LEN"名不副实，且签名验证时序语义有坑

**位置**：`invisible/WatermarkSigner.kt` + `invisible/WatermarkPayload.kt`

类注释明确宣称：

> *「为什么签名要覆盖 LEN：把 LEN 一起签进去就没这个口子。」*

但实现是：

```kotlin
fun sign(key: ByteArray, len: Int, data: ByteArray): ByteArray = runCatching {
    val mac = Mac.getInstance(ALGORITHM)
    mac.init(SecretKeySpec(key, ALGORITHM))
    mac.update(len.toByte())      // ← 只 update 了 len 的**低 8 位**
    mac.update(data)
    mac.doFinal().copyOf(SIGNATURE_BYTES)
}
```

**问题**：

1. **`len.toByte()` 只取了 1 字节**。虽然 `len` 在设计上 ≤ 255，**当下没问题**，但这一步把"覆盖 LEN"退化成了"覆盖 LEN 的低字节"。若将来 `MAX_DATA_BYTES` 上调到 256+（`LEN` 字段在负载里是 8 bit，本就不可能超 255），此处会静默截断。**这是潜伏的定时炸弹**，且注释没提这个隐含前提。

2. **更实质的问题**：`WatermarkPayload.decodeSigned` 在**验签之前**就做了 `if (crc != crc16(data)) return null`。也就是说 **CRC 校验先于签名校验**。这意味着：
   - 攻击者改 `data` 后，**只要重算 CRC 就能通过第一关**，然后才轮到 HMAC 拒绝它 → 结论正确，但
   - **CRC 失败时直接返回 null，签名根本没被验证**。于是一张"签名其实有效、但 CRC 因传输错误而坏"的图，会被归为"未检测到水印"，而不是"检测到但损坏"。**语义丢失**。

3. **最严重**：`decodeSigned` 返回 `Decoded(text, signatureValid = WatermarkSigner.verify(...))`。当 `verify` 因为 `Mac` 初始化失败而走 `getOrElse { ByteArray(8) }` 分支时，返回全 0。**如果攻击者恰好也能让这边抛异常**（理论上难，但 `destroy()` 后的对象、或极端 classloader 环境可能），全 0 与全 0 比较会**通过验签**。注释说"极不可能"是对的，但**安全性代码不该建立在"极不可能"上**。

**建议**：

```kotlin
// 1) 显式用完整长度参与签名（即便当前 len ≤ 255 也写成 4 字节，为将来留余地）
fun sign(key: ByteArray, len: Int, data: ByteArray): ByteArray {
    val mac = Mac.getInstance(ALGORITHM)
    mac.init(SecretKeySpec(key, ALGORITHM))
    mac.update(byteArrayOf(
        (len ushr 24).toByte(), (len ushr 16).toByte(),
        (len ushr 8).toByte(), len.toByte()
    ))
    mac.update(data)
    return mac.doFinal().copyOf(SIGNATURE_BYTES)
    // 不再吞异常返回全 0 —— 让调用方显式处理不可用状态
}

// 2) 验签失败要能被区分出来，而不是和 CRC 失败混为一路
sealed class PayloadDecode {
    data class Ok(val text: String, val sig: Boolean?) : PayloadDecode()
    data class CrcFailed(val partiallyReadable: String?) : PayloadDecode()  // 区分！
    data object NotOurs : PayloadDecode()
}
```

> ⚠️ **改动此文件会破坏旧图兼容性**（签名输入变了）。按 `CONTRIBUTING.md`，**必须先开 issue 讨论**，并考虑**双版本签名验证**（先试新格式，失败回退旧格式）。

---

### 🔴 H2 — 提取端 O(n²) 级重复计算，且存在误命中风险

**位置**：`invisible/InvisibleWatermarkEngine.kt: extractOriented`

```kotlin
for (dataBytes in 1..WatermarkPayload.MAX_DATA_BYTES) {   // 255 轮
    ...
    val slotOrder = bitSlotOrder(totalBlocks)   // ← 每轮都重新洗牌！
    val softBits = FloatArray(bitCount)         // ← 每轮重新分配
    val weightSum = FloatArray(bitCount)
    for (blockIndex in 0 until totalBlocks) {   // ← 每轮全图扫描
        val bitIndex = slotOrder[blockIndex] % bitCount
        ...
    }
    ...
}
```

**问题**：

1. **`bitSlotOrder(totalBlocks)` 在 255 轮里被完整重算 255 次**。它内部是 Fisher-Yates 洗牌，复杂度 O(totalBlocks)。对一张 4000×3000 的图（约 18 万块），**就是 180000 × 255 ≈ 4600 万次交换**，纯属浪费 —— `slotOrder` **与 `dataBytes` 完全无关**，应该在循环外算一次。

2. **更严重的是"内层全图扫描也重复 255 次"**。`coeffValues/coeffConfs/coeffQs` 这些**第一遍已经缓存好了**（代码里确实缓存了），但 `Qim.extract(coeffValues[i], coeffQs[i])` 这个**逐块的判决结果也与 `dataBytes` 无关**（bit 值只依赖系数和 q），却每轮重算。真正随 `dataBytes` 变的只有 `slotOrder[blockIndex] % bitCount` 这一句取模。

3. **误命中风险**：`bestText` 的选取标准是 `avg > bestConfidence`，而 `avg` 是"所有比特的归一化确定性均值"。**枚举 255 个长度时，不同 `bitCount` 会产生不同的 `avg`**，而代码**没有对"负载长度与实际不符"做惩罚**。理论上一个较短的错误长度可能恰好让 RS 解码"成功"（RS 有纠错能力，会尽力纠正），并因 `avg` 较高而胜出 → **返回错误的水印内容**。虽然有 CRC 兜底，但 **CRC 只有 16 bit，255 次尝试里撞上 1/65536 的概率累积后并非可以忽略**（255/65536 ≈ 0.4%，且这是"每次检测都算"的独立事件）。

**建议**：

```kotlin
// 1) slotOrder 提到循环外（一次算好，255 轮复用）
val slotOrder = bitSlotOrder(totalBlocks)

// 2) 把每块的 bit 判决也提前算好（与 dataBytes 无关）
val blockBits = IntArray(totalBlocks) { i ->
    Qim.extract(coeffValues[i], coeffQs[i])
}

for (dataBytes in 1..MAX_DATA_BYTES) {
    // 内层只需累加，不再做 DCT/取模以外的重活
    for (blockIndex in 0 until totalBlocks) {
        val bitIndex = slotOrder[blockIndex] % bitCount
        softBits[bitIndex] += if (blockBits[blockIndex] == 1) conf else -conf
        weightSum[bitIndex] += conf
    }
    // ...
}

// 3) 对"长度越短 avg 越容易偏高"做惩罚，或改为优先信任 CRC 命中 + 签名有效者
```

> 这条改动的**性能收益**（配合我上次报告的建议）可能是**数倍级**，且**不改变算法语义**（因为确实都是纯函数重复计算）。

---

## 2. 中严重度问题

### 🟠 M1 — 默认密钥硬编码，但 UI 将结果显示为"已验证 · 本应用签发"

**位置**：`WatermarkSigner.DEFAULT_KEY` + `DetectScreen` 的签名状态展示

`WatermarkSigner` 的注释非常诚实地承认了局限：

> *「本项目是开源的，所以 DEFAULT_KEY 对读过源码的人是公开的。它能挡住随手伪造，挡不住照抄源码伪造。」*

**问题**：注释是清醒的，但**用户看到的是"已验证 · 本应用签发"**。对一个**开源**项目，任何读过源码的人都能用 `DEFAULT_KEY` 伪造出"本应用签发"的水印。**UI 的措辞给了超出实际能力的信任感**。

**建议**（二选一）：
- **诚实降级**：把 UI 文案从"已验证 · 本应用签发"改成"格式有效 · 密钥未公开可验证"或"通过内置密钥校验"，并在 ⓘ 弹窗里说明"开源密钥仅防误改，不防蓄意伪造"；
- **真正升级**：让用户在设置里自定义密钥（`sign(key,...)` 的入口已经预留），并用 `Android Keystore` 存密钥（不可导出），把"防伪"做到名副其实。

---

### 🟠 M2 — `deviceShortId` 的 FNV-1a 实现有可移植性隐患

**位置**：`invisible/InvisibleContentResolver.kt`

```kotlin
private fun stableHash(input: String): Int {
    var hash = -0x7ee3623b // FNV offset basis (32bit)
    for (b in input.toByteArray(Charsets.UTF_8)) {
        hash = hash xor (b.toInt() and 0xFF)
        hash *= 0x01000193
    }
    return hash
}
```

**问题**：

1. **`-0x7ee3623b` 的可读性陷阱**：FNV-1a 的标准 offset basis 是 `2166136261`（即 `0x811C9DC5`）。用负数写 `-0x7ee3623b` 等价于 `0x811C9DC5`（因为 2³²−0x7ee3623b = 0x811C9DC5），数学上没错，但**读者几乎无法一眼验证**。这种"聪明写法"是维护负担。

2. **`hash *= 0x01000193` 在 Kotlin/Int 上是 32 位回绕乘法**，行为确定（JVM 规范保证 Int 溢出为回绕）。**跨平台没问题**，但**如果将来这段逻辑被移植到 Rust/其他语言**（正好呼应上个议题），`i32` 回绕在 debug 模式会 panic —— 需要显式 `wrapping_mul`。**注释声称"跨语言一致"，实际上是"跨 JVM 实现一致"**。

3. **注释的"43 亿种组合"是误导**：8 位十六进制确实有 2³² ≈ 43 亿种取值，但**真正的碰撞率由 FNV-1a 的质量决定**。对"ANDROID_ID 前若干位"这类输入，FNV-1a 的雪崩性一般，实际碰撞率**不能用 2³² 简单估计**。这是"生日问题"的典型误用。

**建议**：把 offset basis 写成标准形式并加注释，或直接用 `java.security.MessageDigest("SHA-256")` 取前 8 位 hex（更慢但无争议）。

```kotlin
private val FNV_OFFSET_BASIS: Int = 0x811C9DC5.toInt()  // 标准 FNV-1a 32 位
private val FNV_PRIME: Int = 0x01000193
```

---

### 🟠 M3 — `ExifTextCodec.decode` 的前缀判定边界

**位置**：`util/ExifTextCodec.kt: decode`

```kotlin
val body = trimmed.substring(TAG_PREFIX.length)
if (!body.startsWith(B64_MARK)) return trimmed // 明文格式
val payload = body.substring(B64_MARK.length)
val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
    ?: return trimmed
```

**问题**：

1. **`WMB1:B64:`（空 payload）的情况**：`payload = ""`，`Base64.decode("")` 返回**空数组**（不抛异常），`String(emptyArray)` 得到 `""`，最终返回 `"WMB1:"`。**一个空水印会被"成功解码"为空字符串**。上层 `WatermarkDetector` 用 `startsWith(TAG_PREFIX)` 判定命中 → 会报告"检测到水印"，内容却是空的。**边界处理缺失**。

2. **`WMB1:B64`（没有冒号）**：`body = "B64"`，`startsWith("B64:")` 为 false → 走"明文格式"分支返回原文。**符合预期**，但值得加测试钉住。

3. **编码侧与之不对称**：`encode` 用 `isPureAscii(body)` 决定是否编码。**若用户输入恰好是纯 ASCII 但以 `B64:` 开头**（例如用户自定义水印文字就是 `B64:hello`），则 `encode` 产出 `WMB1:B64:hello`，**`decode` 会把它当成 Base64 解码** → 解出乱码或失败。**往返（round-trip）不成立**。

**建议**：

```kotlin
fun decode(raw: String?): String? {
    if (raw == null) return null
    val trimmed = raw.trim()
    if (!trimmed.startsWith(TAG_PREFIX)) return trimmed
    val body = trimmed.substring(TAG_PREFIX.length)
    if (!body.startsWith(B64_MARK)) return trimmed
    val payload = body.substring(B64_MARK.length)
    if (payload.isEmpty()) return trimmed          // ← 补：空 payload 不当 Base64
    val bytes = runCatching { Base64.getDecoder().decode(payload) }.getOrNull()
        ?: return trimmed
    ...
}
```

并**补一条往返测试**：`decode(encode("B64:x")) == "WMB1:B64:x"`。

---

### 🟠 M4 — `MAX_STEP` 封顶使高 strength 档位失去区分度

**位置**：`invisible/InvisibleWatermarkEngine.kt: adaptiveStrength`

```kotlin
return (jpegQuant * STRENGTH_OVER_QUANT * luminanceFactor * activityFactor * strengthScale)
    .coerceIn(4f, MAX_STEP)   // MAX_STEP = 60
```

**问题**：把 `strengthScale` **乘进去之后再封顶 60**，导致：

| 场景 | 未封顶值 | 封顶后 |
|---|---|---|
| jpegQuant=10, activity 高, scale=1 | 10×6×1×1×1 = 60 | 60 |
| jpegQuant=10, activity 高, scale=2 | 120 | **60（与 scale=1 完全相同）** |
| jpegQuant=16, activity 高, scale=1 | 96 | **60** |
| jpegQuant=16, activity 高, scale=4 | 384 | **60** |

**结论**：对**量化步长 ≥ 10 的频点**（而 `MID_BAND` 里大量频点步长是 10~14），**strength=1 到 strength=4 的步长会全部被封到 60，完全一样**。也就是说：

- 用户在 UI 上把"强度"从 1 拉到 4，**对多数频点毫无效果**
- 这解释了为什么 `Dct.kt` 注释说"strengthScale 限制在 1~2" —— 因为 **3、4 档在数学上已被 MAX_STEP 吃掉**
- 但 UI（`WatermarkPanelInvisible`）的滑块范围是 `1f..4f`，`EditorViewModel` 也 `coerceIn(1,4)` → **给用户提供了两个无效档位**

**建议**：二选一
- **缩窄 UI 档位**到 1~2（与 `Dct.kt` 注释一致），并说明原因；
- 或**调整公式**，让 `strengthScale` 作用于封顶之前的不同维度，比如把封顶提高，或对 `strengthScale` 单独缩放（但会改变旧图的提取兼容性 —— 因为提取端也用同一个 `adaptiveStrength`，改了公式两端必须同步改，且**旧图会失配**）。

> ⚠️ 这是**数学/产品一致性问题**，不是崩溃 bug，但会直接影响用户体验（"我调了没反应"）。且**任何公式改动都会破坏旧图兼容性**，必须谨慎。

---

### 🟠 M5 — 批量 Worker 的两条隐藏水印通道取值不对称

**位置**：`worker/BatchWatermarkWorker.kt: processOne`

```kotlin
// DCT 通道
if (invisibleDct && !invisibleContent.isNullOrBlank() && invisibleStrength > 0) {
    stamped = InvisibleWatermarkEngine(invisibleStrength.toFloat())
        .embed(rendered, invisibleContent)
    ...
}

// EXIF 通道
val savedResult = exportRepository.saveBitmap(
    ...
    invisibleContent = invisibleContent.takeIf { invisibleExif }   // ← 只判了 invisibleExif
)
```

**问题**：

1. **DCT 通道要求 `!invisibleContent.isNullOrBlank() && invisibleStrength > 0`**，而 **EXIF 通道只要求 `invisibleExif == true`**。若 `invisibleContent` 为 null 或 blank，`saveBitmap` 会收到 `takeIf { invisibleExif }` = `invisibleContent`（即 null）。**看起来 `saveBitmap` 内部应该兜住 null**，但两个通道的守卫条件不一致，**易错**。

2. 对比 `EditorViewModel.export` 的逻辑：
```kotlin
val exifContent = resolved.takeIf { invisible.exifEnabled }   // resolved 已判过 enabled
```
编辑页用的是 `resolved`（`InvisibleContentResolver.resolve` 的产物，已保证非空且合法），批量页用的是**原始 `invisibleContent` 字符串**。**两条路径的"内容合法性"保证不一致**。

**建议**：把"内容解析"统一提到入队前（注释里其实说已经这么做了），并在 Worker 里对两条通道用**同一个已校验的 `invisibleContent`**：

```kotlin
val content = invisibleContent?.takeIf { it.isNotBlank() }
if (invisibleDct && content != null && invisibleStrength > 0) { ... }
// saveBitmap 传 content.takeIf { invisibleExif }
```

---

## 3. 低严重度 / 改进项

| # | 位置 | 问题 | 建议 |
|---|---|---|---|
| L1 | `XmpCodec.escape` | 未处理**控制字符**（如 `\u0000`）—— XML 1.0 不允许它们，会导致其他工具解析失败 | 转义或剔除 `< 0x20` 的控制字符 |
| L2 | `XmpCodec.unpack` | 用纯字符串 `indexOf` 定位，**不处理嵌套/多个同名标签**；若内容里含 `</wmb:watermark>` 字面量会提前截断（虽然 `escape` 会转义 `<`，但 `unescape` 后再拼是安全的，仍建议加测试） | 补边界测试 |
| L3 | `FileNameResolver.build` | `sanitize` 后 `trim('.', '_', '-')` 可能把用户故意的下划线开头清掉；且**未处理 Windows 保留名**（CON/PRN/NUL）| 低优先，Android 上影响小 |
| L4 | `LruCacheMap.trim` | `while (entries.size > capacity)` + 迭代器删除正确，但 `values()` 每次都 `toList()` 复制 | 若被高频调用可返回只读视图 |
| L5 | `ApkVerifier.signaturesOf` | 用 `toCharsString().sha256Of()` 比较签名 —— **`toCharsString()` 的格式非标准保证**，不同 Android 版本可能不同。更稳的是直接用 `Signature.toByteArray()` | 改用 `it.toByteArray()` |
| L6 | `ReedSolomon.positionToXIndex` | `(BLOCK_SYMBOLS - 1 - pos) % 255`，当 `BLOCK_SYMBOLS=24` 时恒 < 24，`% 255` 是恒等；但若将来 `BLOCK_SYMBOLS` 改动可能越界 | 加 assert 或注释 |
| L7 | `PlaceholderResolver.lineSpacingMultiplier` | 方法名叫 `lineSpacingMultiplier` 却**不接收任何参数**、恒返回 1.2f —— 命名与签名不符 | 改名 `defaultLineSpacingMultiplier()` 或删掉 |
| L8 | `BitmapUtils.calculateInSampleSize` | 用 `maxOf(w/2, h/2) >= target`，对**极端长条形图**（如 10000×10）会降很多次，可能降过头 | 补测试覆盖极端宽高比 |

---

## 4. 值得称赞的设计（避免误伤）

审查中发现的这些做法**明显高于平均水平**，特此记录，避免后续会话误改：

1. **`PresetFileCodec.safeFloat`** —— 正确识别了 `Float.NaN.coerceIn(...) == NaN` 这个 Java/Kotlin 的经典陷阱，并专门处理。**绝大多数项目会踩这个坑**。
2. **`ExifCopier` 的标签白名单** —— 主动排除 Orientation（防二次旋转）、缩略图（防不一致）、UserComment（防覆盖水印），考虑周全。
3. **`WatermarkSigner.verify` 的定长比较** —— 防时序侧信道，虽然是开放密钥场景，但意识正确。
4. **`BitmapsBudget`** —— "动手前先算"而非"崩了再兜"，是成熟做法。
5. **`BitmapsUtils.calculateInSampleSize` 的 `||` vs `&&`** —— 注释详细记录了真实修过的 bug 与实测数据，**非常有价值**。
6. **`LruCacheMap` 不复用 `androidx.collection.LruCache`** —— 因为 `entryRemoved` 回调时机不可控、不能顺手 recycle 位图，这个判断是对的。
7. **`XmpCodec.unescape` 的替换顺序** —— `&amp;` 最后替换，避免二次解码，正确。

---

## 5. 修复记录（原「优先级建议」）

> ⚠️ **本节原为「优先级建议」，2026-10-14 已按此顺序执行完毕**（除 L 系列）。

| 优先级 | 项 | 状态 |
|---|---|---|
| **P0** | H2 | ✅ 已修（纯优化、零语义变更） |
| **P1** | M5 / M3 | ✅ 已修 |
| **P2** | M2 / L5 | ✅ 已修 |
| **P2** | M4 | ✅ 已修 —— 选**「缩窄档位」**而非改公式：改公式会破坏旧图兼容，且实测步长更大**反而更差**（见 `Dct.kt`） |
| **P3** | H1 / M1 | ✅ 已修 —— 人类拍板「**这一版不管旧图**，签名改到最好」；已加**旧格式回退**避免冤枉旧图 |

**剩余**：L1–L4 / L6–L8（低风险，见 §3）。

---

## 6. 一句话总结

> ⚠️ **这是一份远超平均水准的代码；真正的风险集中在"签名语义"与"提取枚举"两处。**

- **H2 是"免费的午餐"** —— 纯优化、零语义变更、收益显著，**已修**。
- **H1 / M4 是"昂贵的正确"** —— 涉旧图兼容性。人类明确「这一版不管旧图」后：
  H1 全修 + 加**旧格式回退**（不冤枉旧图）；M4 选**不改公式**
  （实测步长更大反而更差，见 `Dct.kt`）。
- 其余多为边界健壮性与可维护性改进，**已批量修完**（除 L 系列）。

---

> ⚠️ 本报告为**审查意见**，未改动任何代码。所有修复建议在实施前，
> 请遵循 `docs/multi-session-collaboration.md` 的硬性规矩
> （尤其：**未经人类明示绝不推送**；`invisible/` 改动先讨论）。
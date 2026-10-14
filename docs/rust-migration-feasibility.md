# Rust 迁移可行性调研报告

> **性质**：实验性技术调研，非执行计划。
> **结论先行**：**部分可行（有条件），且当前收益-成本比不划算。**
> **日期**：2026-10-13
> **环境实测**：本机（aarch64 proot）实测数据见 §2，非推测。

---

## 0. 一页结论

> ⚠️ **结论：技术上做得到，但当前不值得做**

| 结论 | 判定 |
|---|---|
| **技术上能否实现** | ✅ 能。Rust 工具链已在本机就绪，Android target 可添加 |
| **包体积代价** | ⚠️ **高风险**。APK 现 3.6 MB，加一个 `.so` 至少翻倍，多 ABI 更甚 |
| **性能收益** | ⚠️ **大概率低于预期**。瓶颈在内存带宽与 GC，不在语言 |
| **开发前提出问题** | 🔴 **本机环境缺 NDK**，装 NDK 又要 1~2 GB 且 proot 下构建慢 |
| **维护成本** | 🔴 **很高**。多语言构建链、跨语言调试、多会话协作复杂度上升 |
| **推荐** | ❌ **不建议现在做**。建议先做 §7 的「零成本替代方案」验证真实瓶颈 |

---

## 1. 先定义：什么叫"耗时长、占资源多"

在讨论 Rust 之前，必须先**用数据定位瓶颈**，否则容易优化错对象。
我把项目里"重"的部分逐个列出，并判断它**到底重在哪**：

| 候选模块 | 位置 | 计算量 | 真瓶颈 | 适合 Rust？ |
|---|---|---|---|---|
| **DCT 正/逆变换** | `invisible/Dct.kt` | O(块数×64×64) 浮点 | CPU 浮点 + 内存 | 理论适合 |
| **RS 编解码** | `invisible/ReedSolomon.kt` | 符号级，规模很小 | 可忽略 | ❌ 不值得 |
| **QIM 调制** | `invisible/Dct.kt` | 每块 O(1) | 可忽略 | ❌ 不值得 |
| **隐藏水印嵌入/提取** | `InvisibleWatermarkEngine.kt` | 全图逐块 DCT | CPU + **大量数组分配** | 部分适合 |
| **可见水印渲染** | `watermark/WatermarkRenderer.kt` | Canvas 绘制 | **GPU/Canvas 已加速** | ❌ 已加速 |
| **图片解码/编码** | `ExportRepository` | JPEG/PNG 编解码 | **系统 codec（C++）** | ❌ 已原生 |

> **关键洞察**：
> - 真正"可搬"的只有 **`invisible/` 里的 DCT 相关热路径**（约 2~3 个文件）。
> - RS 纠错、QIM、渲染、编解码 **要么太小、要么已经原生**，搬了纯亏。
> - 这意味着 Rust 化后的收益天花板，**被限制在整个 App 的一小块**。

---

## 2. 本机环境实测（一手数据，非推测）

我在当前 aarch64 proot 环境里实际执行了检查，结果如下：

```
== 架构 ==
uname -m                 → aarch64
rustc -vV (host)         → aarch64-unknown-linux-gnu
rustc --version          → rustc 1.97.1 (2026-07-14)   ← 已装！

== 已装 target ==
rustup target list --installed
                         → aarch64-unknown-linux-gnu
                           aarch64-linux-android       ← 我刚成功添加了它

== Android SDK ==
ANDROID_HOME             → $ANDROID_HOME
build-tools              → 35.0.0 / 36.0.0
platforms                → android-35
cmdline-tools/latest     → 含 sdkmanager（可装 NDK）

== NDK ==
$ANDROID_HOME/ndk        → **不存在**   ← 🔴 关键缺口
ANDROID_NDK_HOME         → 未设置

== 其他 ==
Java                     → OpenJDK 17.0.19
磁盘可用                 → 113 GB
```

### 2.1 这意味着什么

> ⚠️ **好消息：Rust 已装、target 可加；坏消息：缺 NDK（链接器）**

- ✅ **Rust 编译器已就绪**（1.97.1，stable）
- ✅ **`aarch64-linux-android` target 一条命令就加上了**（我实测通过）
- 🔴 **但没有 NDK** —— 交叉编译 Android 库需要 NDK 里的 **linker（clang）+ 系统库**，否则 `cargo build --target aarch64-linux-android` 会在链接阶段失败
- ✅ 好消息：`sdkmanager` 在，**可以装 NDK**（列表里有 `ndk;23.x` ~ `ndk;26.x` 等多个版本可选），磁盘也够

### 2.2 但要装 NDK 的代价

| 项 | 代价 |
|---|---|
| NDK 体积 | **约 1.5 ~ 2.5 GB**（含多 ABI 工具链）|
| 下载时间 | 取决于网速，proot 下可能很久 |
| proot 构建速度 | **显著慢于真机/PC** —— 这正是项目已有 `setup_android_env.sh` 和 aapt2 aarch64 强制配置的原因 |
| 后续每次 CI | 需要额外缓存 NDK + Rust toolchain |

> ⚠️ 别忘了一个反直觉事实：**Android 是 Rust 的「目标平台」但官方不是「宿主平台」。**
> 我们能在 proot 里装 Rust 是因为它伪装成了 `aarch64-unknown-linux-gnu`。
> Termux 原生环境下 rustup 会识别成 `aarch64-linux-android` 而**找不到工具链**。
> 本机走的是 proot 的 gnu 宿主，所以侥幸可用 —— 但这是**非官方支持路径**，
> 遇到奇怪问题时要自己去啃，没有社区现成答案。

---

## 3. Rust on Android 的技术路线（调研结果）

业界目前三条主流路线：

### 路线 A：`cargo-ndk` + Gradle 插件（最主流）

```
Kotlin ──JNI──► Rust (.so)
```

- 工具：[`bbqsrc/cargo-ndk`](https://github.com/bbqsrc/cargo-ndk)，处理所有 NDK 环境变量
- Gradle 侧：[`rust-android-gradle`](https://github.com/mozilla/rust-android-gradle)（Mozilla 维护）
- 自动生成 `jniLibs/<abi>/lib*.so` 目录结构
- **优点**：控制力强，能精调 ABI、优化级别
- **缺点**：手写 JNI 胶水，`unsafe` 边界容易出 bug

### 路线 B：UniFFI（Mozilla，推荐给"接口多"的场景）

```
Rust ──(UDL/宏)──► 自动生成 Kotlin 绑定
```

- 工具：[`mozilla/uniffi-rs`](https://github.com/mozilla/uniffi-rs)
- 从 Rust 定义自动生成 **类型安全的 Kotlin 绑定**，无需手写 JNI
- **优点**：跨语言边界安全，接口演进友好，同时可复用到 Swift
- **缺点**：多一层代码生成 + 学习成本；对小项目偏重

### 路线 C：`cargo-apk` / `cargo-mobile2`（全 Rust 项目）

- 适合**纯 Rust 应用**，本项目是 Kotlin/Compose 主导，**不适用**

> 对本项目：若要尝试，**路线 A 起步、必要时升级到 B**。但注意——
> 本项目只有 2~3 个可搬的热点函数，**上 UniFFI 属于杀鸡用牛刀**。

---

## 4. 性能收益：能拿到多少？（诚实分析）

### 4.1 好消息：热点小、适合搬

DCT 是纯数值计算，Rust 相对 Kotlin/JVM 可能有 **1.5× ~ 3×** 提升。

### 4.2 坏消息：三个"但是"

**① JNI 调用开销不小，且倒逼"粗粒度"接口设计**

实测数据（社区基准）：

| 调用类型 | 单次开销 |
|---|---|
| 普通 JNI | ~115 ns |
| `@FastNative` | ~35 ns |
| `@CriticalNative` | ~25 ns |

单次调用 100ns 听起来小，但隐藏水印是**逐块循环**的。若设计不当
（每个块一次 JNI 调用），**开销会直接吃掉收益**。
→ 必须设计成 **"整张图一次进去、一次出来"** 的粗粒度接口。

**② 真正的瓶颈可能是内存带宽，不是语言**

`EnginePureSimulationTest` 那条链路里，热路径同时做：
- 逐像素读写 `IntArray(width*height)`
- 每块 64 浮点 DCT
- 大量 `FloatArray(64)` 中间分配

这些是**内存带宽 + 分配**问题。Rust 也要读写同样的内存，
换语言**不会让内存变快**。收益会被高估。

**③ JVM 的 JIT 在长时间数值循环里并不慢**

社区共识：JVM 经过 JIT 预热后，在**热循环**上能与 Rust 打平甚至偶尔更优
（尤其涉及分配时，JVM 的 GC 批处理可能优于 Rust 的即时 drop）。
而隐藏水印嵌入恰好是"长时间跑同一段循环"的场景 —— **JIT 最擅长的形态**。

### 4.3 收益预估

> ⚠️ **乐观：隐藏水印模块提速 1.5~2×；全局感知提升趋近于 0**

- 用户感知到的总耗时 = 解码 + 渲染 + 嵌入 + 写文件
- **嵌入只是其中一段**；即便这段快一倍，整链路可能只快 **10~20%**
- 而代价是 §5 的全部成本

---

## 5. 代价清单（这笔账必须算清）

| 代价 | 具体影响 | 严重度 |
|---|---|---|
| **APK 体积** | 现 3.6 MB。加 `.so`：单 ABI 约 +1~~3 MB，多 ABI（arm64+armv7+x86_64）可**翻倍到 10 MB+** | 🔴 高 |
| **构建复杂度** | Gradle 需接 `cargo-ndk`；CI 需缓存 NDK+toolchain；proot 下构建更慢 | 🔴 高 |
| **环境缺口** | 本机**无 NDK**，需装 1.5~2.5 GB | 🔴 高 |
| **调试难度** | 跨语言栈回溯、`unsafe` 内存 bug、SIGSEGV 难定位 | 🟠 中高 |
| **多会话协作** | 违反项目现有"多会话并行"的简洁性；新人/新会话上手成本陡增 | 🟠 中高 |
| **测试割裂** | 现有"纯 JVM 端到端测试"优势丧失；需新增 Rust 侧测试 + 跨语言集成测试 | 🟠 中 |
| **CI 时间** | 每次构建多编译一遍 Rust；多 ABI 更久 | 🟡 中 |
| **版权/许可** | 需审计所有 Rust crate 许可证（Apache-2.0 项目要干净）| 🟡 中 |

> ⚠️ 特别提醒：项目 `README` 明确把 **"APK 3.6 MB"** 当作卖点徽章。
> Rust 化几乎必然摧毁这个数字。

---

## 6. 与项目现状的冲突

### 6.1 与「隐藏水印极其敏感」的冲突

`CONTRIBUTING.md` 明确写：

> 改动选点策略 / 步长公式 / 载荷格式 **会导致旧图无法解码**，改前请先讨论。

把 DCT 用 Rust 重写，**数学必须逐位一致**，否则：
- 旧图检测失败（灾难）
- 需要写"跨语言等价性测试"来保证 bit-exact

→ 这不是不能做，但**风险极高、验证成本极高**。

### 6.2 与「多 AI 会话协作」的冲突

`docs/multi-session-collaboration.md` 是一套精巧的协作机制。
引入 Rust 后：
- 多一条构建链（Cargo）
- 多一套依赖管理
- 多一处"工作区→仓库"同步对象
- 每个会话都要理解 JNI 边界

→ **显著增加跨会话误判概率**，与项目追求"减少返工"的目标相悖。

---

## 7. 推荐方案：先做"零成本替代"，再谈 Rust

> ⚠️ **90% 的可能：换语言解决不了真问题**

**Rust 之前，先花半天做这三件事**（成本≈0，不动架构）：

### ① 先测，别猜

```bash
# 用项目已有的基准测试摸清真实耗时分布
./gradlew :app:testDebugUnitTest
# 关注 RenderPerformanceTest / EnginePureSimulationTest 的耗时
```

用 Android Studio Profiler 或简单的 `System.nanoTime()` 打点，
回答：**嵌入到底占整条链路的百分之几？**
若 < 20%，Rust 化收益必然 < 10%，直接劝退。

### ② 就地优化 Kotlin（往往收益惊人）

热路径里可立即改进的点（**全部零风险、零新依赖**）：

| 优化点 | 做法 | 预期收益 |
|---|---|---|
| **消除重复分配** | `FloatArray(64)` 已在 `embed()` 里复用 ✓，但 `extractOriented` 的循环枚举（1..255）每轮重新分配 | 中 |
| **提前剪枝** | `extract` 枚举 255 个候选长度，每个都做全图投票 → 先用 CRC 快速筛 | **高** |
| **缓存 slotOrder** | `bitSlotOrder()` 在 255 次枚举里被重复调用 | **高** |
| **并行化** | DCT 逐块独立 → 用 `Dispatchers.Default` 分片并行 | **高** |
| **`FloatArray` → 栈上/复用** | 减 GC 压力 | 中 |

> 尤其 **"缓存 slotOrder + 提前剪枝"** 这两条，可能在**不换语言**的前提下
> 就把检测速度提升数倍 —— 这是最划算的一步。

### ③ 若一定要碰原生：用 NDK 的 C++ 而非 Rust

对齐项目现状：**AGP 已配置 NDK 相关钩子**（`externalNativeBuild` 在注释里被提及）。
用 C++ + NDK 走**项目已有的构建体系**，比引入全新 Rust 工具链**更贴合现状**。
（但需要装 NDK，见 §2.2）

---

## 8. 如果最终还是想做：最小可行的 PoC 方案

若你确认要实验，**建议这么起步**（把风险控制到最小）：

### 阶段 0：装环境
```bash
# 装 NDK（选一个版本，建议 26.x）
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "ndk;26.0.10792818"
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/26.0.10792818
```

### 阶段 1：只搬"一个函数"验证闭环
**只做 `Dct8x8.forward` + `inverse`**（纯函数、无状态、最好验证）：

```toml
# rust/dct/Cargo.toml
[package]
name = "wmb_dct"
edition = "2021"

[lib]
crate-type = ["cdylib"]

[dependencies]
jni = "0.21"
```

```rust
// rust/dct/src/lib.rs —— 关键：粗粒度接口（整块数组进、整块数组出）
use jni::objects::{JClass, JFloatArray};
use jni::sys::jfloatArray;
use jni::JNIEnv;

#[no_mangle]
pub extern "system" fn Java_com_watermarkbox_invisible_NativeDct_forwardBatch(
    mut env: JNIEnv,
    _class: JClass,
    input: JFloatArray,   // 长度 = 64 * blockCount
) -> jfloatArray {
    // ⚠️ 一次 JNI 调用处理所有块 —— 绝不能改成"每块一次调用"
    let n = env.get_array_length(&input).unwrap() as usize;
    let mut buf = vec![0f32; n];
    env.get_float_array_region(&input, 0, &mut buf).unwrap();
    // ... 对每 64 个元素做 DCT ...
    let out = env.new_float_array(n as i32).unwrap();
    env.set_float_array_region(&out, 0, &buf).unwrap();
    out
}
```

```bash
# 编译（cargo-ndk 需要先 cargo install）
cargo install cargo-ndk
cargo ndk -t arm64-v8a -o app/src/main/jniLibs build --release
```

### 阶段 2：等价性验证（**这一步不可跳过**）
```kotlin
@Test
fun `Rust DCT 与 Kotlin DCT 必须逐位一致`() {
    // 随机 1000 个块，两边都跑，断言结果完全相等
    // 只有这个测试通过，才允许进入下一步
}
```

### 阶段 3：只把"嵌入/提取"切过去，跑性能对比
**保留 Kotlin 实现作为 fallback**，用开关切换，A/B 对比真实收益。

### 阶段 4：只有收益显著（>1.5×）才考虑全量切换

---

## 9. 决策矩阵（帮你拍板）

| 你的真实目标 | 推荐做法 |
|---|---|
| **想让导出/检测更快** | ❌ 别上 Rust。先做 §7② 的 Kotlin 优化（尤其剪枝+缓存 slotOrder）|
| **想减小 APK** | ❌ Rust 只会让它更大 |
| **想学 Rust/NDK（学习目的）** | ✅ 可以做 §8 的 PoC，但**别合进主干**，放独立分支 |
| **隐藏水印确实是瓶颈且其他招都用尽了** | ⚠️ 才考虑 Rust，且必须过 §8 阶段 2 的等价性测试 |

---

## 10. 一句话总结

> ⚠️ **技术上可行（Rust 已就绪、target 可加），但成本高、收益存疑、风险大。**

> ⚠️ **建议：先零成本优化 Kotlin，用数据说话；确需原生再谈 Rust。**

Rust 是一把好锤子，但我们得先确认**这真的是颗钉子** ——
而目前的证据表明：**瓶颈更可能在算法与内存使用方式上，而非编程语言。**

---

## 附录：本报告依据（可复核）

| 结论 | 来源 |
|---|---|
| cargo-ndk 工作方式 | github.com/bbqsrc/cargo-ndk |
| UniFFI 绑定生成 | github.com/mozilla/uniffi-rs |
| JNI 开销 ~115/35/25 ns | Substack「JNI Marshalling Performance」+ Android 官方 `@FastNative` 文档 |
| Android 是 target 非 host | cosyra.com「Rust on Termux」+ rustup issue #2872 |
| JVM JIT 热循环性能 | programming-language-benchmarks.vercel.app + r/Kotlin 讨论 |
| turbojpeg 等图像 crate 存在 | lib.rs/crates/turbojpeg（但本项目用系统 codec，不需要）|
| 本机环境数据 | 本文 §2，于 2026-10-13 实测 |

> ⚠️ 本报告为**调研性质**。任何实际改动前，请遵循
> `docs/multi-session-collaboration.md` 的硬性规矩（尤其是**未经人类明示绝不推送**）。

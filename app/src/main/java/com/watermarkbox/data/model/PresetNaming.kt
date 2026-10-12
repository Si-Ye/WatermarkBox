package com.watermarkbox.data.model

/**
 * 预设命名的**纯函数**工具集。
 *
 * 之所以单独放一个 object 而不是塞在 `SettingsRepository` 里：
 * `SettingsRepository` 的构造需要 `Context`，而命名逻辑本身**零依赖**。
 * 抽出来后可以直接单元测试，不必为了测一个字符串算法去构造 Android 环境。
 *
 * （这是从一次教训里学到的：不要为了测试而"复刻"一份实现，
 *   那样测的不是真实代码，改了一边另一边不会报错。）
 */
object PresetNaming {

    /**
     * 生成不重复的名称：`原名 (2)`、`原名 (3)`…
     *
     * 用「 (n)」而不是纯数字后缀，是为了保持可读性，
     * 且避免与用户自己命名的 "名称2" 撞车（那是另一个名字，不算冲突）。
     *
     * @param base  希望使用的名称
     * @param taken 已占用的名称集合
     */
    fun uniqueName(base: String, taken: Collection<String>): String {
        if (base !in taken) return base
        var n = 2
        while ("$base ($n)" in taken) n++
        return "$base ($n)"
    }
}
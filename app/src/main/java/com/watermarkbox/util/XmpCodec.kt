package com.watermarkbox.util

/**
 * XMP 元数据的**最小封装**。
 *
 * ## 为什么除了 EXIF 还要写 XMP
 *
 * 有些工具（尤其图床、部分修图软件）会**剥掉 EXIF**，却保留 XMP ——
 * 在它们看来 XMP 才是「标准元数据容器」。多写一份，覆盖面更广；
 * 对用户来说仍是零成本（一样不碰像素）。
 *
 * ## 为什么不用完整 XMP 骨架
 *
 * 完整 XMP 要 dc / xmp / xmpMM 等一堆命名空间，我们只需要
 * **一个字段**。塞进去只会让文件变大、还可能跟别的工具打架。
 *
 * ## 为什么内容要转义
 *
 * XMP 是 XML。水印内容可能含 `<` `&` 等字符，直接拼进去会产生
 * 非法 XML，对方工具解析时整包都可能丢掉。这里统一转义。
 */
object XmpCodec {

    /** 自定义命名空间。用仓库地址，避免与别人的字段重名。 */
    private const val NAMESPACE = "https://github.com/Si-Ye/WatermarkBox"

    /** 命名空间前缀。 */
    private const val PREFIX = "wmb"

    /** 承载水印的字段名。 */
    private const val FIELD = "watermark"

    /** 我们自己写入的字段标签（也用于探测 XMP 里有没有我们的东西）。 */
    val FIELD_TAG: String get() = "<$PREFIX:$FIELD>"

    /**
     * 把内容打成最小合法的 XMP 包。
     *
     * `xpacket` 头尾是 XMP 规范要求的（某些解析器缺了会拒收），
     * 因此即使内容很少也保留。
     */
    fun pack(content: String): String = buildString {
        append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n")
        append("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        append("    <rdf:Description rdf:about=\"\" xmlns:$PREFIX=\"$NAMESPACE\">\n")
        append("      <$PREFIX:$FIELD>${escape(content)}</$PREFIX:$FIELD>\n")
        append("    </rdf:Description>\n")
        append("  </rdf:RDF>\n")
        append("</x:xmpmeta>\n")
        append("<?xpacket end=\"w\"?>")
    }

    /**
     * 从 XMP 里取回内容。
     *
     * ## 为什么不用 XML 解析器
     *
     * 我们只取自己写的那一个字段，而这份 XMP 可能已被别的工具
     * 改写过（加了它自己的命名空间、重排了节点顺序，甚至换成
     * 了 RDF 数组形式）。直接按标签定位比「先解析成树再遍历」
     * 更省事，也不怕对方塞进来的节点我们不认识。
     *
     * @return 内容；XMP 为空或没有我们的字段时返回 null
     */
    fun unpack(xmp: String?): String? {
        if (xmp.isNullOrBlank()) return null
        val open = FIELD_TAG
        val close = "</$PREFIX:$FIELD>"
        val start = xmp.indexOf(open)
        if (start < 0) return null
        val end = xmp.indexOf(close, start + open.length)
        if (end < 0) return null
        return unescape(xmp.substring(start + open.length, end))
    }

/**
     * XML 实体前缀。
     *
     * 单独提出来拼接，**不在字符串字面量里直接写 `&`** ——
     * 那个字符经过多层文本处理容易出岔子（本项目就踩过一次：
     * 字面量里的 `"` 在写文件时被解码成了真正的引号，
     * 导致源码语法错误）。
     */
    private const val AMP = "&"

    private fun escape(s: String): String = buildString(s.length) {
        s.forEach { c ->
            when (c) {
                '&' -> append(AMP + "amp;")
                '<' -> append(AMP + "lt;")
                '>' -> append(AMP + "gt;")
                '"' -> append(AMP + "quot;")
                '\'' -> append(AMP + "apos;")
                else -> append(c)
            }
        }
    }

    private fun unescape(s: String): String = s
        .replace(AMP + "lt;", "<")
        .replace(AMP + "gt;", ">")
        .replace(AMP + "quot;", "\"")
        .replace(AMP + "apos;", "'")
        // `&amp;` 必须**最后**替换：否则 `&amp;lt;` 会被二次解码成 `<`
        .replace(AMP + "amp;", AMP)
}
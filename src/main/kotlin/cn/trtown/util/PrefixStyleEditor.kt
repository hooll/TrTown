package cn.trtown.util

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 逐字符样式模型与静态渐变算法（纯函数）。
 *
 * 文本语义以 Unicode code point 为单位，与 [PrefixCodec] 的可见字数口径一致。
 * 输出仍是既有 `&0..f / &#RRGGBB / &k..o / &r` 规范 source，不引入新的存储格式。
 */
object PrefixStyleEditor {

    /** 一个可见字符及其样式；color 为 `&a` 或 `&#RRGGBB`，null 表示继承外层外壳的颜色。 */
    data class StyledChar(val value: String, val color: String?, val formats: Set<Char>)

    /** 格式码输出顺序固定，保证同一造型多次序列化结果稳定。 */
    private val FORMAT_ORDER = listOf('k', 'l', 'm', 'n', 'o')

    /** 传统 16 色的 RGB 值，用于把颜色转成渐变插值用的十六进制。 */
    private val LEGACY_RGB = mapOf(
        '0' to "000000", '1' to "0000AA", '2' to "00AA00", '3' to "00AAAA",
        '4' to "AA0000", '5' to "AA00AA", '6' to "FFAA00", '7' to "AAAAAA",
        '8' to "555555", '9' to "5555FF", 'a' to "55FF55", 'b' to "55FFFF",
        'c' to "FF5555", 'd' to "FF55FF", 'e' to "FFFF55", 'f' to "FFFFFF",
    )

    /**
     * 解码为逐字符样式；原文非法时返回 null。
     *
     * 没有颜色码 = `null`，表示继承外层 display-format 的颜色；
     * 显式 `&f` 与 `&r` 都保留为 `&f`，不能和“继承”混为一谈。
     * 颜色码按 Minecraft 语义重置格式。
     */
    fun decode(source: String, allowColor: Boolean): List<StyledChar>? {
        val lex = PrefixCodec.lex(source)
        if (lex.issue != null) return null
        val chars = ArrayList<StyledChar>(lex.tokens.size)
        var color: String? = null
        var formats: Set<Char> = emptySet()
        for (token in lex.tokens) {
            when (token) {
                is PrefixToken.LegacyColor -> {
                    if (allowColor) {
                        // 显式 &f 是“白色”，不是“未指定颜色”；null 只表示继承外层外壳
                        color = "&${token.code}"
                        formats = emptySet()
                    }
                }

                is PrefixToken.HexColor -> {
                    if (allowColor) {
                        color = "&#${token.rgb}"
                        formats = emptySet()
                    }
                }

                is PrefixToken.Format -> {
                    if (allowColor) {
                        if (token.code == 'r') {
                            // &r 在非白外壳里会重置为默认白色，不能当成“继承”
                            color = "&f"
                            formats = emptySet()
                        } else {
                            formats = formats + token.code
                        }
                    }
                }

                is PrefixToken.Text -> chars += StyledChar(
                    value = token.value,
                    color = if (allowColor) color else null,
                    formats = if (allowColor) formats else emptySet(),
                )
            }
        }
        return chars
    }

    /**
     * 序列化为规范 source；当前规则无法无损表达时返回 null。
     *
     * - `null` 颜色 = 未指定，继承外层 display-format 的颜色与格式；
     * - `&f` = 显式白色，必须写出来；`&r` 解码后同样归为显式白色；
     * - 颜色变化或需要摘除格式时重新写颜色码（颜色码本身会重置格式），
     *   避免格式泄漏到后续文字。
     *
     * [shellColor] / [shellFormats] 是 display-format 在 `%s` 前的有效外壳样式。
     * 不传时按“无外壳”处理，兼容纯文本测试与调用。
     */
    fun render(
        chars: List<StyledChar>,
        allowColor: Boolean,
        shellColor: String? = null,
        shellFormats: Set<Char> = emptySet(),
    ): String? {
        if (!allowColor) return chars.joinToString(separator = "") { it.value }
        val shell = shellColor?.let { normalizeColor(it) ?: return null }
        val out = StringBuilder()
        // 没有任何显式颜色时仍未改动“继承外壳”的写法，不写冗余颜色码；
        // 一旦有字符带显式颜色（例如局部上色后的草稿），则从第一个字符起显式写出外壳色，
        // 便于保存后的 raw 自身也能区分“继承”与“显式”。
        val emitsShellColor = shell != null && chars.any { it.color != null }
        var currentColor: String? = if (emitsShellColor) null else shell
        var currentFormats: Set<Char> = if (emitsShellColor || shell == null) emptySet() else shellFormats
        for (char in chars) {
            val explicitColor = char.color?.let { normalizeColor(it) ?: return null }
            val desiredColor = explicitColor ?: shell
            val desiredFormats = if (explicitColor == null) shellFormats + char.formats else char.formats
            if (desiredColor != currentColor) {
                if (!emitColor(out, desiredColor, currentColor, currentFormats)) return null
                currentColor = desiredColor
                currentFormats = emptySet()
            } else if (currentFormats.any { it !in desiredFormats }) {
                // 当前格式比目标多（例如同色段里摘掉外壳粗体）时必须重发颜色码来重置，
                // 否则多出来的格式会继续泄漏到后面的字符上。
                if (!emitColor(out, desiredColor, currentColor, currentFormats)) return null
                currentFormats = emptySet()
            }
            for (code in FORMAT_ORDER) {
                if (code in desiredFormats && code !in currentFormats) {
                    out.append('&').append(code)
                    currentFormats = currentFormats + code
                }
            }
            out.append(char.value)
        }
        return out.toString()
    }

    private fun emitColor(
        out: StringBuilder,
        target: String?,
        currentColor: String?,
        currentFormats: Set<Char>,
    ): Boolean {
        if (target == null) {
            if (currentColor == null && currentFormats.isEmpty()) return true
            out.append("&r")
            return true
        }
        if (target.startsWith("&#")) {
            val hex = target.substring(2)
            if (hex.length != 6 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
            out.append("&#").append(hex.uppercase(Locale.ROOT))
            return true
        }
        if (target.length != 2 || target[0] != '&') return false
        val code = target[1].lowercaseChar()
        if (code !in '0'..'9' && code !in 'a'..'f') return false
        out.append('&').append(code)
        return true
    }

    /**
     * 只改范围内每个字符的颜色，格式与范围外字符保持原样。
     *
     * `fallbackColor` / `fallbackFormats` 是外壳样式：样式编辑会把“继承外壳”的字符
     * 先落成显式外壳样式，避免选中部分字符上色后，范围外字符改变实际颜色或格式。
     */
    fun withColor(
        chars: List<StyledChar>,
        range: IntRange,
        color: String,
        fallbackColor: String? = null,
        fallbackFormats: Set<Char> = emptySet(),
    ): List<StyledChar>? {
        val normalized = normalizeColor(color) ?: return null
        val base = materialize(chars, fallbackColor, fallbackFormats)
        return base.mapIndexed { index, char ->
            if (index in range) char.copy(color = normalized) else char
        }
    }

    /** 范围内颜色逐字符插值，端点分别精确等于首尾色。 */
    fun withGradient(
        chars: List<StyledChar>,
        range: IntRange,
        from: String,
        to: String,
        fallbackColor: String? = null,
        fallbackFormats: Set<Char> = emptySet(),
    ): List<StyledChar>? {
        val indices = range.filter { it in chars.indices }
        if (indices.isEmpty()) return null
        val fromRgb = rgbOf(from) ?: return null
        val toRgb = rgbOf(to) ?: return null
        val result = materialize(chars, fallbackColor, fallbackFormats).toMutableList()
        for ((position, index) in indices.withIndex()) {
            result[index] = result[index].copy(
                color = "&#" + gradientHex(fromRgb, toRgb, position, indices.size),
            )
        }
        return result
    }

    /** 格式开关：范围内全部有该格式则全部去掉，否则全部加上；颜色与其他格式不动。 */
    fun toggleFormat(
        chars: List<StyledChar>,
        range: IntRange,
        code: Char,
        fallbackColor: String? = null,
        fallbackFormats: Set<Char> = emptySet(),
    ): List<StyledChar> {
        val base = materialize(chars, fallbackColor, fallbackFormats)
        val selected = base.filterIndexed { index, _ -> index in range }
        val allHave = selected.isNotEmpty() && selected.all { code in it.formats }
        return base.mapIndexed { index, char ->
            if (index !in range) char
            else char.copy(formats = if (allHave) char.formats - code else char.formats + code)
        }
    }

    /** 清除范围内颜色与格式，回到外壳默认样式；范围外不动。 */
    fun cleared(
        chars: List<StyledChar>,
        range: IntRange,
        fallbackColor: String = "&f",
        fallbackFormats: Set<Char> = emptySet(),
    ): List<StyledChar> {
        val base = materialize(chars, fallbackColor, fallbackFormats)
        return base.mapIndexed { index, char ->
            if (index in range) char.copy(color = fallbackColor, formats = fallbackFormats) else char
        }
    }

    private fun materialize(
        chars: List<StyledChar>,
        fallbackColor: String?,
        fallbackFormats: Set<Char>,
    ): List<StyledChar> =
        if (fallbackColor == null) chars
        else chars.map {
            if (it.color == null) it.copy(color = fallbackColor, formats = fallbackFormats + it.formats) else it
        }

    /** 范围内某格式的状态：ENABLED / DISABLED / MIXED；外壳格式参与“继承”字符的有效状态。 */
    fun formatState(
        chars: List<StyledChar>,
        range: IntRange,
        code: Char,
        fallbackColor: String? = null,
        fallbackFormats: Set<Char> = emptySet(),
    ): FormatState {
        val selected = chars.filterIndexed { index, _ -> index in range }
        if (selected.isEmpty()) return FormatState.DISABLED
        val count = selected.count { char ->
            val effective = if (char.color == null && fallbackColor != null) fallbackFormats + char.formats else char.formats
            code in effective
        }
        return when (count) {
            0 -> FormatState.DISABLED
            selected.size -> FormatState.ENABLED
            else -> FormatState.MIXED
        }
    }

    enum class FormatState { ENABLED, DISABLED, MIXED }

    /** 把选区裁剪到有效下标；空选区返回 null。 */
    fun normalizeRange(start: Int, end: Int, size: Int): IntRange? {
        if (size <= 0) return null
        val first = minOf(start, end).coerceIn(0, size - 1)
        val last = maxOf(start, end).coerceIn(0, size - 1)
        return first..last
    }

    /** `&a` / `&#RRGGBB` 规范化；非法返回 null。 */
    fun normalizeColor(color: String?): String? {
        if (color.isNullOrBlank()) return null
        val value = color.trim()
        if (value.length == 2 && (value[0] == '&' || value[0] == '§')) {
            val code = value[1].lowercaseChar()
            if (code in '0'..'9' || code in 'a'..'f') return "&$code"
        }
        if (value.length == 8 && value.startsWith("&#")) {
            val hex = value.substring(2)
            if (hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                return "&#${hex.uppercase(Locale.ROOT)}"
            }
        }
        return null
    }

    /** 颜色（传统色、`&#RRGGBB` 或裸六位 HEX）对应的六位大写 RGB；非法返回 null。 */
    fun rgbOf(color: String): String? {
        val bare = color.trim().uppercase(Locale.ROOT)
        if (bare.length == 6 && bare.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return bare
        }
        val normalized = normalizeColor(color) ?: return null
        if (normalized.startsWith("&#")) return normalized.substring(2)
        return LEGACY_RGB[normalized[1]]
    }

    /**
     * 双端线性插值：`round(start + (end - start) * i / (n - 1))`。
     *
     * n<=1 取起点；i=0 / i=n-1 分别精确等于首尾色。
     */
    fun gradientHex(from: String, to: String, index: Int, count: Int): String {
        val first = hexChannels(from) ?: return from.uppercase(Locale.ROOT)
        val second = hexChannels(to) ?: return to.uppercase(Locale.ROOT)
        if (count <= 1) return from.uppercase(Locale.ROOT)
        val step = index.coerceIn(0, count - 1)
        val ratio = step.toDouble() / (count - 1)
        return buildString(6) {
            for (channel in 0..2) {
                val value = (first[channel] + (second[channel] - first[channel]) * ratio).roundToInt()
                append(value.coerceIn(0, 255).toString(16).uppercase(Locale.ROOT).padStart(2, '0'))
            }
        }
    }

    private fun hexChannels(hex: String): IntArray? {
        val value = hex.trim().uppercase(Locale.ROOT)
        if (value.length != 6 || !value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return intArrayOf(
            value.substring(0, 2).toInt(16),
            value.substring(2, 4).toInt(16),
            value.substring(4, 6).toInt(16),
        )
    }
}

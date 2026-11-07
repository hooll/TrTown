package cn.trtown.util

import java.util.Locale

/**
 * 前缀词法结果中的错误类型。
 *
 * 只描述"文本本身不合法"，不包含配置开关与角色限制；这些由服务层判断。
 */
enum class PrefixIssue {
    /** 颜色指令残缺、HEX 长度/字符非法、`&x` 多段 HEX 不完整。 */
    INVALID_COLOR,

    /** 孤立代理项，无法构成合法 Unicode 字符。 */
    INVALID_CHARACTER,

    /** 原始输入超过 4096 个 UTF-16 单元。 */
    RAW_TOO_LONG,
}

/** 词法单元；每个 [Text] 只承载一个 Unicode code point。 */
sealed interface PrefixToken {
    data class LegacyColor(val code: Char) : PrefixToken
    data class HexColor(val rgb: String) : PrefixToken
    data class Format(val code: Char) : PrefixToken
    data class Text(val value: String) : PrefixToken
}

/** 词法结果；[issue] 非空表示原文本身不可解析。 */
data class PrefixLexResult(val tokens: List<PrefixToken>, val issue: PrefixIssue? = null)

/**
 * 前缀文本编解码（纯函数，不依赖 Bukkit / 配置）。
 *
 * 统一支持：
 * - `&0..f` 传统 16 色；
 * - `&k..o` 格式码与 `&r` 重置；
 * - `&#RRGGBB` 与 `&x&R&R&G&G&B&B` 两种 RGB 写法；
 * - 与 `&` 等价的 `§` 输入。
 *
 * 输出口径固定：传统 `&` 码、RGB 统一为 `&#RRGGBB`（大写）。
 * 不是颜色指令的 `&`（例如 `A&B`、`&z`）按普通文字保留；
 * 只有**残缺的颜色指令**（结尾孤立 `&`、`&#12`、`&x&F&F` 等）才报错，
 * 且这些碎片不计入可见字数。
 */
object PrefixCodec {

    /** raw 输入的硬上限（UTF-16 单元）；在解析前先验，避免超长串拖垮词法。 */
    const val MAX_RAW_UNITS: Int = 4096

    private const val FORMATS = "klmnor"

    /** 解析原文。 */
    fun lex(raw: String): PrefixLexResult {
        if (raw.length > MAX_RAW_UNITS) return PrefixLexResult(emptyList(), PrefixIssue.RAW_TOO_LONG)
        val tokens = ArrayList<PrefixToken>(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '&' || c == '§') {
                if (i + 1 >= raw.length) {
                    return PrefixLexResult(tokens, PrefixIssue.INVALID_COLOR)
                }
                val next = raw[i + 1]
                when {
                    next == '#' -> {
                        if (i + 8 > raw.length) {
                            return PrefixLexResult(tokens, PrefixIssue.INVALID_COLOR)
                        }
                        val hex = raw.substring(i + 2, i + 8)
                        if (!hex.all { it.isHexDigit() }) {
                            return PrefixLexResult(tokens, PrefixIssue.INVALID_COLOR)
                        }
                        tokens += PrefixToken.HexColor(hex.uppercase(Locale.ROOT))
                        i += 8
                    }

                    next == 'x' || next == 'X' -> {
                        val parsed = parseTraditionalHex(raw, i)
                            ?: return PrefixLexResult(tokens, PrefixIssue.INVALID_COLOR)
                        tokens += parsed.first
                        i += parsed.second
                    }

                    next.isHexDigit() -> {
                        tokens += PrefixToken.LegacyColor(next.lowercaseChar())
                        i += 2
                    }

                    next.lowercaseChar() in FORMATS -> {
                        tokens += PrefixToken.Format(next.lowercaseChar())
                        i += 2
                    }

                    else -> {
                        // 普通文字里的 &（例如 A&B、&z）：只把 & 当文字，后续字符继续正常解析
                        tokens += PrefixToken.Text(c.toString())
                        i += 1
                    }
                }
            } else {
                val cp = raw.codePointAt(i)
                if (cp in 0xD800..0xDFFF) {
                    return PrefixLexResult(tokens, PrefixIssue.INVALID_CHARACTER)
                }
                tokens += PrefixToken.Text(String(Character.toChars(cp)))
                i += Character.charCount(cp)
            }
        }
        return PrefixLexResult(tokens)
    }

    /**
     * 解析 `&x&R&R&G&G&B&B`（`§x§R...` 同样接受）。
     *
     * 返回 token 与消耗的 UTF-16 单元数；任何一段不完整都返回 null。
     */
    private fun parseTraditionalHex(raw: String, start: Int): Pair<PrefixToken, Int>? {
        var cursor = start + 2
        val rgb = StringBuilder(6)
        repeat(6) {
            if (cursor + 1 >= raw.length) return null
            val marker = raw[cursor]
            if (marker != '&' && marker != '§') return null
            val digit = raw[cursor + 1]
            if (!digit.isHexDigit()) return null
            rgb.append(digit)
            cursor += 2
        }
        return PrefixToken.HexColor(rgb.toString().uppercase(Locale.ROOT)) to (cursor - start)
    }

    /** 规范存储串：传统 `&` 码 + 大写 `&#RRGGBB`。 */
    fun sourceOf(tokens: List<PrefixToken>): String = buildString {
        for (token in tokens) {
            when (token) {
                is PrefixToken.LegacyColor -> append('&').append(token.code)
                is PrefixToken.HexColor -> append("&#").append(token.rgb)
                is PrefixToken.Format -> append('&').append(token.code)
                is PrefixToken.Text -> append(token.value)
            }
        }
    }

    /** 可见纯文本（颜色/格式 token 全部剔除）。 */
    fun plainOf(tokens: List<PrefixToken>): String = buildString {
        for (token in tokens) {
            if (token is PrefixToken.Text) append(token.value)
        }
    }

    /** 可见字数，按 Unicode code point 计。 */
    fun visibleLength(tokens: List<PrefixToken>): Int = tokens.count { it is PrefixToken.Text }

    /**
     * 渲染成 Bukkit 可直接显示的传统 `§` 串。
     *
     * RGB 展开为 `§x§R§R§G§G§B§B`，避免依赖不同版本 `.colored()` 对 `&#` 的解析差异。
     */
    fun sectionOf(tokens: List<PrefixToken>): String = buildString {
        for (token in tokens) {
            when (token) {
                is PrefixToken.LegacyColor -> append('§').append(token.code)
                is PrefixToken.HexColor -> {
                    append("§x")
                    for (ch in token.rgb) append('§').append(ch)
                }

                is PrefixToken.Format -> append('§').append(token.code)
                is PrefixToken.Text -> append(token.value)
            }
        }
    }

    /** 把规范串渲染成 `§` 串；解析失败时原样返回（调用方用于预览，不用于落库）。 */
    fun toSection(raw: String): String {
        val lex = lex(raw)
        return if (lex.issue == null) sectionOf(lex.tokens) else raw
    }

    /** 去掉全部受支持的颜色/格式 token，只保留文字。 */
    fun stripColors(tokens: List<PrefixToken>): List<PrefixToken> = tokens.filterIsInstance<PrefixToken.Text>()

    /** 去掉全部受支持的颜色/格式 token；原文本身非法时返回 null。 */
    fun stripColors(raw: String): String? {
        val lex = lex(raw)
        if (lex.issue != null) return null
        return plainOf(stripColors(lex.tokens))
    }

    /** 六位 HEX 规范化：允许一个前导 `#`，输出大写；非法返回 null。 */
    fun normalizeHex(value: String?): String? {
        if (value.isNullOrBlank()) return null
        var text = value.trim()
        if (text.startsWith("#")) text = text.substring(1)
        if (text.length != 6 || !text.all { it.isHexDigit() }) return null
        return text.uppercase(Locale.ROOT)
    }

    /** 检查纯文本中的禁用字符；`%` 无条件拒绝（防 PAPI 递归）。 */
    fun deniedChar(plain: String, denyChars: List<String>): String? {
        if (plain.contains('%')) return "%"
        for (deny in denyChars) {
            if (deny.isNotEmpty() && plain.contains(deny)) return deny
        }
        return null
    }

    internal fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}

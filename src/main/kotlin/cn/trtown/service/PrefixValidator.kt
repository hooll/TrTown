package cn.trtown.service

import cn.trtown.util.PrefixCodec
import cn.trtown.util.PrefixIssue
import cn.trtown.util.PrefixToken

/**
 * 前缀校验纯函数（不依赖数据库 / Bukkit）。
 *
 * 命令、GUI 铁砧、样式页、渐变页都必须经过这里；`PrefixService` 只负责角色、目标镇与提交。
 */
object PrefixValidator {

    /**
     * @param raw 原始输入，可含 `&` / `§` 颜色与格式码
     * @param allowEmpty true 时空白/仅颜色返回 null（= 回退默认）；false 时抛业务异常
     * @return 规范 source（`&` 码 + 大写 `&#RRGGBB`）；空白返回 null
     */
    fun validate(raw: String, rules: PrefixRules, allowEmpty: Boolean = true): String? {
        if (raw.length > PrefixRules.MAX_RAW_UNITS) {
            throw TownException(TownError.PREFIX_RAW_TOO_LONG)
        }
        val text = raw.trim()
        if (text.isEmpty()) {
            if (allowEmpty) return null
            throw TownException(TownError.PREFIX_EMPTY)
        }
        val lex = PrefixCodec.lex(text)
        if (lex.issue == PrefixIssue.RAW_TOO_LONG) throw TownException(TownError.PREFIX_RAW_TOO_LONG)
        if (lex.issue != null) throw TownException(TownError.PREFIX_INVALID_COLOR)

        // allow-color=false：剥掉全部受支持的颜色/格式 token，再验正文（不能只把按钮置灰）
        val tokens = if (rules.allowColor) lex.tokens else PrefixCodec.stripColors(lex.tokens)
        val plain = PrefixCodec.plainOf(tokens)
        val denied = PrefixCodec.deniedChar(plain, rules.denyChars)
        if (denied != null) throw TownException(TownError.PREFIX_DENY_CHAR, denied)

        val visible = PrefixCodec.visibleLength(tokens)
        if (visible == 0) {
            if (allowEmpty) return null
            throw TownException(TownError.PREFIX_EMPTY)
        }
        if (visible > rules.maxLength) {
            throw TownException(TownError.PREFIX_TOO_LONG, "$visible > ${rules.maxLength}")
        }
        return PrefixCodec.sourceOf(tokens)
    }

    /** 外壳颜色：display-format 中 `%s` 之前最后一个颜色码；没有则默认白色。 */
    fun shellColor(rules: PrefixRules): String {
        val index = rules.displayFormat.indexOf("%s")
        val before = if (index >= 0) rules.displayFormat.substring(0, index) else rules.displayFormat
        val lex = PrefixCodec.lex(before)
        var color = "&f"
        for (token in lex.tokens) {
            when (token) {
                is PrefixToken.LegacyColor -> color = "&${token.code}"
                is PrefixToken.HexColor -> color = "&#${token.rgb}"
                is PrefixToken.Format -> if (token.code == 'r') color = "&f"
                is PrefixToken.Text -> Unit
            }
        }
        return color
    }

    /** 预览：raw 为空时按 `fallback-to-name` 回退镇名；显示规则与真实 render 一致。 */
    fun previewOf(raw: String?, townName: String, rules: PrefixRules): String {
        if (!rules.enabled) return ""
        val value = if (raw.isNullOrBlank()) {
            if (rules.fallbackToName) townName else ""
        } else {
            raw
        }
        // 关闭颜色后，已保存/草稿/reset 的正文都必须与服务 rawOf 一样去色；
        // 外壳 display-format 自己的颜色保留。这里只影响预览，不改数据库原值。
        val shown = if (!rules.allowColor && value.isNotEmpty()) {
            PrefixCodec.stripColors(value) ?: value
        } else {
            value
        }
        if (shown.isEmpty()) return ""
        return rules.displayFormat.replace("%s", shown)
    }
}

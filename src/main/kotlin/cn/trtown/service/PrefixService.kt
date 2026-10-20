package cn.trtown.service

import cn.trtown.Settings
import cn.trtown.model.Town
import java.util.UUID

/**
 * 前缀相关的纯数据规则（从 `config.yml` 抽出，便于单测，不依赖 TabooLib 配置对象）。
 */
data class PrefixRules(
    val enabled: Boolean,
    val maxLength: Int,
    val allowColor: Boolean,
    val displayFormat: String,
    val fallbackToName: Boolean,
    val denyChars: List<String>,
) {
    companion object {
        fun fromSettings(): PrefixRules = PrefixRules(
            enabled = Settings.prefixEnabled,
            maxLength = Settings.prefixMaxLength,
            allowColor = Settings.prefixAllowColor,
            displayFormat = Settings.prefixDisplayFormat,
            fallbackToName = Settings.prefixFallbackToName,
            denyChars = Settings.prefixDenyChars,
        )
    }
}

/**
 * 小镇前缀：清洗、设置、渲染（PLAN §5.12）。
 *
 * 纯 Kotlin；渲染结果直接交给 PAPI 变量（`prefix.display-format` 套壳）。
 */
class PrefixService(
    private val repos: Repos,
    private val rules: PrefixRules = PrefixRules.fromSettings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val colorPattern = Regex("[&§][0-9a-fk-orA-FK-OR]")

    /** 设置前缀；空串 = reset（返回 null） */
    fun set(actor: UUID, raw: String): String? {
        if (!rules.enabled) throw TownException(TownError.PREFIX_DISABLED)
        val member = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        if (!member.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
        val town = repos.towns.findById(member.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)

        val cleaned = clean(raw)
        repos.towns.updatePrefix(town.id, cleaned)
        repos.audit.append(actor, town.id, "setprefix", cleaned ?: "<reset>", clock())
        // M-15：前缀改了必须立刻失效，否则要到重登才生效
        repos.members.listByTown(town.id).forEach { CacheHooks.invalidate(it.uuid) }
        return cleaned
    }

    /** 清洗规则：去空白 → § 统一成 & → 去颜色（可选）→ 拒绝字符 → 可见长度 */
    fun clean(raw: String): String? {
        var text = raw.trim().replace('§', '&')
        if (text.isEmpty()) return null
        if (!rules.allowColor) text = text.replace(colorPattern, "")
        if (rules.denyChars.any { text.contains(it) }) {
            throw TownException(TownError.PREFIX_DENY_CHAR, rules.denyChars.joinToString(" "))
        }
        val visible = colorPattern.replace(text, "").length
        if (visible == 0) return null
        if (visible > rules.maxLength) {
            throw TownException(TownError.PREFIX_TOO_LONG, "$visible > ${rules.maxLength}")
        }
        return text
    }

    /** 前缀原文（未套格式）：自定义前缀优先，否则回落到镇名 */
    fun rawOf(town: Town): String {
        val custom = town.prefix
        if (!custom.isNullOrBlank()) return custom
        return if (rules.fallbackToName) town.name else ""
    }

    /** 套上 `prefix.display-format`，返回带 `&` 颜色码的字符串 */
    fun render(town: Town): String {
        if (!rules.enabled) return ""
        val raw = rawOf(town)
        if (raw.isEmpty()) return ""
        return rules.displayFormat.replace("%s", raw)
    }

    /** 给 PAPI 用：先查镇，再渲染 */
    fun renderFor(uuid: UUID): String {
        val member = repos.members.findByUuid(uuid) ?: return ""
        val town = repos.towns.findById(member.townId) ?: return ""
        return render(town)
    }
}

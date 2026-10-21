package cn.trtown.dominion

import cn.trtown.Settings
import cn.trtown.model.TownRole

/** 配置致命错误：启动时直接拒绝启用（PLAN §5.7） */
class ConfigException(message: String) : RuntimeException(message)

/**
 * `dominion.roles.*` 与 `permissions.editable-flags` 的启动校验（M-3）。
 *
 * **唯一判据**：一个 flag 名合法 ⟺ `Flags.getPreFlag(name)` 认它（M-26）。
 * 启动校验和运行期 `DominionApiGateway` 用的是同一个查表，所以两者不可能再分叉 ——
 * 上一版把 `Flags` 的静态常量取并集，结果 `container` 这种"有常量、没注册"的名字
 * 启动时放行、绑定时才炸（真服实测：`/dominion group_set_flag ... container true`
 * → `[Dominion] Unknown privilege flag: container.`）。
 *
 * 校验规则：
 * 1. 任何出现 flag 名的地方（roles / editable-flags / aliases 的目标值）都必须能解析，否则**启动失败**；
 * 2. 三档必须严格递进：低阶 true 的 flag，高阶不得显式 false；
 * 3. `editable-flags` 里合法但 roles 从未出现的项丢弃并 WARN。
 */
object FlagConfig {

    data class Validated(
        /** 生效值：每个阶级 = 从 MEMBER 到该阶级的并集 */
        val effective: Map<TownRole, Map<String, Boolean>>,
        val editable: Set<String>,
        val warnings: List<String>,
    )

    private val order = listOf(TownRole.MEMBER, TownRole.MANAGER, TownRole.MAYOR)

    fun validate(): Validated = validate(
        roleFlags = order.associateWith { Settings.roleFlags(it) },
        editableFlags = Settings.editableFlags,
        aliases = Settings.flagAliases,
        knownFlagNames = registeredFlagNames(),
    )

    /** Dominion 真正注册的 privilege flag 名（M-26 的唯一来源，见 [FlagNames]） */
    private fun registeredFlagNames(): Set<String> {
        val names = FlagNames.registered
        // 空表说明 Dominion 还没填注册表 —— 此时放行等于全部放行，必须直接失败
        if (names.isEmpty()) {
            throw ConfigException("读不到 Dominion 的 privilege flag 注册表（Flags.getAllPriFlags() 为空）")
        }
        return names
    }

    /**
     * 纯函数版：不读配置、不碰 Dominion，供单测注入 [knownFlagNames]（M-26 的回归闸门）。
     */
    fun validate(
        roleFlags: Map<TownRole, Map<String, Boolean>>,
        editableFlags: List<String>,
        aliases: Map<String, String>,
        knownFlagNames: Set<String>,
    ): Validated {
        val warnings = mutableListOf<String>()
        val illegal = LinkedHashSet<String>()

        // 1. roles：逐级解析，非法项收集后一次性报错（比"报第一个就停"好排查）
        val normalized = LinkedHashMap<TownRole, Map<String, Boolean>>()
        for (role in order) {
            val map = LinkedHashMap<String, Boolean>()
            roleFlags[role].orEmpty().forEach { (key, value) ->
                if (key !in knownFlagNames) illegal += "dominion.roles.${role.name}.$key" else map[key] = value
            }
            normalized[role] = map
        }

        // 2. editable-flags / aliases 的目标值：同一个判据
        editableFlags.forEach { raw ->
            if (raw !in knownFlagNames) illegal += "permissions.editable-flags.$raw"
        }
        aliases.forEach { (display, target) ->
            val name = target.lowercase()
            if (name !in knownFlagNames) illegal += "permissions.aliases.$display → $target"
        }
        if (illegal.isNotEmpty()) {
            throw ConfigException(
                "以下配置项不是 Dominion 4.9.5 注册的 privilege flag（启动即失败，避免「配置看着没问题、绑定时才炸」）：" +
                    illegal.joinToString("、") +
                    "；合法项共 ${knownFlagNames.size} 个，如 " + knownFlagNames.sorted().take(6).joinToString("/"),
            )
        }

        // 3. 递进校验
        for (i in 0 until order.size - 1) {
            val lower = order[i]
            val higher = order[i + 1]
            normalized.getValue(lower).forEach { (flag, value) ->
                if (value && normalized.getValue(higher)[flag] == false) {
                    throw ConfigException("$lower 开启了 $flag，但 $higher 显式关闭了它（三档必须递进）")
                }
            }
        }

        // 4. 生效值 = 逐级并集
        val effective = LinkedHashMap<TownRole, Map<String, Boolean>>()
        val acc = LinkedHashMap<String, Boolean>()
        for (role in order) {
            normalized.getValue(role).forEach { (k, v) -> acc[k] = v }
            effective[role] = LinkedHashMap(acc)
        }

        // 5. editable-flags ⊆ roles 里出现过的 flag（合法但没用到 → 丢弃并 WARN）
        val editable = LinkedHashSet<String>()
        editableFlags.forEach { raw ->
            if (raw in acc) editable += raw
            else warnings += "permissions.editable-flags 里的 $raw 未出现在 dominion.roles.*，已丢弃"
        }

        return Validated(effective, editable, warnings)
    }
}

/** 启动时校验通过后的全局只读配置 */
object DominionConfig {

    @Volatile
    private var validated: FlagConfig.Validated? = null

    fun install(value: FlagConfig.Validated) {
        validated = value
    }

    val current: FlagConfig.Validated
        get() = validated ?: error("Dominion 配置尚未校验")

    /** 某阶级的生效 flag（已按递进展开） */
    fun flagsOf(role: TownRole): Map<String, Boolean> = current.effective[role] ?: emptyMap()
}

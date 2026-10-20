package cn.trtown.dominion

import cn.lunadeer.dominion.api.dtos.flag.Flags
import cn.lunadeer.dominion.api.dtos.flag.PriFlag
import cn.trtown.Settings
import cn.trtown.model.TownRole

/** 配置致命错误：启动时直接拒绝启用（PLAN §5.7） */
class ConfigException(message: String) : RuntimeException(message)

/**
 * `dominion.roles.*` 与 `permissions.editable-flags` 的启动校验（M-3）。
 *
 * 三件事：
 * 1. 每个 key 必须能被 `Flags.getPreFlag` 解析（大小写不敏感兜底，命中后归一化并 WARN）；
 * 2. 三档必须严格递进：低阶 true 的 flag，高阶不得显式 false；
 * 3. `editable-flags` 必须是 roles 里出现过的 flag 的子集，多出来的丢弃并 WARN。
 */
object FlagConfig {

    data class Validated(
        /** 生效值：每个阶级 = 从 MEMBER 到该阶级的并集 */
        val effective: Map<TownRole, Map<String, Boolean>>,
        val editable: Set<String>,
        val warnings: List<String>,
    )

    private val order = listOf(TownRole.MEMBER, TownRole.MANAGER, TownRole.MAYOR)

    fun validate(): Validated {
        val warnings = mutableListOf<String>()

        // 1. 解析 + 归一化
        val normalized = LinkedHashMap<TownRole, Map<String, Boolean>>()
        for (role in order) {
            val map = LinkedHashMap<String, Boolean>()
            Settings.roleFlags(role).forEach { (key, value) ->
                val flag = resolve(key)
                    ?: throw ConfigException("dominion.roles.${role.name}.$key 不是 Dominion 的合法 privilege flag")
                if (flag.flagName != key) {
                    warnings += "dominion.roles.${role.name}.$key 已归一化为 ${flag.flagName}"
                }
                map[flag.flagName] = value
            }
            normalized[role] = map
        }

        // 2. 递进校验
        for (i in 0 until order.size - 1) {
            val lower = order[i]
            val higher = order[i + 1]
            normalized.getValue(lower).forEach { (flag, value) ->
                if (value && normalized.getValue(higher)[flag] == false) {
                    throw ConfigException("$lower 开启了 $flag，但 $higher 显式关闭了它（三档必须递进）")
                }
            }
        }

        // 3. 生效值 = 逐级并集
        val effective = LinkedHashMap<TownRole, Map<String, Boolean>>()
        val acc = LinkedHashMap<String, Boolean>()
        for (role in order) {
            normalized.getValue(role).forEach { (k, v) -> acc[k] = v }
            effective[role] = LinkedHashMap(acc)
        }

        // 4. editable-flags ⊆ roles 里出现过的 flag
        val editable = LinkedHashSet<String>()
        Settings.editableFlags.forEach { raw ->
            val flag = resolve(raw)
            when {
                flag == null -> warnings += "permissions.editable-flags 里的 $raw 不是合法 flag，已丢弃"
                flag.flagName !in acc -> warnings += "permissions.editable-flags 里的 $raw 未出现在 dominion.roles.*，已丢弃"
                else -> editable += flag.flagName
            }
        }

        return Validated(effective, editable, warnings)
    }

    /** 先按 flag_name 精确解析，失败再做一次大小写不敏感兜底 */
    private fun resolve(key: String): PriFlag? {
        Flags.getPreFlag(key)?.let { return it }
        return knownFlags.firstOrNull { it.flagName == key }
            ?: knownFlags.firstOrNull { it.flagName.equals(key, ignoreCase = true) }
    }

    /**
     * Dominion 的 `pri_flags` 列表在插件 onEnable 阶段可能还没填充（实测：`getAllPriFlags()`
     * 在此时拿不到 `container`），因此这里把 API 列表与 `Flags` 类的静态常量取并集。
     * 静态常量就是 Dominion 注册的那批对象，不依赖初始化时序。
     */
    private val knownFlags: List<PriFlag> by lazy {
        val map = LinkedHashMap<String, PriFlag>()
        runCatching { Flags.getAllPriFlags() }.getOrDefault(emptyList())
            .forEach { map[it.flagName] = it }
        reflectFlags().forEach { map.putIfAbsent(it.flagName, it) }
        map.values.toList()
    }

    private fun reflectFlags(): List<PriFlag> = Flags::class.java.fields
        .filter { PriFlag::class.java.isAssignableFrom(it.type) }
        .mapNotNull { field -> runCatching { field.get(null) as? PriFlag }.getOrNull() }
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

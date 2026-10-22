package cn.trtown.dominion

import cn.trtown.Settings
import cn.trtown.model.TownRole

/** 配置致命错误：启动时直接拒绝启用（PLAN §5.7） */
class ConfigException(message: String) : RuntimeException(message)

/**
 * `dominion.roles.*.presets` 的启动校验（M-3 / M-26 / v1.25）。
 *
 * 新模型（PLAN §5.16.1）不再有"阶级组"，角色的 flag 集来自 Dominion 自己的
 * `plugins/Dominion/flags.yml` 预设组：`groups.privilege.<preset>.flags`。
 *
 * **唯一判据不变**：一个 flag 名合法 ⟺ `Flags.getPreFlag(name)` 认它（M-26）。
 * 启动校验和运行期 `DominionApiGateway` 用的是同一个查表，所以两者不可能再分叉。
 *
 * 校验规则：
 * 1. `flags.yml` 读不到 → 启动失败，**不回退内置常量**；
 * 2. preset 名不存在、或非空 presets 展开出 0 项 → 启动失败；
 * 3. 展开出的 flag 名必须全部能在 Dominion 注册表里找到；
 * 4. 生效值按 MEMBER → MANAGER → MAYOR 逐级并集，保证高阶是低阶的超集。
 */
object FlagConfig {

    data class Validated(
        /** 生效值：每个阶级 = 从 MEMBER 到该阶级的并集 */
        val effective: Map<TownRole, Map<String, Boolean>>,
        /** 所有角色会触碰的 flag 并集（降级时要显式设回 false 的就是这批） */
        val managed: Set<String>,
        /** 原始 presets，用于启动日志 */
        val presets: Map<TownRole, List<String>>,
        val warnings: List<String>,
    )

    private val order = listOf(TownRole.MEMBER, TownRole.MANAGER, TownRole.MAYOR)

    fun validate(): Validated = validate(
        rolePresets = order.associateWith { Settings.rolePresets(it) },
        presetFlags = DominionPresets.load(),
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
     * 纯函数版：不读配置、不碰 Dominion，供单测注入 [presetFlags] 与 [knownFlagNames]。
     */
    fun validate(
        rolePresets: Map<TownRole, List<String>>,
        presetFlags: Map<String, List<String>>,
        knownFlagNames: Set<String>,
    ): Validated {
        val illegal = LinkedHashSet<String>()
        val normalized = LinkedHashMap<TownRole, Map<String, Boolean>>()

        for (role in order) {
            val presets = rolePresets[role].orEmpty()
            val flags = LinkedHashSet<String>()
            for (preset in presets) {
                // M-44（AUDIT §24.10）：`presets` 除了预设组名，也接受**单个 flag 名**。
                // Dominion 4.9.5 的 `groups.privilege` 里没有 `teleport` 组 —— `teleport`
                // 只是 `movement` 组里的一项（movement = move/teleport/fly/riding/ender_pearl）。
                // 给 MEMBER 整组 movement 会顺带放开 fly 等，超出"回自己家的门票"这一最小集合，
                // 所以这里允许直接写 flag 名（先按组名查，组名不存在再按 flag 名查，组名优先）。
                val list = presetFlags[preset]
                    ?: if (preset.trim().lowercase() in knownFlagNames) listOf(preset)
                    else throw ConfigException(
                        "dominion.roles.${role.name}.presets 引用了不存在的预设组 `$preset`；" +
                            "flags.yml 里可用：${presetFlags.keys.sorted().joinToString("、")}" +
                            "（也可直接写单个 flag 名，如 teleport）",
                    )
                if (list.isEmpty()) {
                    throw ConfigException(
                        "预设组 `$preset` 展开出 0 个 flag（dominion.roles.${role.name}.presets 非空时不允许）",
                    )
                }
                list.forEach { raw ->
                    val flag = raw.trim().lowercase()
                    if (flag !in knownFlagNames) {
                        illegal += "dominion.roles.${role.name}.presets.$preset.$flag"
                    } else {
                        flags += flag
                    }
                }
            }
            normalized[role] = flags.associateWith { true }
        }

        if (illegal.isNotEmpty()) {
            throw ConfigException(
                "以下配置项不是 Dominion 4.9.5 注册的 privilege flag（启动即失败，避免「配置看着没问题、运行期才炸」）：" +
                    illegal.joinToString("、") +
                    "；合法项共 ${knownFlagNames.size} 个，如 " + knownFlagNames.sorted().take(6).joinToString("/"),
            )
        }

        // 生效值 = 逐级并集
        val effective = LinkedHashMap<TownRole, Map<String, Boolean>>()
        val acc = LinkedHashMap<String, Boolean>()
        for (role in order) {
            normalized.getValue(role).keys.forEach { acc[it] = true }
            effective[role] = LinkedHashMap(acc)
        }

        // 三档递进（并集写法下恒成立，保留断言防止将来改成"逐项 true/false 表"时静默退化）
        for (i in 0 until order.size - 1) {
            val lower = order[i]
            val higher = order[i + 1]
            val missing = effective.getValue(lower).keys - effective.getValue(higher).keys
            if (missing.isNotEmpty()) {
                throw ConfigException("三档必须递进：$lower 有 $missing，但 $higher 没有")
            }
        }

        return Validated(
            effective = effective,
            managed = acc.keys.toSet(),
            presets = rolePresets,
            warnings = emptyList(),
        )
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

    /** 所有角色会触碰的 flag（含"该角色应为 false"的项） */
    fun managedFlags(): Set<String> = current.managed

    /**
     * 某阶级的完整期望值：managed 里每个 flag 都有明确 true/false。
     *
     * 用途：降级时把高阶 flag 设回 false（PLAN §5.16.3）、`admin verify` 比对实况（§5.16.4）。
     */
    fun expectedFlags(role: TownRole): Map<String, Boolean> {
        val effective = flagsOf(role)
        return current.managed.associateWith { effective[it] ?: false }
    }
}

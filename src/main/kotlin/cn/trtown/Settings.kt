package cn.trtown

import cn.trtown.model.TownRole
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 配置注入（TabooLib `@Config`）。字段与 PLAN §5.7 一一对应。
 */
object Settings {

    @Config("config.yml")
    lateinit var conf: Configuration

    /** 配置是否已完成注入 */
    val injected: Boolean
        get() = this::conf.isInitialized

    /** 配置热重载（`admin reload`） */
    fun reload() {
        if (injected) conf.reload()
    }

    // ---- storage ----

    val storageType: String
        get() = conf.getString("storage.type", "sqlite")!!.lowercase()

    val mysqlHost: String
        get() = conf.getString("storage.mysql.host", "localhost")!!

    val mysqlPort: String
        get() = conf.get("storage.mysql.port")?.toString() ?: "3306"

    val mysqlDatabase: String
        get() = conf.getString("storage.mysql.database", "trtown")!!

    val mysqlUser: String
        get() = conf.getString("storage.mysql.user", "root")!!

    val mysqlPassword: String
        get() = conf.getString("storage.mysql.password", "")!!

    /** SQLite 连接池（PLAN §5.13：1–2，避免并发写锁库） */
    val sqlitePoolSize: Int
        get() = conf.getInt("storage.sqlite-pool-size", 2)

    val mysqlPoolSize: Int
        get() = conf.getInt("storage.mysql.pool-size", 10)

    // ---- town ----

    val maxMembers: Int
        get() = conf.getInt("town.max-members", 20)

    val maxTownsPerPlayer: Int
        get() = conf.getInt("town.max-towns-per-player", 1)

    val nameRegex: String
        get() = conf.getString("town.name-regex", "^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,16}$")!!

    val inviteExpireSeconds: Long
        get() = conf.getLong("town.invite-expire-seconds", 300)

    val maxPendingInvites: Int
        get() = conf.getInt("town.max-pending-invites", 50)

    val disbandConfirmSeconds: Long
        get() = conf.getLong("town.disband-confirm-seconds", 10)

    /** 已绑定主领地时，转让镇长需要二次确认的窗口 */
    val transferConfirmSeconds: Long
        get() = conf.getLong("town.transfer-confirm-seconds", 10)

    /** 转让后原镇长降为的阶级 */
    val transferDemoteTo: TownRole
        get() = runCatching { TownRole.valueOf(conf.getString("town.transfer-demote-to", "MEMBER")!!.uppercase()) }
            .getOrDefault(TownRole.MEMBER)

    // ---- prefix ----

    val prefixEnabled: Boolean
        get() = conf.getBoolean("prefix.enabled", true)

    val prefixMaxLength: Int
        get() = conf.getInt("prefix.max-length", 16)

    val prefixAllowColor: Boolean
        get() = conf.getBoolean("prefix.allow-color", true)

    val prefixDisplayFormat: String
        get() = conf.getString("prefix.display-format", "&8[&b%s&8] &r")!!

    val prefixFallbackToName: Boolean
        get() = conf.getBoolean("prefix.fallback-to-name", true)

    val prefixCooldownSeconds: Long
        get() = conf.getLong("prefix.cooldown-seconds", 0)

    val prefixDenyChars: List<String>
        get() = conf.getStringList("prefix.deny-chars")

    // ---- dominion ----

    /**
     * 某阶级在 Dominion 里的 flag 预设组名（v1.25，PLAN §5.16.1）。
     *
     * 预设组的实际 flag 列表在 `plugins/Dominion/flags.yml` 的
     * `groups.privilege.<名称>.flags`（4.9.5 实测结构），启动时由
     * [cn.trtown.dominion.FlagConfig] 展开。**空列表合法**（该角色不设任何 flag）。
     *
     * **MEMBER 默认带 `teleport`**（M-44 / AUDIT §24.10）：Dominion 的 `TeleportProvider`
     * 会校验成员自己的 `teleport` flag，不给就传不进去 —— 普通成员点不动 `/tt tp`
     * 等于该功能对绝大多数玩家不可用。`teleport` 只是"回自己家的门票"，
     * 不含建筑/容器/管理权限，与 m01715「只加成员、别给额外权限」不冲突。
     *
     * 兼容旧配置：`presets` 键不存在时用默认值（MEMBER `teleport`、MANAGER `administration`、
     * MAYOR 空），而不是把旧版逐 flag 表误读成"空预设"。
     */
    fun rolePresets(role: TownRole): List<String> {
        val defaults = when (role) {
            TownRole.MANAGER -> listOf("administration")
            TownRole.MEMBER -> listOf("teleport")
            else -> emptyList()
        }
        val section = conf.getConfigurationSection("dominion.roles.${role.name}") ?: return defaults
        if (!section.contains("presets")) return defaults
        return section.getStringList("presets").map { it.trim() }.filter { it.isNotEmpty() }
    }

    // ---- teleport（PLAN §5.17.3） ----

    /** 是否允许 `/tt tp <别人的镇>`；false 时带镇名一律拒绝 */
    val teleportAllowOthers: Boolean
        get() = conf.getBoolean("teleport.allow-others", true)

    // ---- gui（PLAN §5.18.7） ----

    /** 总开关：false → `/tt` 回退文字帮助，菜单入口全部关闭 */
    val guiEnabled: Boolean
        get() = conf.getBoolean("gui.enabled", true)

    /** 菜单刷新节流（占位；实现为"点击时重开"） */
    val guiRefreshSeconds: Int
        get() = conf.getInt("gui.refresh-seconds", 3)

    // ---- sync ----

    val syncPollTicks: Long
        get() = conf.getLong("sync.poll-ticks", 20)

    val syncMaxAttempts: Int
        get() = conf.getInt("sync.max-attempts", 5)

    val syncBackoffSeconds: List<Long>
        get() = conf.getLongList("sync.backoff-seconds").ifEmpty { listOf(5L, 30L, 120L, 600L) }
}

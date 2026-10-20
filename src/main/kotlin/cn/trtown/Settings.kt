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

    // ---- permissions（个人权限覆写） ----

    val allowOverride: Boolean
        get() = conf.getBoolean("permissions.allow-override", true)

    val editableFlags: List<String>
        get() = conf.getStringList("permissions.editable-flags").map { it.lowercase() }

    /** 中文别名 → 小写 flag_name */
    val flagAliases: Map<String, String>
        get() {
            val section = conf.getConfigurationSection("permissions.aliases") ?: return emptyMap()
            return section.getKeys(false).associate { it to section.getString(it)!!.lowercase() }
        }

    // ---- dominion ----

    val groupNameFormat: String
        get() = conf.getString("dominion.group-name-format", "TrTown_%s_%s")!!

    /**
     * 某阶级在 Dominion 里的 flag 表（key 一律小写 `flag_name`）。
     *
     * 语义是"叠加"：生效值 = 从 MEMBER 到该阶级的并集，由 S3 的配置校验负责。
     */
    fun roleFlags(role: TownRole): Map<String, Boolean> {
        val section = conf.getConfigurationSection("dominion.roles.${role.name}") ?: return emptyMap()
        return section.getKeys(false).associateWith { section.getBoolean(it) }
    }

    // ---- sync ----

    val syncPollTicks: Long
        get() = conf.getLong("sync.poll-ticks", 20)

    val syncMaxAttempts: Int
        get() = conf.getInt("sync.max-attempts", 5)

    val syncBackoffSeconds: List<Long>
        get() = conf.getLongList("sync.backoff-seconds").ifEmpty { listOf(5L, 30L, 120L, 600L) }
}

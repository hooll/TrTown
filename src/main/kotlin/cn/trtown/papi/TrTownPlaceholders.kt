package cn.trtown.papi

import cn.trtown.VERSION
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.platform.PlatformScheduler
import cn.trtown.service.PrefixService
import cn.trtown.service.Repos
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.OfflinePlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 内存缓存条目（PAPI 只读这里，禁止查库） */
data class CachedInfo(
    val prefix: String,
    val prefixRaw: String,
    val townName: String,
    val role: String,
    val roleDisplay: String,
    val loadedAt: Long,
)

/**
 * 前缀/角色缓存（PLAN §5.12）。
 *
 * PAPI 变量会被聊天 / TAB / 计分板高频调用，因此**只读内存**；
 * 缓存由入服、命令成功后异步刷新，缺失时返回空串并触发一次异步加载。
 */
object PlaceholderCache {

    private val map = ConcurrentHashMap<UUID, CachedInfo>()

    /** 负缓存：无镇玩家的下次允许查询时间（M-13，60 秒） */
    private val missingUntil = ConcurrentHashMap<UUID, Long>()

    private const val MISSING_TTL_MS = 60_000L

    fun get(uuid: UUID): CachedInfo? = map[uuid]

    /** 缓存未命中时是否允许触发一次异步刷新（负缓存期内返回 false） */
    fun shouldRefresh(uuid: UUID): Boolean {
        val until = missingUntil[uuid] ?: return true
        return System.currentTimeMillis() >= until
    }

    fun markMissing(uuid: UUID) {
        map.remove(uuid)
        missingUntil[uuid] = System.currentTimeMillis() + MISSING_TTL_MS
    }

    fun put(uuid: UUID, info: CachedInfo?) {
        if (info == null) {
            markMissing(uuid)
        } else {
            missingUntil.remove(uuid)
            map[uuid] = info
        }
    }

    fun invalidate(uuid: UUID) {
        map.remove(uuid)
        missingUntil.remove(uuid)
    }

    fun clear() {
        map.clear()
        missingUntil.clear()
    }

    /** 异步刷新（DB 操作必须在异步线程） */
    fun refreshAsync(uuid: UUID) {
        PlatformScheduler.runAsync(delayTicks = 1) {
            runCatching {
                val repos = Repos.fromStorage()
                val member = repos.members.findByUuid(uuid)
                if (member == null) {
                    markMissing(uuid)
                    return@runCatching
                }
                val town = repos.towns.findById(member.townId)
                if (town == null) {
                    markMissing(uuid)
                    return@runCatching
                }
                put(uuid, build(town, member.role, PrefixService(repos)))
            }
        }
    }

    private fun build(town: Town, role: TownRole, prefix: PrefixService) = CachedInfo(
        prefix = prefix.render(town),
        prefixRaw = prefix.rawOf(town),
        townName = town.name,
        role = role.name,
        roleDisplay = when (role) {
            TownRole.MAYOR -> "镇长"
            TownRole.MANAGER -> "管理"
            TownRole.MEMBER -> "成员"
        },
        loadedAt = System.currentTimeMillis(),
    )

    /**
     * M-15：把服务层的变更点接到缓存上。
     *
     * 只失效不刷新的话，玩家下一条聊天仍拿到空串（再下一次才有），
     * 因此这里对**在线玩家**做"失效 + 立刻异步重建"。
     */
    fun installHooks() {
        cn.trtown.service.CacheHooks.invalidator = { uuid ->
            invalidate(uuid)
            if (org.bukkit.Bukkit.getPlayer(uuid) != null) refreshAsync(uuid)
        }
    }
}

/** PlaceholderAPI 扩展：`%trtown_prefix%` 等 5 个变量 */
class TrTownPlaceholders : PlaceholderExpansion() {

    override fun getIdentifier(): String = "trtown"

    override fun getAuthor(): String = "TrTown"

    override fun getVersion(): String = VERSION

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String {
        val uuid = player?.uniqueId ?: return ""
        val info = PlaceholderCache.get(uuid)
        if (info == null) {
            // 负缓存期内不再触发查询，避免无镇玩家每条聊天打一次库（M-13）
            if (PlaceholderCache.shouldRefresh(uuid)) PlaceholderCache.refreshAsync(uuid)
            return ""
        }
        return when (params.lowercase()) {
            "prefix" -> info.prefix
            "prefix_raw" -> info.prefixRaw
            "name" -> info.townName
            "role" -> info.role
            "role_display" -> info.roleDisplay
            else -> ""
        }
    }
}

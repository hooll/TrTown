package cn.trtown.platform

import cn.trtown.command.SuggestCache
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.SyncExecutor
import cn.trtown.dominion.SyncSettings
import cn.trtown.model.SyncAction
import cn.trtown.papi.PlaceholderCache
import cn.trtown.service.InviteService
import cn.trtown.service.Repos
import cn.trtown.service.TownRules
import cn.trtown.service.TownService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.function.submitAsync
import taboolib.common.platform.function.warning

/**
 * 生命周期（PLAN §5.9 / W8）。
 *
 * 常驻定时任务只有两个：同步任务重试、邀请过期清理（PLAN §5.13 上限 2 个）。
 */
object Lifecycle {

    /** 任务重试循环（M-14：单飞守卫，避免上一轮未跑完时被下一 tick 重复领取） */
    fun startSyncLoop(pollTicks: Long) {
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        submitAsync(delay = pollTicks, period = pollTicks) {
            if (!running.compareAndSet(false, true)) return@submitAsync
            try {
                runCatching {
                    val repos = Repos.fromStorage()
                    val rules = TownRules.fromSettings()
                    val towns = TownService(repos, rules)
                    SyncExecutor(repos, DominionApiGateway(), SyncSettings.fromSettings(), towns).runOnce()
                }.onFailure { warning("[同步] 执行失败: ${it.javaClass.simpleName}: ${it.message}") }
            } finally {
                running.set(false)
            }
        }
    }

    /** 邀请过期清理（同时刷新补全用的镇名缓存，不新增常驻任务） */
    fun startInviteCleanup(periodTicks: Long = 6000L) {
        submitAsync(delay = periodTicks, period = periodTicks) {
            runCatching {
                val repos = Repos.fromStorage()
                InviteService(repos, TownRules.fromSettings()).expireOverdue()
                cn.trtown.command.SuggestCache.refreshTownsAsync()
            }.onFailure { warning("[邀请] 清理失败: ${it.message}") }
        }
    }

    /** 入服自愈 + 缓存刷新 */
    fun registerListener() {
        Bukkit.getPluginManager().registerEvents(object : Listener {

            @EventHandler
            fun onJoin(event: PlayerJoinEvent) {
                val uuid = event.player.uniqueId
                PlaceholderCache.refreshAsync(uuid)
                SuggestCache.refreshPendingAsync(uuid)
                SuggestCache.refreshTerritoriesAsync(uuid)
                PlatformScheduler.runAsync(delayTicks = 20) {
                    runCatching { selfHeal(uuid) }
                }
            }

            @EventHandler
            fun onQuit(event: PlayerQuitEvent) {
                PlaceholderCache.invalidate(event.player.uniqueId)
            }
        }, Bukkit.getPluginManager().getPlugin("TrTown") ?: return)
    }

    /** 成员所属镇已绑定领地但没有账本记录 → 补一条 GRANT */
    private fun selfHeal(uuid: java.util.UUID) {
        val repos = Repos.fromStorage()
        val member = repos.members.findByUuid(uuid) ?: return
        val town = repos.towns.findById(member.townId) ?: return
        val dominionId = town.dominionId ?: return
        if (repos.grants.find(town.id, dominionId, uuid) != null) return
        repos.tasks.enqueue(town.id, dominionId, SyncAction.GRANT, uuid, null)
    }
}

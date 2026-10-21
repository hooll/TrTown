package cn.trtown.platform

import cn.trtown.command.SuggestCache
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.SyncExecutor
import cn.trtown.dominion.SyncSettings
import cn.trtown.model.SyncAction
import cn.trtown.model.TownState
import cn.trtown.papi.PlaceholderCache
import cn.trtown.service.InviteService
import cn.trtown.service.NotifyHooks
import cn.trtown.service.Repos
import cn.trtown.service.TownRules
import cn.trtown.service.TownService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import taboolib.common.platform.function.submitAsync

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
                }.onFailure { Log.failure("[同步] 执行失败", it) }
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
            }.onFailure { Log.failure("[邀请] 清理失败", it) }
        }
    }

    /** 入服自愈 + 缓存刷新 + 离线邀请汇总提醒 */
    fun registerListener() {
        Bukkit.getPluginManager().registerEvents(object : Listener {

            @EventHandler
            fun onJoin(event: PlayerJoinEvent) {
                val uuid = event.player.uniqueId
                PlaceholderCache.refreshAsync(uuid)
                SuggestCache.refreshPendingAsync(uuid)
                SuggestCache.refreshTerritoriesAsync(uuid)
                PlatformScheduler.runAsync(delayTicks = 20) {
                    runCatching { selfHeal(uuid) }.onFailure { Log.failure("入服自愈", it) }
                    // M-25：邀请发出时对方很可能不在线，只做"当场提示"在离线场景仍然不成立
                    runCatching { remindPendingInvites(uuid) }.onFailure { Log.failure("入服邀请提醒", it) }
                }
            }

            @EventHandler
            fun onQuit(event: PlayerQuitEvent) {
                PlaceholderCache.invalidate(event.player.uniqueId)
            }
        }, Bukkit.getPluginManager().getPlugin("TrTown") ?: return)
    }

    /**
     * 入服汇总提醒（PLAN §5.15 硬规则 4）。
     *
     * 查库在异步线程（本方法由 `runAsync` 调进来），投递由 [NotifyHooks] 接到 `Notify.send`
     * 再切玩家 region 线程 —— 两步**不能**混在一个 lambda 里（R29）。
     */
    private fun remindPendingInvites(uuid: java.util.UUID) {
        val pending = InviteService(Repos.fromStorage(), TownRules.fromSettings()).pendingOf(uuid)
        if (pending.isEmpty()) return
        NotifyHooks.joinReminder(uuid, pending.map { it.townName })
    }

    /**
     * 入服自愈。
     *
     * 两件事：
     * 1. **M-35 兜底**：成员挂在已解散（或已不存在）的镇上 → 清掉成员行并告诉玩家。
     *    不清的话玩家会彻底卡死（`/tt leave` 要转让、`/tt create` 说已在镇里、其余命令说镇已解散）。
     *    收尾路径已经会删成员行，但历史脏数据或中断的解散仍可能留下这种状态，
     *    而且 `sweepDisbanded` 只扫 `dominion_id` 非空的镇，扫不到它们 —— 只能靠玩家自己登录脱身。
     * 2. 成员所属镇已绑定领地但没有成员记录 → 补一条 `MEMBER_ADD`；flag 不一致 → `MEMBER_FLAGS`。
     */
    private fun selfHeal(uuid: java.util.UUID) {
        val repos = Repos.fromStorage()
        val member = repos.members.findByUuid(uuid) ?: return
        val town = repos.towns.findById(member.townId)

        if (town == null || town.state != TownState.ACTIVE) {
            repos.members.delete(member.townId, uuid)
            repos.audit.append(uuid, member.townId, "member-stale-cleaned", "state=${town?.state}", System.currentTimeMillis())
            Notify.send(uuid, "town.member.stale")
            return
        }

        val dominionId = town.dominionId ?: return
        val gateway = DominionApiGateway()
        val ref = gateway.resolveById(dominionId) ?: return
        // 镇长 = 领地 owner，不是成员；入服自愈不碰他
        if (uuid == ref.ownerUuid) return

        // 已经有本成员的待处理任务 → 不重复入队
        val pending = repos.tasks.listByTown(town.id).any {
            it.targetUuid == uuid && it.state == cn.trtown.model.SyncState.PENDING &&
                it.action in setOf(SyncAction.MEMBER_ADD, SyncAction.MEMBER_FLAGS, SyncAction.MEMBER_REMOVE)
        }
        if (pending) return

        when (gateway.isMember(ref, uuid)) {
            null -> return // 玩家不在 Dominion 缓存，下次登录再试
            false -> repos.tasks.enqueue(town.id, dominionId, SyncAction.MEMBER_ADD, uuid, null)
            true -> {
                val expected = cn.trtown.dominion.DominionConfig.expectedFlags(member.role)
                val actual = gateway.memberFlags(ref, uuid) ?: return
                // 缺失按 false 处理：MEMBER 的默认期望就是"一个 flag 都不设"
                if (expected.any { (actual[it.key] ?: false) != it.value }) {
                    repos.tasks.enqueue(town.id, dominionId, SyncAction.MEMBER_FLAGS, uuid, null)
                }
            }
        }
    }
}

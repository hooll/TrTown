package cn.trtown.command

import cn.trtown.Settings
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.DominionGateway
import cn.trtown.service.Repos
import cn.trtown.service.SuggestHooks
import cn.trtown.platform.PlatformScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tab 补全的唯一数据源（PLAN §5.6.3）。
 *
 * **读取侧只碰内存**：补全 lambda 在主线程（region 线程）按 Tab 高频触发，
 * 一律不许查库 / 调 Dominion / 读 `Settings`。
 * 写入侧全部走 `PlatformScheduler.runAsync`，每类一个单飞开关。
 */
object SuggestCache {

    @Volatile
    private var towns: List<String> = emptyList()

    @Volatile
    private var flags: List<String> = emptyList()

    private val pendingInvites = ConcurrentHashMap<UUID, List<String>>()
    private val territories = ConcurrentHashMap<UUID, List<String>>()

    private val townsBusy = AtomicBoolean(false)
    private val pendingBusy = AtomicBoolean(false)
    private val territoriesBusy = AtomicBoolean(false)

    // ---- 读取（只读内存） ----

    fun towns(): List<String> = towns

    fun flags(): List<String> = flags

    /** 只读：**不触发刷新**（补全回调必须零副作用，AC-47 ⑥ 直接断言这条） */
    fun pendingInvites(uuid: UUID?): List<String> = uuid?.let { pendingInvites[it] } ?: emptyList()

    /** 只读：**不触发刷新**；数据由入服 / 领地变更时的异步刷新维护 */
    fun territories(uuid: UUID?): List<String> = uuid?.let { territories[it] } ?: emptyList()

    // ---- flag 快照（启动 / admin reload 各调一次） ----

    fun updateFlags(editable: List<String>, aliases: Map<String, String>) {
        flags = Suggestions.flags(editable, aliases)
    }

    // ---- 异步刷新 ----

    fun refreshTownsAsync() {
        if (!townsBusy.compareAndSet(false, true)) return
        PlatformScheduler.runAsync(delayTicks = 1) {
            try {
                towns = Repos.fromStorage().towns.listActive().map { it.name }
            } catch (ex: Throwable) {
                cn.trtown.platform.Log.failure("[补全] 镇名缓存刷新失败", ex)
            } finally {
                townsBusy.set(false)
            }
        }
    }

    fun refreshPendingAsync(uuid: UUID) {
        if (!pendingBusy.compareAndSet(false, true)) return
        PlatformScheduler.runAsync(delayTicks = 1) {
            try {
                val repos = Repos.fromStorage()
                val names = repos.invites.listPendingByTarget(uuid).mapNotNull { invite ->
                    repos.towns.findById(invite.townId)?.name
                }
                pendingInvites[uuid] = names
            } catch (ex: Throwable) {
                cn.trtown.platform.Log.failure("[补全] 邀请缓存刷新失败", ex)
            } finally {
                pendingBusy.set(false)
            }
        }
    }

    fun refreshTerritoriesAsync(uuid: UUID) {
        if (!territoriesBusy.compareAndSet(false, true)) return
        PlatformScheduler.runAsync(delayTicks = 1) {
            try {
                territories[uuid] = gateway().ownedNames(uuid)
            } catch (ex: Throwable) {
                cn.trtown.platform.Log.failure("[补全] 领地名缓存刷新失败", ex)
            } finally {
                territoriesBusy.set(false)
            }
        }
    }

    /** 异步路径专用：Dominion 调用只在异步线程发生 */
    private fun gateway(): DominionGateway = DominionApiGateway()

    // ---- 失效 ----

    fun invalidatePending(uuid: UUID) {
        pendingInvites.remove(uuid)
        refreshPendingAsync(uuid)
    }

    fun invalidateTerritories(uuid: UUID) {
        territories.remove(uuid)
        refreshTerritoriesAsync(uuid)
    }

    fun clear() {
        towns = emptyList()
        flags = emptyList()
        pendingInvites.clear()
        territories.clear()
    }

    // ---- 测试可见的写入入口（PLAN §5.6.3 可测性契约） ----

    @JvmSynthetic
    internal fun putTownsForTest(list: List<String>) {
        towns = list
    }

    @JvmSynthetic
    internal fun putPendingForTest(uuid: UUID, list: List<String>) {
        pendingInvites[uuid] = list
    }

    @JvmSynthetic
    internal fun putTerritoriesForTest(uuid: UUID, list: List<String>) {
        territories[uuid] = list
    }

    @JvmSynthetic
    internal fun putFlagsForTest(list: List<String>) {
        flags = list
    }

    /** 启动 / `admin reload` 时用配置重建 flag 快照 */
    fun reloadFlagsFromSettings() {
        updateFlags(Settings.editableFlags, Settings.flagAliases)
    }

    /** 接线：把 `SuggestHooks` 指向本缓存 */
    fun installHooks() {
        SuggestHooks.onTownsChanged = { refreshTownsAsync() }
        SuggestHooks.onPendingChanged = { uuid -> invalidatePending(uuid) }
        SuggestHooks.onTerritoriesChanged = { uuid -> invalidateTerritories(uuid) }
        SuggestHooks.onFlagsChanged = { reloadFlagsFromSettings() }
    }
}

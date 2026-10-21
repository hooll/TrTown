package cn.trtown.dominion

import cn.trtown.model.Town
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * `dominion_id → 小镇` 的内存索引（PLAN §5.16.7）。
 *
 * 为什么需要：`DominionTransferEvent` 在**全局线程**同步触发，而 Dominion 在事件里等 future
 * （`getFutureToComplete()`），监听器里不能查库、不能阻塞。用这份只读快照做 O(1) 判断。
 *
 * 维护点：启动时 [reload]、绑定/解绑（`TerritoryService`）、转让成功（`SyncExecutor`）、解散收尾（`TownService`）。
 * 缓存缺失时**不拦截**（宁可漏拦一次，也不误拦别人的领地转让）。
 */
object DominionBindings {

    data class Binding(val townId: Int, val townName: String, val ownerUuid: UUID)

    private val byDominion = ConcurrentHashMap<Int, Binding>()

    /** 本插件自己发起的 `setOwner`，事件监听器必须放行（否则会把自己的转让拦掉） */
    private val internalTransfers = ConcurrentHashMap.newKeySet<Int>()

    fun put(dominionId: Int, townId: Int, townName: String, ownerUuid: UUID) {
        byDominion[dominionId] = Binding(townId, townName, ownerUuid)
    }

    fun remove(dominionId: Int) {
        byDominion.remove(dominionId)
    }

    fun updateOwner(dominionId: Int, ownerUuid: UUID) {
        byDominion.computeIfPresent(dominionId) { _, old -> old.copy(ownerUuid = ownerUuid) }
    }

    fun get(dominionId: Int): Binding? = byDominion[dominionId]

    fun reload(towns: List<Town>) {
        byDominion.clear()
        towns.filter { it.state == cn.trtown.model.TownState.ACTIVE && it.dominionId != null }
            .forEach { put(it.dominionId!!, it.id, it.name, it.ownerUuid) }
    }

    fun beginInternalTransfer(dominionId: Int) {
        internalTransfers += dominionId
    }

    fun endInternalTransfer(dominionId: Int) {
        internalTransfers -= dominionId
    }

    fun isInternalTransfer(dominionId: Int): Boolean = dominionId in internalTransfers

    /** 单测用：全局状态必须能清 */
    @JvmSynthetic
    internal fun clear() {
        byDominion.clear()
        internalTransfers.clear()
    }
}

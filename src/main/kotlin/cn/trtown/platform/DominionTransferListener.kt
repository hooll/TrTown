package cn.trtown.platform

import cn.lunadeer.dominion.events.dominion.modify.DominionTransferEvent
import cn.trtown.command.Messages
import cn.trtown.dominion.DominionBindings
import cn.trtown.service.Repos
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import java.util.UUID

/**
 * 拦截 Dominion 原生转让（PLAN §5.16.7）。
 *
 * 规则：
 * 1. `dominion.id` 不是任何镇的主领地 → 不管；
 * 2. 是主领地 → **取消**，提示玩家用 `/tt transfer`（领地与小镇必须一起转）；
 * 3. 账本 owner 与事件 `oldOwner` 不一致 → 只记 WARN + 审计，不拦截
 *    （缓存/数据已经不一致时，再拦会让玩家完全无法处理）。
 *
 * 监听器在全局线程同步执行，Dominion 在事件里等 future —— 这里**只查内存索引 + 取消 + 投递消息**，
 * 不查库、不调 Dominion、不阻塞。审计写库放到异步线程。
 */
object DominionTransferListener {

    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("TrTown") ?: return
        Bukkit.getPluginManager().registerEvents(Handler, plugin)
    }

    /**
     * 纯判定（单测入口）：这次转让是否必须拦截。
     *
     * 条件：不是本插件自己发起的 + 领地是某镇主领地 + 事件里的 oldOwner 与账本一致。
     * 账本不一致时放行（只告警），避免缓存/数据已坏时把玩家彻底卡死。
     */
    internal fun shouldBlock(dominionId: Int, oldOwner: UUID): Boolean {
        if (DominionBindings.isInternalTransfer(dominionId)) return false
        val binding = DominionBindings.get(dominionId) ?: return false
        return oldOwner == binding.ownerUuid
    }

    private object Handler : Listener {

        @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
        fun onTransfer(event: DominionTransferEvent) {
            val dom = event.dominion
            // 本插件自己发起的 setOwner（TRANSFER_DOMINION 任务）必须放行
            if (DominionBindings.isInternalTransfer(dom.id)) return
            val binding = DominionBindings.get(dom.id) ?: return

            val oldOwner = event.oldOwner.uuid
            if (oldOwner != null && oldOwner != binding.ownerUuid) {
                Log.warn(
                    "转让事件与账本不一致：领地 ${dom.id} 实际 owner=$oldOwner，账本=${binding.ownerUuid}（镇 ${binding.townName}），" +
                        "本次不拦截",
                )
                PlatformScheduler.runAsync(delayTicks = 1) {
                    runCatching {
                        Repos.fromStorage().audit.append(
                            null, binding.townId, "transfer-event-mismatch",
                            "dominion=${dom.id} actual=$oldOwner ledger=${binding.ownerUuid}",
                        )
                    }.onFailure { Log.failure("写转让不一致审计", it) }
                }
                return
            }

            event.isCancelled = true
            val text = Messages.get("town.transfer.dominion-blocked", binding.townName)
            val operator = event.operator
            if (operator is Player) {
                PlatformScheduler.runForPlayer(operator) { operator.sendMessage(text) }
            } else {
                operator.sendMessage(text)
            }
        }
    }
}

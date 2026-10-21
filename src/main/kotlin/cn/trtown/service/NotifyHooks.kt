package cn.trtown.service

import java.util.UUID

/**
 * 玩家通知钩子（PLAN §5.15，M-25）。
 *
 * 与 [SuggestHooks] 同一手法：服务层是纯 Kotlin，不许碰 Bukkit / 消息通道，
 * 所有"告诉某个玩家某件事"都通过这里注入，启动时由 `TrTown.onEnable` 接线到
 * `platform/Notify.send`。
 *
 * 全部默认空实现 → 单测里不注入也能安全调用；**AC-50 ① 的闸门就是替换这两个钩子
 * 断言"invite 真的触发了通知且参数对"**，而不是 grep 代码里有没有那一行。
 */
object NotifyHooks {

    /** 某人被邀请进某镇：`(目标 UUID, 镇名, 邀请人 UUID)` */
    @Volatile
    var onInvited: (UUID, String, UUID) -> Unit = { _, _, _ -> }

    /** 某人入服时还有离线期间收到的邀请：`(UUID, 镇名列表)` */
    @Volatile
    var onJoinReminder: (UUID, List<String>) -> Unit = { _, _ -> }

    fun invited(target: UUID, townName: String, inviterUuid: UUID) =
        onInvited(target, townName, inviterUuid)

    fun joinReminder(target: UUID, townNames: List<String>) =
        onJoinReminder(target, townNames)
}

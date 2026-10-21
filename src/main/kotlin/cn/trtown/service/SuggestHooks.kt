package cn.trtown.service

import java.util.UUID

/**
 * 补全数据钩子（PLAN §5.6.3「可测性契约」，v1.20）。
 *
 * 服务层是纯 Kotlin，不能依赖 Bukkit / PAPI / 命令层缓存，
 * 因此所有"补全数据源"都通过这里注入，启动时由 `TrTown.onEnable` 接线。
 *
 * 全部默认空实现 → 单测里不注入也能安全调用。
 */
object SuggestHooks {

    /** 在线玩家名（AC-47 的唯一入口；命令层禁止直接调 `Bukkit#getOnlinePlayers`） */
    @Volatile
    var onlineNames: () -> List<String> = { emptyList() }

    /** 镇名集合发生变化（建镇 / 解散 / 转让） */
    @Volatile
    var onTownsChanged: () -> Unit = {}

    /** 某玩家的待处理邀请发生变化（邀请 / 接受 / 拒绝 / 过期 / 入镇 / 退镇） */
    @Volatile
    var onPendingChanged: (UUID) -> Unit = {}

    /** 某玩家名下可绑定的领地名发生变化 */
    @Volatile
    var onTerritoriesChanged: (UUID) -> Unit = {}

    fun townsChanged() = onTownsChanged()

    fun pendingChanged(uuid: UUID) = onPendingChanged(uuid)

    fun territoriesChanged(uuid: UUID) = onTerritoriesChanged(uuid)
}

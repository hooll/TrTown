package cn.trtown.service

import cn.trtown.dominion.BlockPosition
import cn.trtown.dominion.DominionBindings
import cn.trtown.dominion.DominionBounds
import cn.trtown.dominion.DominionGateway
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import java.util.UUID

/**
 * 主领地绑定（PLAN §5.16 / W5）。
 *
 * v1.25 起**不建组**：
 * - 镇长 = 领地 owner，天然全权限，TrTown 不对他做任何 Dominion 写操作；
 * - 成员 = 只 `addMember`（MEMBER 预设为空，与 Dominion GUI 手动加成员一致）；
 * - 管理 = `addMember` + 把 MANAGER 预设（默认 `administration` 7 项）设为 true。
 *
 * 绑定/解绑/换绑 = 成员增删 + 成员 flag 设置，全部先入队再执行（I5）。
 */
class TerritoryService(
    private val repos: Repos,
    private val gateway: DominionGateway,
    private val towns: TownService,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 是否允许跨镇传送（PLAN §5.17.3）；注入而非直读 Settings，服务层保持可单测 */
    private val allowOthers: () -> Boolean = { true },
) {

    /** `/tt tp` 的解析结果：目标领地 + 展示用镇名 + 传送点坐标 */
    data class TpTarget(val dominionId: Int, val townName: String, val location: BlockPosition)

    fun setterritory(actor: UUID, dominionName: String?, at: BlockPosition? = null): Town {
        val member = towns.requireMember(actor)
        if (member.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        val town = towns.requireActiveTown(member.townId)

        val ref = if (dominionName.isNullOrBlank()) {
            // 无参 = 脚下领地（PLAN §5.16.2）
            val pos = at ?: throw TownException(
                TownError.DOMINION_NOT_FOUND,
                "你脚下没有领地。站到自己领地里再执行，或 /tt setterritory <领地名>",
            )
            gateway.resolveAt(pos) ?: throw TownException(
                TownError.DOMINION_NOT_FOUND,
                "你脚下没有领地。站到自己领地里再执行，或 /tt setterritory <领地名>",
            )
        } else {
            gateway.resolveByName(dominionName) ?: throw TownException(TownError.DOMINION_NOT_FOUND, dominionName)
        }

        // I3：绑定者必须是领地 owner
        if (ref.ownerUuid != actor) throw TownException(TownError.DOMINION_NOT_OWNER, ref.name)

        // I2：一块领地只能被一个镇绑定；DISBANDED 的旧镇若无挂起任务则先收尾
        val holder = repos.towns.findByDominionId(ref.id)
        if (holder != null && holder.id != town.id) {
            if (holder.state == TownState.DISBANDED) {
                // M-32：收尾只等 PENDING；DEAD 不再阻塞（否则这块地永远绑不上，且不可自愈）。
                if (!towns.finalizeDisband(holder.id)) {
                    val pending = repos.tasks.countUnfinishedByTown(holder.id)
                    val dead = repos.tasks.countByTownAndState(holder.id, SyncState.DEAD)
                    throw TownException(
                        TownError.DOMINION_IN_USE,
                        if (pending > 0) {
                            "旧镇解散尚未完成（还有 $pending 条同步任务在跑），请稍后再试"
                        } else {
                            "旧镇解散时 Dominion 同步失败（$dead 条），领地可能残留权限，请联系管理员"
                        },
                    )
                }
            } else {
                throw TownException(TownError.DOMINION_IN_USE, holder.name)
            }
        }

        // 换绑：旧领地上的本镇成员逐个移除（旧模型留下的组不再处理）
        val oldDominionId = town.dominionId
        if (oldDominionId != null && oldDominionId != ref.id) {
            repos.members.listByTown(town.id).forEach { m ->
                enqueue(town.id, oldDominionId, SyncAction.MEMBER_REMOVE, m.uuid)
            }
        }

        // 新领地：已有成员逐个补加；管理额外设 MANAGER 预设 flag
        repos.members.listByTown(town.id).forEach { m ->
            // 镇长就是领地 owner，Dominion 不允许把 owner 加成成员 —— 不排任务
            if (m.uuid == ref.ownerUuid) return@forEach
            // 管理需要的 MEMBER_FLAGS 由 MEMBER_ADD 成功后再入队（避开 addMember 后缓存未刷新）
            enqueue(town.id, ref.id, SyncAction.MEMBER_ADD, m.uuid)
        }
        repos.towns.updateDominion(town.id, ref.id)
        DominionBindings.put(ref.id, town.id, town.name, actor)
        repos.audit.append(actor, town.id, "setterritory", "${ref.name}#${ref.id}", clock())
        SuggestHooks.territoriesChanged(actor)
        return town.copy(dominionId = ref.id)
    }

    fun unsetterritory(actor: UUID): Town {
        val member = towns.requireMember(actor)
        if (member.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        val town = towns.requireActiveTown(member.townId)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND)

        repos.members.listByTown(town.id).forEach { m ->
            enqueue(town.id, dominionId, SyncAction.MEMBER_REMOVE, m.uuid)
        }
        repos.towns.updateDominion(town.id, null)
        DominionBindings.remove(dominionId)
        repos.audit.append(actor, town.id, "unsetterritory", "#$dominionId", clock())
        SuggestHooks.territoriesChanged(actor)
        return town.copy(dominionId = null)
    }

    /**
     * 写小镇传送点（PLAN §5.17.2 / AC-58）。
     *
     * - 数据源唯一：只写 Dominion 本身，TrTown 不存副本；
     * - 必须镇长（MAYOR）或管理员（`actorIsAdmin` 由命令层按 `trtown.admin` 判定后传入）；
     * - **不校验站在领地内**（与 Dominion GUI 行为对齐，免得"想设在家门口"被拒）；
     * - 写操作抛 `DominionOpException`，由命令层翻成 `town.tp.set-failed` 并 `Log.failure`。
     */
    fun settp(actor: UUID, at: BlockPosition, actorIsAdmin: Boolean = false): BlockPosition {
        val member = towns.requireMember(actor)
        if (!actorIsAdmin && member.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        val town = towns.requireActiveTown(member.townId)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND)
        val ref = gateway.resolveById(dominionId)
            ?: throw TownException(TownError.DOMINION_NOT_FOUND, "#$dominionId")
        gateway.setTpLocation(ref, at)
        repos.audit.append(
            actor, town.id, "settp",
            "#$dominionId (${at.x}, ${at.y}, ${at.z})", clock(),
        )
        return at
    }

    /**
     * 解析 `/tt tp` 的目标（PLAN §5.17.1 / §5.17.3 / AC-61）。
     *
     * - 无镇名 = 自己镇（必须是成员）；
     * - 有镇名 = 指定 ACTIVE 镇，**可以是别人的镇**（m02168），不要求成员关系、不写库；
     * - 跨镇受 `teleport.allow-others` 总开关限制，无按镇开关（需加列迁移，本批不做）。
     */
    fun tpTarget(actor: UUID, townName: String?): TpTarget {
        val self = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        val requested = townName?.trim().orEmpty()
        val crossTown = requested.isNotEmpty()
        if (crossTown && !allowOthers()) throw TownException(TownError.TP_OTHERS_DISABLED)

        val town = if (!crossTown) {
            towns.requireActiveTown(self.townId)
        } else {
            val named = repos.towns.findByNameLower(requested)
                ?: throw TownException(TownError.TOWN_NOT_FOUND, requested)
            // 解散中的镇不能传（AC-61 的"不存在/解散"统一走 not-found 文案，由命令层映射）
            if (named.state != TownState.ACTIVE) throw TownException(TownError.TOWN_DISBANDED, named.name)
            named
        }

        // M-41（AUDIT §24.7）：非跨镇时"还没绑领地"必须报 TOWN_NOT_BOUND（→ town.tp.no-dominion）。
        // 报 TP_NOT_SET 会让玩家被指引去 /tt settp，而那条路必然回"还没绑定领地"，等于空转。
        val dominionId = town.dominionId ?: throw TownException(
            if (crossTown) TownError.TP_TARGET_NOT_SET else TownError.TOWN_NOT_BOUND,
            town.name,
        )
        // M-42（AUDIT §24.8）：Dominion 对未 settp 的领地会兜底返回领地中心，
        // 所以下面两处 not-set 只在"绑了但读不到"（世界卸载等）时触发，属兜底路径而非主路径。
        val ref = gateway.resolveById(dominionId)
            ?: throw TownException(
                if (crossTown) TownError.TP_TARGET_NOT_SET else TownError.TP_NOT_SET,
                town.name,
            )
        val location = gateway.tpLocation(ref) ?: throw TownException(
            if (crossTown) TownError.TP_TARGET_NOT_SET else TownError.TP_NOT_SET,
            town.name,
        )
        return TpTarget(dominionId, town.name, location)
    }

    /** 转让确认文案里的主领地名称；读不到时退回 `#id` */
    fun dominionName(dominionId: Int): String =
        gateway.resolveById(dominionId)?.name ?: "#$dominionId"

    /** 传送点坐标（GUI 展示用，PLAN §5.18.4）；未设置 / 读不到 → null */
    fun tpLocationOf(dominionId: Int): BlockPosition? {
        val ref = gateway.resolveById(dominionId) ?: return null
        return gateway.tpLocation(ref)
    }

    /** 领地范围（GUI 展示用，PLAN §5.18.4）；读不到 → null */
    fun boundsOf(dominionId: Int): DominionBounds? {
        val ref = gateway.resolveById(dominionId) ?: return null
        return gateway.bounds(ref)
    }

    private fun enqueue(townId: Int, dominionId: Int, action: SyncAction, target: UUID) {
        repos.tasks.enqueue(townId, dominionId, action, target, null, clock())
    }
}

package cn.trtown.service

import cn.trtown.dominion.BlockPosition
import cn.trtown.dominion.DominionBindings
import cn.trtown.dominion.DominionConfig
import cn.trtown.dominion.DominionGateway
import cn.trtown.dominion.DominionRef
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
) {

    /** `/tt perms` 的一行：角色应有值 vs Dominion 实际值（null = 未绑定/读不到） */
    data class FlagRow(val flag: String, val expected: Boolean, val actual: Boolean?)

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

    /** `/tt perms`：展示当前角色在 Dominion 里的 flag 实况（PLAN §5.16.5） */
    fun describeFlags(target: UUID): List<FlagRow> {
        val member = repos.members.findByUuid(target) ?: throw TownException(TownError.NOT_IN_TOWN)
        val town = repos.towns.findById(member.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        val expected = DominionConfig.expectedFlags(member.role)
        val actual: Map<String, Boolean>? = town.dominionId?.let { dominionId ->
            val ref = gateway.resolveById(dominionId) ?: return@let null
            if (ref.ownerUuid == target) {
                // owner 天然全权限
                expected.mapValues { true }
            } else {
                gateway.memberFlags(ref, target)
            }
        }
        return expected.keys.sorted().map { flag ->
            FlagRow(flag, expected.getValue(flag), actual?.get(flag))
        }
    }

    /** 转让确认文案里的主领地名称；读不到时退回 `#id` */
    fun dominionName(dominionId: Int): String =
        gateway.resolveById(dominionId)?.name ?: "#$dominionId"

    private fun enqueue(townId: Int, dominionId: Int, action: SyncAction, target: UUID) {
        repos.tasks.enqueue(townId, dominionId, action, target, null, clock())
    }
}

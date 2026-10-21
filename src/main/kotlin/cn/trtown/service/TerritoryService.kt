package cn.trtown.service

import cn.trtown.dominion.DominionConfig
import cn.trtown.dominion.DominionGateway
import cn.trtown.dominion.DominionRef
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.Town
import cn.trtown.model.TownGroup
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import java.util.UUID

/**
 * 主领地绑定（PLAN §5.4 / §6.1 W5）。
 *
 * 顺序铁律：ensureGroup(MAYOR) → (MANAGER) → (MEMBER) → 写 trtown_groups → 逐个 GRANT → 最后写 dominion_id。
 * 任一步失败回滚本次创建的组，绝不落 dominion_id。
 */
class TerritoryService(
    private val repos: Repos,
    private val gateway: DominionGateway,
    private val towns: TownService,
    private val groupNameFormat: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun setterritory(actor: UUID, dominionName: String?): Town {
        val member = towns.requireMember(actor)
        if (member.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        val town = towns.requireActiveTown(member.townId)

        val ref = if (dominionName.isNullOrBlank()) {
            throw TownException(TownError.DOMINION_NOT_FOUND, "未指定领地名称")
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
                // 收尾失败时才解释原因 —— 且不再笼统地说"请稍后再试"，玩家等不到的东西不要让他等。
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

        // 换绑：先把旧领地全量 REVOKE，再给旧领地的 owned 组入队 DELETE_GROUP（M-11）
        val oldDominionId = town.dominionId
        if (oldDominionId != null && oldDominionId != ref.id) {
            revokeAll(town, oldDominionId)
            repos.groups.listByTown(town.id).filter { it.owned }.forEach { group ->
                repos.tasks.enqueue(
                    town.id, oldDominionId, SyncAction.DELETE_GROUP, null,
                    "{\"group_id\":${group.groupId},\"group_name\":\"${group.groupName}\"}", clock(),
                )
            }
            repos.groups.deleteByTown(town.id)
        }

        // 建组（失败回滚本次创建的组）
        val created = mutableListOf<Int>()
        try {
            listOf(TownRole.MAYOR, TownRole.MANAGER, TownRole.MEMBER).forEach { role ->
                val group = gateway.ensureGroup(ref, role, groupName(town.id, role), DominionConfig.flagsOf(role))
                if (group.created) created += group.id
                val known = repos.groups.find(town.id, role)
                repos.groups.upsert(
                    TownGroup(town.id, role, group.id, group.name, owned = known?.owned ?: group.created),
                )
            }
        } catch (ex: Exception) {
            // M-24：回滚前先把真实堆栈留下（线上只看到一句 message 的教训见 PLAN §5.14）
            cn.trtown.platform.Log.failure("绑定领地 ${ref.name}#${ref.id} 建组", ex)
            // M-27：两侧都要收干净 —— Dominion 侧已建的组 + TrTown 自己的账本行。
            // 只清账本会留下"半成品组"，下次绑定时 ensureGroup 的 existing 分支会命中它。
            created.forEach { id ->
                runCatching { gateway.deleteGroupIfEmpty(ref, id) }
                    .onFailure { cn.trtown.platform.Log.failure("回滚删除组 $id", it) }
            }
            repos.groups.deleteByTown(town.id)
            throw TownException(TownError.DOMINION_BIND_FAILED, ex.message)
        }

        // 先入队 GRANT，再落 dominion_id
        repos.members.listByTown(town.id).forEach { m ->
            // M-30：镇长就是领地 owner，Dominion 不允许把 owner 加成成员 —— 排这条任务只会永久重试
            if (m.uuid == ref.ownerUuid) return@forEach
            repos.tasks.enqueue(town.id, ref.id, SyncAction.GRANT, m.uuid, null, clock())
        }
        repos.towns.updateDominion(town.id, ref.id)
        repos.audit.append(actor, town.id, "setterritory", "${ref.name}#${ref.id}", clock())
        SuggestHooks.territoriesChanged(actor)
        return town.copy(dominionId = ref.id)
    }

    fun unsetterritory(actor: UUID): Town {
        val member = towns.requireMember(actor)
        if (member.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        val town = towns.requireActiveTown(member.townId)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND)

        revokeAll(town, dominionId)
        repos.groups.listByTown(town.id).filter { it.owned }.forEach { group ->
            repos.tasks.enqueue(
                town.id, dominionId, SyncAction.DELETE_GROUP, null,
                "{\"group_id\":${group.groupId},\"group_name\":\"${group.groupName}\"}", clock(),
            )
        }
        repos.groups.deleteByTown(town.id)
        repos.towns.updateDominion(town.id, null)
        repos.audit.append(actor, town.id, "unsetterritory", "#$dominionId", clock())
        SuggestHooks.territoriesChanged(actor)
        return town.copy(dominionId = null)
    }

    private fun revokeAll(town: Town, dominionId: Int) {
        repos.members.listByTown(town.id).forEach { m ->
            repos.tasks.enqueue(town.id, dominionId, SyncAction.REVOKE, m.uuid, null, clock())
        }
    }

    private fun groupName(townId: Int, role: TownRole): String =
        String.format(groupNameFormat, townId, role.name)
}

package cn.trtown.service

import cn.trtown.model.Member
import cn.trtown.model.SyncAction
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import java.util.UUID

/**
 * 小镇主服务：建镇 / 改角色 / 转让 / 解散。
 *
 * 纯 Kotlin，不依赖 Bukkit；所有方法同步阻塞，调用方必须在异步线程执行（AC-24）。
 */
class TownService(
    private val repos: Repos,
    private val rules: TownRules,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 建镇。成功后镇长成员行同时落库（I1：一人一镇由 `members.uuid` 唯一性 + 建镇前检查共同保证）。 */
    fun create(actor: UUID, name: String): Town {
        val trimmed = name.trim()
        if (!Regex(rules.nameRegex).matches(trimmed)) {
            throw TownException(TownError.NAME_INVALID, trimmed)
        }
        if (repos.towns.findByNameLower(trimmed) != null) {
            throw TownException(TownError.NAME_TAKEN, trimmed)
        }
        if (repos.members.findByUuid(actor) != null) {
            throw TownException(TownError.ALREADY_IN_TOWN)
        }
        if (repos.towns.countActiveByOwner(actor) >= rules.maxTownsPerPlayer) {
            throw TownException(TownError.TOWN_LIMIT)
        }
        val now = clock()
        val town = try {
            repos.towns.insert(trimmed, actor, now)
        } catch (ex: Exception) {
            // 并发建镇撞唯一索引：翻译成业务错误
            if (repos.towns.findByNameLower(trimmed) != null) throw TownException(TownError.NAME_TAKEN, trimmed)
            throw ex
        }
        repos.members.insert(Member(town.id, actor, TownRole.MAYOR, emptyMap(), now))
        repos.audit.append(actor, town.id, "create", trimmed, now)
        return town
    }

    /** 改角色（仅镇长；不能用它任命第二个镇长，转让请用 [transfer]） */
    fun setrole(actor: UUID, target: UUID, role: TownRole): Member {
        if (role == TownRole.MAYOR) throw TownException(TownError.ROLE_INVALID, "镇长只能通过 transfer 转让")
        val actorMember = requireMember(actor)
        val town = requireActiveTown(actorMember.townId)
        if (actorMember.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        if (target == actor) throw TownException(TownError.CANNOT_TARGET_SELF)
        val targetMember = repos.members.find(town.id, target)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER)
        if (targetMember.role == TownRole.MAYOR) throw TownException(TownError.CANNOT_TARGET_MAYOR)

        repos.members.updateRole(town.id, target, role)
        enqueue(town, SyncAction.MOVE_GROUP, target)
        repos.audit.append(actor, town.id, "setrole", "$target -> ${role.name}", clock())
        return targetMember.copy(role = role)
    }

    /** 转让镇长：目标升 MAYOR，原镇长降为 `town.transfer-demote-to` */
    fun transfer(actor: UUID, target: UUID): Town {
        val actorMember = requireMember(actor)
        val town = requireActiveTown(actorMember.townId)
        if (actorMember.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        if (target == actor) throw TownException(TownError.CANNOT_TARGET_SELF)
        val targetMember = repos.members.find(town.id, target)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER)

        val demoteTo = if (rules.transferDemoteTo == TownRole.MAYOR) TownRole.MEMBER else rules.transferDemoteTo
        repos.members.updateRole(town.id, target, TownRole.MAYOR)
        repos.members.updateRole(town.id, actor, demoteTo)
        repos.towns.updateOwner(town.id, target)
        enqueue(town, SyncAction.MOVE_GROUP, target)
        enqueue(town, SyncAction.MOVE_GROUP, actor)
        repos.audit.append(actor, town.id, "transfer", "$actor -> $target", clock())
        return repos.towns.findById(town.id) ?: town.copy(ownerUuid = target)
    }

    /**
     * 解散：成员逐个 REVOKE、本插件创建的组逐个 DELETE_GROUP，然后置 DISBANDED。
     * 组与账本行保留到执行器处理完（S3），这里只入队。
     */
    fun disband(actor: UUID): Town {
        val actorMember = requireMember(actor)
        val town = requireActiveTown(actorMember.townId)
        if (actorMember.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)

        val now = clock()
        // M-6：名字立刻墓碑化，同名新镇可以马上建
        repos.towns.tombstoneName(town.id, town.name)
        // 成员逐个 REVOKE 后删成员行
        repos.members.listByTown(town.id).forEach { member ->
            enqueue(town, SyncAction.REVOKE, member.uuid)
            repos.members.delete(town.id, member.uuid)
        }
        // M-9：DELETE_GROUP 必须带 group_id；组行保留到收尾时再删
        repos.groups.listByTown(town.id).filter { it.owned }.forEach { group ->
            enqueue(
                town, SyncAction.DELETE_GROUP, null,
                payload = "{\"group_id\":${group.groupId},\"group_name\":\"${group.groupName}\"}",
            )
        }
        repos.invites.deleteByTown(town.id)
        repos.towns.updateState(town.id, TownState.DISBANDED)
        repos.audit.append(actor, town.id, "disband", town.name, now)
        // 没有绑定领地 → 没有同步任务 → 立即收尾
        if (town.dominionId == null) finalizeDisband(town.id)
        return town.copy(state = TownState.DISBANDED)
    }

    /**
     * 解散收尾（M-5）：等该镇所有同步任务 DONE 后，清 `dominion_id`、删 grants、删组行。
     *
     * 幂等、可重启重跑：重复调用只会做剩下的部分。返回 true 表示已收尾。
     */
    fun finalizeDisband(townId: Int): Boolean {
        val town = repos.towns.findById(townId) ?: return false
        if (town.state != TownState.DISBANDED) return false
        if (repos.tasks.countPendingByTown(townId) > 0) return false

        val hadDominion = town.dominionId != null
        val hadGroups = repos.groups.listByTown(townId).isNotEmpty()
        if (!hadDominion && !hadGroups) return true

        repos.grants.deleteByTown(townId)
        if (hadDominion) repos.towns.updateDominion(townId, null)
        repos.groups.deleteByTown(townId)
        repos.audit.append(null, townId, "disband-finalized", "dominion=${town.dominionId}", clock())
        return true
    }

    // ---- 供其他服务与命令层复用 ----

    fun requireMember(uuid: UUID): Member =
        repos.members.findByUuid(uuid) ?: throw TownException(TownError.NOT_IN_TOWN)

    fun requireActiveTown(townId: Int): Town {
        val town = repos.towns.findById(townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        if (town.state != TownState.ACTIVE) throw TownException(TownError.TOWN_DISBANDED, town.name)
        return town
    }

    fun townOf(uuid: UUID): Town? = repos.members.findByUuid(uuid)?.let { repos.towns.findById(it.townId) }

    /** 只有绑定了主领地才入队同步任务（I5 由执行器保证） */
    private fun enqueue(town: Town, action: SyncAction, target: UUID?, payload: String? = null) {
        val dominionId = town.dominionId ?: return
        repos.tasks.enqueue(town.id, dominionId, action, target, payload, clock())
    }
}

package cn.trtown.service

import cn.trtown.model.SyncAction
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import java.util.UUID

/**
 * 成员关系：主动退出 / 踢人。
 *
 * 规则（PLAN §5.3）：
 * - 镇长不能直接退出，必须先转让；
 * - 管理只能踢普通成员，管理之间不能互踢；
 * - 退镇 / 被踢都要入队 REVOKE，让执行器按账本回收权限。
 */
class MembershipService(
    private val repos: Repos,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun leave(actor: UUID) {
        val member = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        if (member.role == TownRole.MAYOR) throw TownException(TownError.MAYOR_MUST_TRANSFER)
        val town = repos.towns.findById(member.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)

        repos.members.delete(town.id, actor)
        enqueue(town, actor)
        repos.audit.append(actor, town.id, "leave", member.role.name, clock())
        CacheHooks.invalidate(actor)
        SuggestHooks.townsChanged()
    }

    fun kick(actor: UUID, target: UUID) {
        val actorMember = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        if (actorMember.role == TownRole.MEMBER) throw TownException(TownError.NO_PERMISSION)
        if (target == actor) throw TownException(TownError.CANNOT_TARGET_SELF)

        val town = repos.towns.findById(actorMember.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        val targetMember = repos.members.find(town.id, target)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER)
        if (targetMember.role == TownRole.MAYOR) throw TownException(TownError.NO_PERMISSION)
        if (actorMember.role == TownRole.MANAGER && targetMember.role != TownRole.MEMBER) {
            throw TownException(TownError.MANAGER_CANNOT_TOUCH_MANAGER)
        }

        repos.members.delete(town.id, target)
        enqueue(town, target)
        repos.audit.append(actor, town.id, "kick", "$target (${targetMember.role.name})", clock())
        CacheHooks.invalidate(target)
        SuggestHooks.pendingChanged(target)
    }

    private fun enqueue(town: Town, target: UUID) {
        val dominionId = town.dominionId ?: return
        repos.tasks.enqueue(town.id, dominionId, SyncAction.MEMBER_REMOVE, target, null, clock())
    }
}

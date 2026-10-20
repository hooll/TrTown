package cn.trtown.service

import cn.trtown.model.Invite
import cn.trtown.model.InviteStatus
import cn.trtown.model.Member
import cn.trtown.model.SyncAction
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import java.util.UUID

/**
 * 邀请：邀请 / 接受 / 拒绝 / 过期清理。
 *
 * 纯 Kotlin，同步阻塞；调用方必须在异步线程执行。
 */
class InviteService(
    private val repos: Repos,
    private val rules: TownRules,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 镇长 / 管理可邀请；同一 (镇, 目标) 的 PENDING 只保留一条，重复邀请只刷新过期时间 */
    fun invite(actor: UUID, target: UUID): Invite {
        val actorMember = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        if (!actorMember.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
        if (target == actor) throw TownException(TownError.CANNOT_TARGET_SELF)

        val town = repos.towns.findById(actorMember.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        if (town.state != TownState.ACTIVE) throw TownException(TownError.TOWN_DISBANDED, town.name)
        if (repos.members.findByUuid(target) != null) throw TownException(TownError.TARGET_IN_TOWN)
        if (repos.invites.countPending(town.id) >= rules.maxPendingInvites) {
            throw TownException(TownError.INVITE_LIMIT)
        }

        val now = clock()
        val expiresAt = now + rules.inviteExpireSeconds * 1000L
        val existing = repos.invites.findPending(town.id, target)
        if (existing != null) {
            repos.invites.refreshExpiry(existing.id, expiresAt)
            repos.audit.append(actor, town.id, "invite-refresh", "$target", now)
            return existing.copy(expiresAt = expiresAt)
        }
        val invite = repos.invites.insert(town.id, target, actor, now, expiresAt)
        repos.audit.append(actor, town.id, "invite", "$target", now)
        return invite
    }

    /** 接受邀请：过期的置 EXPIRED 并拒绝；已在其他镇、镇已解散、人数满都拒绝 */
    fun accept(target: UUID, townId: Int): Member {
        val invite = repos.invites.findPending(townId, target)
            ?: throw TownException(TownError.INVITE_NOT_FOUND)
        val now = clock()
        if (now > invite.expiresAt) {
            repos.invites.updateStatus(invite.id, InviteStatus.EXPIRED)
            throw TownException(TownError.INVITE_EXPIRED)
        }
        if (repos.members.findByUuid(target) != null) throw TownException(TownError.ALREADY_IN_TOWN)
        val town = repos.towns.findById(townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        if (town.state != TownState.ACTIVE) throw TownException(TownError.TOWN_DISBANDED, town.name)
        if (repos.members.countByTown(town.id) >= rules.maxMembers) throw TownException(TownError.TOWN_FULL)

        repos.invites.updateStatus(invite.id, InviteStatus.ACCEPTED)
        val member = Member(town.id, target, TownRole.MEMBER, emptyMap(), now)
        repos.members.insert(member)
        enqueue(town, SyncAction.GRANT, target)
        repos.audit.append(target, town.id, "accept", invite.inviterUuid.toString(), now)
        return member
    }

    fun deny(target: UUID, townId: Int) {
        val invite = repos.invites.findPending(townId, target)
            ?: throw TownException(TownError.INVITE_NOT_FOUND)
        repos.invites.updateStatus(invite.id, InviteStatus.DECLINED)
        repos.audit.append(target, townId, "deny", invite.inviterUuid.toString(), clock())
    }

    /** 常驻任务：清理过期邀请，返回受影响行数 */
    fun expireOverdue(): Int = repos.invites.expireOverdue(clock())

    private fun enqueue(town: Town, action: SyncAction, target: UUID?) {
        val dominionId = town.dominionId ?: return
        repos.tasks.enqueue(town.id, dominionId, action, target, null, clock())
    }
}

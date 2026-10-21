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
 * 一条"待处理邀请"的展示视图（PLAN §5.15，M-25）。
 *
 * 带上镇名是因为无参 `/tt accept` 要直接回「已加入小镇：X」、≥2 条时要列出镇名，
 * 而 `Invite` 只有 `townId`；在服务层一次查完，命令层不必再回表。
 */
data class PendingInvite(val townId: Int, val townName: String, val inviterUuid: UUID)

/**
 * 无参 `/tt accept` / `/tt deny` 的选择结果（PLAN §5.15 / R30）。
 *
 * 抽成纯函数是为了让"≥2 条绝不自动选"这条规则**可断言**：
 * 命令层一碰 `Cmd.player` / `Cmd.reply` 就没法在无 Bukkit 的单测里跑，
 * 但"选哪一条"的决策本身是纯逻辑，不该跟着一起变成不可测。
 */
sealed interface InviteChoice {
    /** 没有待处理邀请 */
    data object None : InviteChoice

    /** 只有一条 → 直接处理 */
    data class Single(val invite: PendingInvite) : InviteChoice

    /** 两条以上 → 只列出来，让玩家点明（选错镇要退镇再进，代价远大于多敲四个字） */
    data class Ambiguous(val invites: List<PendingInvite>) : InviteChoice
}

fun chooseInvite(invites: List<PendingInvite>): InviteChoice = when {
    invites.isEmpty() -> InviteChoice.None
    invites.size == 1 -> InviteChoice.Single(invites.single())
    else -> InviteChoice.Ambiguous(invites)
}

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
            // M-25：重复邀请也算"对方被邀请了"，必须再提示一次（他很可能没看到上一次）
            NotifyHooks.invited(target, town.name, actor)
            return existing.copy(expiresAt = expiresAt)
        }
        val invite = repos.invites.insert(town.id, target, actor, now, expiresAt)
        repos.audit.append(actor, town.id, "invite", "$target", now)
        SuggestHooks.pendingChanged(target)
        // M-25：被邀请人必须当场看到提示（投递在接线层，服务层只发钩子）
        NotifyHooks.invited(target, town.name, actor)
        return invite
    }

    /**
     * 某人的待处理邀请（PLAN §5.15 硬规则 3）。
     *
     * **惰性过期**：读出来时若已过 `expiresAt`，就地置 `EXPIRED` 且**不计入返回值**。
     * 不做这一步的话，清理任务最长 5 分钟才跑一次，会出现"提示 1 条待处理 → 接受时说已过期"。
     */
    fun pendingOf(target: UUID): List<PendingInvite> {
        val now = clock()
        return repos.invites.listPendingByTarget(target).mapNotNull { invite ->
            if (now > invite.expiresAt) {
                repos.invites.updateStatus(invite.id, InviteStatus.EXPIRED)
                return@mapNotNull null
            }
            val town = repos.towns.findById(invite.townId) ?: return@mapNotNull null
            PendingInvite(town.id, town.name, invite.inviterUuid)
        }
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
        // M-15：入服时可能写过 60 s 负缓存，接受邀请后必须立刻失效
        CacheHooks.invalidate(target)
        SuggestHooks.pendingChanged(target)
        return member
    }

    fun deny(target: UUID, townId: Int) {
        val invite = repos.invites.findPending(townId, target)
            ?: throw TownException(TownError.INVITE_NOT_FOUND)
        repos.invites.updateStatus(invite.id, InviteStatus.DECLINED)
        repos.audit.append(target, townId, "deny", invite.inviterUuid.toString(), clock())
        SuggestHooks.pendingChanged(target)
    }

    /** 常驻任务：清理过期邀请，返回受影响行数 */
    fun expireOverdue(): Int = repos.invites.expireOverdue(clock())

    private fun enqueue(town: Town, action: SyncAction, target: UUID?) {
        val dominionId = town.dominionId ?: return
        repos.tasks.enqueue(town.id, dominionId, action, target, null, clock())
    }
}

package cn.trtown.service

import cn.trtown.dominion.DominionBindings
import cn.trtown.model.Member
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import cn.trtown.platform.Log
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
        // M-15：入服时可能已写入 60 s 负缓存，建镇后必须立刻失效
        CacheHooks.invalidate(actor)
        SuggestHooks.townsChanged()
        SuggestHooks.pendingChanged(actor)
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
        enqueue(town, SyncAction.MEMBER_FLAGS, target)
        repos.audit.append(actor, town.id, "setrole", "$target -> ${role.name}", clock())
        CacheHooks.invalidate(target)
        return targetMember.copy(role = role)
    }

    /** 转让预览（PLAN §5.16.7）：只校验，不写库；命令层据此决定要不要二次确认 */
    data class TransferPreview(val town: Town, val dominionId: Int?)

    fun previewTransfer(actor: UUID, target: UUID): TransferPreview {
        val (town, _) = validateTransfer(actor, target)
        return TransferPreview(town, town.dominionId)
    }

    /** 转让镇长：目标升 MAYOR，原镇长降为 `town.transfer-demote-to` */
    fun transfer(actor: UUID, target: UUID): Town {
        val (town, targetMember) = validateTransfer(actor, target)
        val demoteTo = if (rules.transferDemoteTo == TownRole.MAYOR) TownRole.MEMBER else rules.transferDemoteTo

        repos.members.updateRole(town.id, target, TownRole.MAYOR)
        repos.members.updateRole(town.id, actor, demoteTo)
        repos.towns.updateOwner(town.id, target)
        // 已绑定主领地：领地所有权必须跟着走（PLAN §5.16.7 第 5 条）。
        // TrTown 先落库、Dominion 后调；任务失败则 DEAD + 告警，不回滚 TrTown。
        town.dominionId?.let { enqueue(town, SyncAction.TRANSFER_DOMINION, target) }
        repos.audit.append(actor, town.id, "transfer", "$actor -> $target (${targetMember.role.name} → MAYOR)", clock())
        CacheHooks.invalidate(target)
        CacheHooks.invalidate(actor)
        SuggestHooks.townsChanged()
        return repos.towns.findById(town.id) ?: town.copy(ownerUuid = target)
    }

    private fun validateTransfer(actor: UUID, target: UUID): Pair<Town, Member> {
        val actorMember = requireMember(actor)
        val town = requireActiveTown(actorMember.townId)
        if (actorMember.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)
        if (target == actor) throw TownException(TownError.CANNOT_TARGET_SELF)
        val targetMember = repos.members.find(town.id, target)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER)
        return town to targetMember
    }

    /**
     * 解散：成员逐个 `MEMBER_REMOVE`，然后置 DISBANDED。
     * 成员行立刻删除；同步任务靠"镇已解散"放行移除（见 `SyncExecutor.memberRemove`）。
     */
    fun disband(actor: UUID): Town {
        val actorMember = requireMember(actor)
        val town = requireActiveTown(actorMember.townId)
        if (actorMember.role != TownRole.MAYOR) throw TownException(TownError.NOT_MAYOR)

        val now = clock()
        // M-6：名字立刻墓碑化，同名新镇可以马上建
        repos.towns.tombstoneName(town.id, town.name)
        // 成员逐个 MEMBER_REMOVE 后删成员行
        val disbanded = repos.members.listByTown(town.id)
        disbanded.forEach { member ->
            enqueue(town, SyncAction.MEMBER_REMOVE, member.uuid)
            repos.members.delete(town.id, member.uuid)
        }
        repos.invites.deleteByTown(town.id)
        repos.towns.updateState(town.id, TownState.DISBANDED)
        repos.audit.append(actor, town.id, "disband", town.name, now)
        // M-15：解散后所有原成员的前缀/角色都必须失效
        disbanded.forEach { CacheHooks.invalidate(it.uuid) }
        SuggestHooks.townsChanged()
        // 没有绑定领地 → 没有同步任务 → 立即收尾
        if (town.dominionId == null) finalizeDisband(town.id)
        return town.copy(state = TownState.DISBANDED)
    }

    /**
     * 解散收尾（M-5）：等该镇所有**还会再跑**的同步任务（PENDING）结束后，清 `dominion_id`、删 grants。
     *
     * 幂等、可重启重跑：重复调用只会做剩下的部分。返回 true 表示已收尾。
     *
     * **M-32**：`DEAD` 任务不阻塞收尾（见 `countUnfinishedByTown`），但必须留痕 ——
     * 否则 Dominion 侧可能残留权限，而 TrTown 这边已经什么都不记得了。
     * 留痕方式：一行 warning + 一条 `disband-finalized-degraded` 审计；任务行本身保留当证据。
     */
    fun finalizeDisband(townId: Int): Boolean {
        val town = repos.towns.findById(townId) ?: return false
        if (town.state != TownState.DISBANDED) return false
        if (repos.tasks.countUnfinishedByTown(townId) > 0) return false

        val dead = repos.tasks.countByTownAndState(townId, SyncState.DEAD)
        if (dead > 0) {
            Log.warn(
                "解散收尾跳过 $dead 条失败的 Dominion 同步（镇 #$townId，领地 ${town.dominionId}）——" +
                    "Dominion 侧权限可能残留，请用 /trtown admin verify 核对",
            )
            repos.audit.append(null, townId, "disband-finalized-degraded", "dead=$dead dominion=${town.dominionId}", clock())
        }

        val hadDominion = town.dominionId != null
        val hadMembers = repos.members.countByTown(townId) > 0
        if (!hadDominion && !hadMembers) return true

        repos.grants.deleteByTown(townId)
        if (hadDominion) {
            repos.towns.updateDominion(townId, null)
            DominionBindings.remove(town.dominionId)
        }
        // M-35：成员行也必须清。留着的话玩家会变成"已解散小镇的镇长"：
        // /tt leave 要求先转让、没有第二个人可转、/tt create 说已在镇里、/tt setterritory 说镇已解散 ——
        // 四条路全堵，只能管理员改库。
        repos.members.deleteByTown(townId)
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

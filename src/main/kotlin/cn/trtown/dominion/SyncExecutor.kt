package cn.trtown.dominion

import cn.trtown.model.Grant
import cn.trtown.model.Member
import cn.trtown.model.PermsJson
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncTask
import cn.trtown.model.TownGroup
import cn.trtown.service.Repos
import cn.trtown.service.TownService
import java.util.UUID

/**
 * 同步执行器：消费 `trtown_sync_tasks`，调用 [DominionGateway] 施加/撤销权限。
 *
 * 设计要点（PLAN §5.4 / §5.9）：
 * - 先有任务行再执行（I5）；
 * - 每个动作幂等，重跑不改变结果；
 * - 失败按退避重试，超过上限置 DEAD；
 * - 任务成功后如果该镇已解散且任务清空，触发解散收尾（M-5）。
 *
 * 同步阻塞，调用方必须在异步线程执行。
 */
class SyncExecutor(
    private val repos: Repos,
    private val gateway: DominionGateway,
    private val settings: SyncSettings,
    private val towns: TownService,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 取出到点任务并执行，返回本轮处理数量 */
    fun runOnce(limit: Int = 20): Int {
        sweepDisbanded()
        val tasks = repos.tasks.claimReady(clock(), limit)
        tasks.forEach { execute(it) }
        return tasks.size
    }

    /**
     * M-32 的自愈入口：把"已解散但 `dominion_id` 还没清"的镇收尾掉。
     *
     * 收尾原本只由"该镇最后一条同步任务跑完"触发。那条任务一旦变成 `DEAD`，
     * 就再没有任何事件会触发它 —— 那块领地会被永久占住（真服症状：
     * `该领地已被其他小镇绑定（旧镇解散尚未完成，请稍后再试）`，等到天荒地老也不会变）。
     *
     * 放在每轮轮询里而不是新开常驻任务（PLAN §5.13 上限 2 个）：一次小表 SELECT，
     * 且 `finalizeDisband` 本身幂等，没有脏数据时是纯读。
     */
    private fun sweepDisbanded() {
        repos.towns.listDisbandedUnfinished().forEach { town ->
            runCatching { towns.finalizeDisband(town.id) }
                .onFailure { cn.trtown.platform.Log.failure("解散收尾自愈（镇 #${town.id}）", it) }
        }
    }

    fun execute(task: SyncTask) {
        try {
            when (task.action) {
                SyncAction.GRANT -> grant(task)
                SyncAction.REVOKE -> revoke(task)
                SyncAction.MOVE_GROUP -> moveGroup(task)
                SyncAction.ENSURE_GROUP -> ensureGroups(task)
                SyncAction.DELETE_GROUP -> deleteGroup(task)
                SyncAction.SET_MEMBER_FLAG -> setMemberFlag(task)
            }
            repos.tasks.markDone(task.id, clock())
            // 解散收尾（幂等；非解散镇直接返回 false）
            towns.finalizeDisband(task.townId)
        } catch (ex: Exception) {
            onFailure(task, ex)
        }
    }

    private fun onFailure(task: SyncTask, ex: Exception) {
        // M-24：重试次数与退避写进库，堆栈进日志——两者都要，缺一个都查不动线上问题
        cn.trtown.platform.Log.failure("同步任务 ${task.action}#${task.id}", ex)
        val attempts = task.attempts + 1
        val dead = attempts >= settings.maxAttempts
        val backoff = settings.backoffSeconds.getOrElse(attempts - 1) { settings.backoffSeconds.lastOrNull() ?: 5L }
        val message = "${ex.javaClass.simpleName}: ${ex.message}"
        repos.tasks.markAttempt(task.id, message, attempts, clock() + backoff * 1000L, dead, clock())
    }

    // ---- 各动作 ----

    private fun grant(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        val member = repos.members.find(task.townId, uuid)
        if (member == null) {
            // 成员已退出，授权任务作废（撤销任务会由 leave/kick 入队）
            return
        }
        // M-30：Dominion 的 **owner 不是 member**，也不能被加进组
        // （真服日志：`You can't add dominion owner as a member.`）。
        // 镇长凭 owner 身份已拥有全部权限，这条任务本身没有意义 —— 直接收尾，
        // 否则它会以 PENDING 永久重试，每次打一屏堆栈。
        if (ref.ownerUuid == uuid) {
            recordOwnerNoop(task, member)
            return
        }
        val isMember = gateway.isMember(ref, uuid) ?: throw RetryLaterException("玩家不在 Dominion 缓存")
        val existing = repos.grants.find(task.townId, task.dominionId, uuid)

        if (existing == null) {
            val preGroupId = if (isMember) (gateway.memberGroupId(ref, uuid) ?: 0) else 0
            repos.grants.upsert(
                Grant(
                    townId = task.townId,
                    dominionId = task.dominionId,
                    uuid = uuid,
                    preMember = isMember,
                    preGroupId = preGroupId,
                    appliedGroupId = 0,
                    appliedRole = member.role,
                    memberAdded = false,
                    groupAdded = false,
                    appliedAt = clock(),
                ),
            )
        }

        var memberAdded = existing?.memberAdded ?: false
        if (!isMember) {
            gateway.ensureMember(ref, uuid)
            memberAdded = true
            val row = repos.grants.find(task.townId, task.dominionId, uuid)
            if (row != null) repos.grants.upsert(row.copy(memberAdded = true))
        }

        val group = gateway.ensureGroup(ref, member.role, groupName(task.townId, member.role), DominionConfig.flagsOf(member.role))
        val known = repos.groups.find(task.townId, member.role)
        repos.groups.upsert(
            TownGroup(task.townId, member.role, group.id, group.name, owned = known?.owned ?: group.created),
        )
        gateway.putInGroup(ref, group.id, uuid)

        val row = repos.grants.find(task.townId, task.dominionId, uuid)
        if (row != null) {
            repos.grants.upsert(
                row.copy(
                    appliedGroupId = group.id,
                    appliedRole = member.role,
                    memberAdded = memberAdded,
                    groupAdded = true,
                    appliedAt = clock(),
                ),
            )
        }
    }

    /**
     * 为领地 owner 记一条**零副作用**账本行（M-30）。
     *
     * 目的是让 `Lifecycle.selfHeal` 的"没有账本记录就补一条 GRANT"不再每次入服都重新排任务；
     * 撤销时这条行会被 `revoke` 直接删掉（`memberAdded`/`groupAdded` 全 false，不碰 Dominion）。
     */
    private fun recordOwnerNoop(task: SyncTask, member: Member) {
        if (repos.grants.find(task.townId, task.dominionId, member.uuid) != null) return
        repos.grants.upsert(
            Grant(
                townId = task.townId,
                dominionId = task.dominionId,
                uuid = member.uuid,
                preMember = false,
                preGroupId = 0,
                appliedGroupId = 0,
                appliedRole = member.role,
                memberAdded = false,
                groupAdded = false,
                appliedAt = clock(),
            ),
        )
    }

    private fun revoke(task: SyncTask) {
        val uuid = requireTarget(task)
        val grant = repos.grants.find(task.townId, task.dominionId, uuid) ?: return // I4：无账本不动
        val ref = gateway.resolveById(task.dominionId) ?: run {
            // 领地已不存在：账本直接作废
            repos.grants.delete(task.townId, task.dominionId, uuid)
            return
        }

        // 铁律：先移出组，再移出领地成员
        if (grant.groupAdded) {
            gateway.removeFromGroup(ref, grant.appliedGroupId, uuid)
        }
        val ourGroups = repos.groups.listByTown(task.townId).map { it.groupId }.toSet()
        if (grant.preGroupId != 0 && grant.preGroupId !in ourGroups) {
            gateway.putInGroup(ref, grant.preGroupId, uuid)
        }
        if (grant.memberAdded && !grant.preMember) {
            gateway.removeMemberFromDominion(ref, uuid)
        }
        repos.grants.delete(task.townId, task.dominionId, uuid)
    }

    private fun moveGroup(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        val member = repos.members.find(task.townId, uuid) ?: return
        // M-30 同源：owner 不在 Dominion 成员表里，`isMember` 恒为 false，
        // 不拦的话转让镇长时（原镇长降为 MEMBER）这条任务也会永久重试。
        if (ref.ownerUuid == uuid) return
        if (gateway.isMember(ref, uuid) == null) throw RetryLaterException("玩家不在 Dominion 缓存")
        if (gateway.isMember(ref, uuid) == false) throw RetryLaterException("玩家不在领地成员表")

        val group = gateway.ensureGroup(ref, member.role, groupName(task.townId, member.role), DominionConfig.flagsOf(member.role))
        val known = repos.groups.find(task.townId, member.role)
        repos.groups.upsert(
            TownGroup(task.townId, member.role, group.id, group.name, owned = known?.owned ?: group.created),
        )
        gateway.putInGroup(ref, group.id, uuid)
        repos.grants.find(task.townId, task.dominionId, uuid)?.let {
            repos.grants.updateApplied(task.townId, task.dominionId, uuid, group.id, member.role, clock())
        }
    }

    private fun ensureGroups(task: SyncTask) {
        val ref = requireRef(task)
        repos.groups.listByTown(task.townId).forEach { known ->
            val group = gateway.ensureGroup(ref, known.role, groupName(task.townId, known.role), DominionConfig.flagsOf(known.role))
            if (group.id != known.groupId) {
                repos.groups.upsert(known.copy(groupId = group.id, groupName = group.name))
            }
        }
    }

    private fun deleteGroup(task: SyncTask) {
        val payload = task.payload ?: throw DominionOpException("DELETE_GROUP 缺少 payload")
        val groupId = Regex("\"group_id\"\\s*:\\s*(\\d+)").find(payload)?.groupValues?.get(1)?.toInt()
            ?: throw DominionOpException("DELETE_GROUP payload 缺少 group_id: $payload")
        val ref = gateway.resolveById(task.dominionId)
        if (ref != null) {
            gateway.deleteGroupIfEmpty(ref, groupId)
        }
        repos.groups.listByTown(task.townId)
            .filter { it.groupId == groupId }
            .forEach { repos.groups.delete(task.townId, it.role) }
    }

    private fun setMemberFlag(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        val (flag, value) = PermsJson.decodeFlagPayload(task.payload)
            ?: throw DominionOpException("SET_MEMBER_FLAG payload 非法: ${task.payload}")
        gateway.setMemberFlag(ref, uuid, flag, value)
    }

    // ---- 小工具 ----

    private fun requireRef(task: SyncTask): DominionRef =
        gateway.resolveById(task.dominionId) ?: throw DominionOpException("领地 ${task.dominionId} 不存在")

    private fun requireTarget(task: SyncTask): UUID =
        task.targetUuid ?: throw DominionOpException("${task.action} 缺少目标玩家")

    private fun groupName(townId: Int, role: cn.trtown.model.TownRole): String =
        String.format(settings.groupNameFormat, townId, role.name)
}

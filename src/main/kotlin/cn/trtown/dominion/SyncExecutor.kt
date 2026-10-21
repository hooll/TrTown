package cn.trtown.dominion

import cn.trtown.model.SyncAction
import cn.trtown.model.SyncTask
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import cn.trtown.service.Repos
import cn.trtown.service.TownService
import java.util.UUID

/**
 * 同步执行器：消费 `trtown_sync_tasks`，调用 [DominionGateway] 施加/撤销权限。
 *
 * v1.25（PLAN §5.16）：Dominion 侧不再有组。新任务只有四种：
 * - `MEMBER_ADD`：只 `addMember`（MEMBER 预设为空时一个 flag 都不设）；
 * - `MEMBER_FLAGS`：按当前角色把全部 managed flag 设成期望值（含降级时设回 false）；
 * - `MEMBER_REMOVE`：`removeMember`；
 * - `TRANSFER_DOMINION`：`setOwner` + 成员表换位（新 owner 退出成员表、原镇长降级入表）。
 *
 * 旧动作（`GRANT` / `REVOKE` / `MOVE_GROUP` / `SET_MEMBER_FLAG`）保留解析并映射到新语义；
 * `ENSURE_GROUP` / `DELETE_GROUP` 是组模型的遗留，直接收尾（表已不再使用）。
 *
 * 设计要点：先有任务行再执行（I5）；每个动作幂等；失败按退避重试，超过上限置 DEAD；
 * 任务成功后如果该镇已解散且任务清空，触发解散收尾（M-5）。
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
     * 放在轮询里而不是新开常驻任务（PLAN §5.13 上限 2 个）。**M-33**：不必每秒扫一次 ——
     * 轮询是 1 s 一次，而 `DISBANDED` 的行永不删除、会长期累积，每秒把全表装配成 `Town` 对象只为筛掉 99% 不划算。
     * 30 s 一次足够（收尾本身幂等，晚半分钟没有任何业务影响）。
     */
    private fun sweepDisbanded() {
        val now = clock()
        if (lastSweepAt >= 0 && now - lastSweepAt < SWEEP_INTERVAL_MS) return
        lastSweepAt = now
        repos.towns.listDisbandedUnfinished().forEach { town ->
            runCatching { towns.finalizeDisband(town.id) }
                .onFailure { cn.trtown.platform.Log.failure("解散收尾自愈（镇 #${town.id}）", it) }
        }
    }

    fun execute(task: SyncTask) {
        try {
            when (task.action) {
                SyncAction.MEMBER_ADD, SyncAction.GRANT -> memberAdd(task)
                SyncAction.MEMBER_FLAGS, SyncAction.MOVE_GROUP -> applyMemberFlags(task)
                SyncAction.MEMBER_REMOVE, SyncAction.REVOKE -> memberRemove(task)
                SyncAction.SET_MEMBER_FLAG -> setMemberFlag(task)
                SyncAction.TRANSFER_DOMINION -> transferDominion(task)
                // 组模型遗留：表还在，但新代码不再读写；旧行直接收尾，避免永久 PENDING
                SyncAction.ENSURE_GROUP, SyncAction.DELETE_GROUP -> Unit
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

    /** 入镇 / 绑定时补加已有成员：只 addMember，再设 MEMBER 预设的 flag（默认为空） */
    private fun memberAdd(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        val member = repos.members.find(task.townId, uuid)
        if (member == null) {
            // 成员已退出，入镇任务作废（移除任务会由 leave/kick 入队）
            return
        }
        if (ref.ownerUuid == uuid) {
            // 旧 GRANT 指向 owner：Dominion 不允许把 owner 加成成员，直接收尾（M-30）。
            // 新 MEMBER_ADD 指向 owner 只可能出现在"转让任务还没跑完"时，必须重试。
            if (task.action == SyncAction.GRANT) return
            throw RetryLaterException("等待领地所有权转让完成")
        }
        gateway.ensureMember(ref, uuid)
        // PLAN §5.16.3：MEMBER_ADD 只加成员；MEMBER 预设默认为空。
        // 管理的 administration 由单独的 MEMBER_FLAGS 任务处理（避免 addMember 后缓存尚未刷新时
        // 立刻 setMemberFlag 触发一次可避免的 RetryLater）。
        DominionConfig.flagsOf(TownRole.MEMBER).filterValues { it }.keys.forEach { flag ->
            gateway.setMemberFlag(ref, uuid, flag, true)
        }
        // 管理：延后 5 秒再设 administration（addMember 后 Dominion 的成员缓存有数秒延迟，
        // 实测 2 秒仍会失败一次；5 秒能避开绝大多数无谓的 WARN/重试）
        if (member.role != TownRole.MEMBER) {
            repos.tasks.enqueue(
                task.townId, task.dominionId, SyncAction.MEMBER_FLAGS, uuid, null, clock() + 5_000,
            )
        }
    }

    /** 改角色：把全部 managed flag 设成当前角色的期望值（含降级时设回 false） */
    private fun applyMemberFlags(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        val member = repos.members.find(task.townId, uuid) ?: return
        if (ref.ownerUuid == uuid) return
        when (gateway.isMember(ref, uuid)) {
            null -> throw RetryLaterException("玩家不在 Dominion 缓存")
            false -> gateway.ensureMember(ref, uuid)
            true -> Unit
        }
        DominionConfig.expectedFlags(member.role).forEach { (flag, value) ->
            gateway.setMemberFlag(ref, uuid, flag, value)
        }
    }

    /** 退镇 / 被踢 / 解散：移除 Dominion 成员。解散时成员行已删，靠镇状态放行。 */
    private fun memberRemove(task: SyncTask) {
        val ref = gateway.resolveById(task.dominionId) ?: return
        val uuid = requireTarget(task)
        val town = repos.towns.findById(task.townId)
        // 离开后立刻重新入镇：旧移除任务不能把新身份删掉（解散时成员行本来就已清空，不适用）
        if (town?.state == TownState.ACTIVE && repos.members.find(task.townId, uuid) != null) return
        gateway.removeMemberFromDominion(ref, uuid)
    }

    /** 旧 `SET_MEMBER_FLAG`（个人覆写已删除）：仍执行，避免历史任务永久 PENDING */
    private fun setMemberFlag(task: SyncTask) {
        val ref = requireRef(task)
        val uuid = requireTarget(task)
        if (repos.members.find(task.townId, uuid) == null) return
        val (flag, value) = cn.trtown.model.PermsJson.decodeFlagPayload(task.payload)
            ?: throw DominionOpException("SET_MEMBER_FLAG payload 非法: ${task.payload}")
        gateway.setMemberFlag(ref, uuid, flag, value)
    }

    /**
     * 转让主领地（PLAN §5.16.7 第 5 条）。
     *
     * 顺序：`setOwner` → 新 owner 退出成员表 → 原镇长按降级后的角色入表并设 flag。
     * 任何一步失败都会重试；`setOwner` 本身幂等（owner 已是目标时直接返回）。
     */
    private fun transferDominion(task: SyncTask) {
        val ref = requireRef(task)
        val newOwner = requireTarget(task)
        val oldOwner = ref.ownerUuid
        if (oldOwner == newOwner) {
            DominionBindings.updateOwner(ref.id, newOwner)
            return
        }

        DominionBindings.beginInternalTransfer(ref.id)
        try {
            gateway.transferOwnership(ref, newOwner)
        } finally {
            DominionBindings.endInternalTransfer(ref.id)
        }
        DominionBindings.updateOwner(ref.id, newOwner)

        val after = gateway.resolveById(ref.id) ?: ref.copy(ownerUuid = newOwner)
        // 新 owner 不是成员：把残留的成员行删掉（幂等，本来没有就什么都不做）
        gateway.removeMemberFromDominion(after, newOwner)

        val oldMember = repos.members.find(task.townId, oldOwner)
        if (oldMember != null) {
            gateway.ensureMember(after, oldOwner)
            // 刚 addMember 时 Dominion 的成员缓存有数秒延迟；延后 5 秒交给 MEMBER_FLAGS 任务，
            // 失败也能按正常退避重试。
            repos.tasks.enqueue(
                task.townId, task.dominionId, SyncAction.MEMBER_FLAGS, oldOwner, null, clock() + 5_000,
            )
        }
    }

    // ---- 小工具 ----

    private fun requireRef(task: SyncTask): DominionRef =
        gateway.resolveById(task.dominionId) ?: throw DominionOpException("领地 ${task.dominionId} 不存在")

    private fun requireTarget(task: SyncTask): UUID =
        task.targetUuid ?: throw DominionOpException("${task.action} 缺少目标玩家")

    companion object {
        /** M-33：收尾扫描的间隔。轮询是 1 s，但自愈晚半分钟没有任何业务影响 */
        private const val SWEEP_INTERVAL_MS = 30_000L

        /** 跨实例共享（`Lifecycle` 每轮都 new 一个 SyncExecutor）；-1 = 还没扫过，保证启动后第一次一定扫 */
        @Volatile
        private var lastSweepAt = -1L

        /** 单测用：清掉节流状态，避免用例之间互相影响（companion 状态在整个 JVM 里共享） */
        @JvmSynthetic
        internal fun resetSweepThrottleForTest() {
            lastSweepAt = -1L
        }
    }
}

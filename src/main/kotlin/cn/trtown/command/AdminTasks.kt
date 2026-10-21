package cn.trtown.command

import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.DominionBindings
import cn.trtown.dominion.DominionConfig
import cn.trtown.dominion.DominionGateway
import cn.trtown.model.SyncState
import cn.trtown.model.TownState
import cn.trtown.service.Repos
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownRules
import cn.trtown.service.TownService

/** 运维命令的实现（命令树在 [CmdAdmin]） */
internal object AdminTasks {

    /**
     * 比对 DB 期望与 Dominion 实际；返回差异列表（空 = 一致）。
     *
     * v1.25 判据（PLAN §5.16.4）只有三项：
     * 1. 绑定：`dom.owner == towns.owner_uuid`；
     * 2. 成员：`trtown_members` 里**除 owner 外**每人都在 `dom.getMembers()`，反向多出来的也报；
     * 3. flag：非 owner 成员的 managed flag 等于其角色期望值（缺失按 false）。
     *
     * **M-36**：owner 在 Dominion 里不是 member，必须排除，否则生产上会误报
     * `eae3d10f-… 不是领地成员`。
     */
    fun verify(
        townName: String,
        repos: Repos = Repos.fromStorage(),
        gateway: DominionGateway = DominionApiGateway(),
    ): List<String> {
        val town = repos.towns.findByNameLower(townName)
            ?: throw TownException(TownError.TOWN_NOT_FOUND, townName)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND, town.name)
        val ref = gateway.resolveById(dominionId)
            ?: return listOf("领地 #$dominionId 在 Dominion 中不存在")

        val diffs = mutableListOf<String>()
        if (ref.ownerUuid != town.ownerUuid) {
            diffs += "领地 owner=${ref.ownerUuid} 与镇长=${town.ownerUuid} 不一致"
        }

        val members = repos.members.listByTown(town.id).filter { it.uuid != town.ownerUuid }
        val actualMembers = gateway.memberUuids(ref)
        if (actualMembers == null) {
            diffs += "读不到 Dominion 成员列表（领地 #$dominionId）"
        } else {
            val actual = actualMembers.filter { it != town.ownerUuid }.toSet()
            members.forEach { member ->
                if (member.uuid !in actual) {
                    diffs += "${member.uuid} 不是领地成员"
                    return@forEach
                }
                val actualFlags = gateway.memberFlags(ref, member.uuid)
                if (actualFlags == null) {
                    diffs += "${member.uuid} 不在 Dominion 缓存（稍后重试）"
                    return@forEach
                }
                DominionConfig.expectedFlags(member.role).forEach { (flag, expected) ->
                    val value = actualFlags[flag] ?: false
                    if (value != expected) {
                        diffs += "${member.uuid} flag $flag 实际=$value 期望=$expected（${member.role.name}）"
                    }
                }
            }
            val known = members.map { it.uuid }.toSet()
            (actual - known).forEach { diffs += "$it 是多余成员（不在 TrTown）" }
        }

        // M-10：DEAD 任务必须可见，否则权限可能残留在 Dominion 而账本已被删
        repos.tasks.listByTown(town.id)
            .filter { it.state == SyncState.DEAD }
            .forEach { diffs += "DEAD 任务 #${it.id} ${it.action}：${it.lastError ?: "未知错误"}（可 admin retry 重试）" }
        return diffs
    }

    /**
     * 按 [verify] 的同一套判据修复（PLAN §5.16.4 的 `--fix`）。
     *
     * 返回实际执行的修复项；空列表 = 本来就没有差异。命令语法是
     * `/trtown admin verify <镇名> fix`（TabooLib literal 不便于用 `--fix` 前缀）。
     */
    fun fix(
        townName: String,
        repos: Repos = Repos.fromStorage(),
        gateway: DominionGateway = DominionApiGateway(),
    ): List<String> {
        val town = repos.towns.findByNameLower(townName)
            ?: throw TownException(TownError.TOWN_NOT_FOUND, townName)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND, town.name)
        val ref = gateway.resolveById(dominionId)
            ?: return listOf("领地 #$dominionId 在 Dominion 中不存在，无法修复")

        val fixed = mutableListOf<String>()
        if (ref.ownerUuid != town.ownerUuid) {
            DominionBindings.beginInternalTransfer(ref.id)
            try {
                gateway.transferOwnership(ref, town.ownerUuid)
            } finally {
                DominionBindings.endInternalTransfer(ref.id)
            }
            fixed += "owner -> ${town.ownerUuid}"
        }

        val after = gateway.resolveById(ref.id) ?: ref
        val members = repos.members.listByTown(town.id).filter { it.uuid != town.ownerUuid }
        val actual = gateway.memberUuids(after)?.filter { it != town.ownerUuid }?.toSet().orEmpty()
        members.forEach { member ->
            if (member.uuid !in actual) {
                gateway.ensureMember(after, member.uuid)
                fixed += "add ${member.uuid}"
            }
            DominionConfig.expectedFlags(member.role).forEach { (flag, value) ->
                val current = gateway.memberFlags(after, member.uuid)?.get(flag) ?: false
                if (current != value) {
                    gateway.setMemberFlag(after, member.uuid, flag, value)
                    fixed += "flag ${member.uuid} $flag=$value"
                }
            }
        }
        val known = members.map { it.uuid }.toSet()
        (actual - known).forEach { extra ->
            gateway.removeMemberFromDominion(after, extra)
            fixed += "remove $extra"
        }
        return fixed
    }

    /** 强制解散：按镇长身份走同一套流程，审计记录为管理员操作 */
    fun forceDisband(townName: String) {
        val repos = Repos.fromStorage()
        val town = repos.towns.findByNameLower(townName)
            ?: throw TownException(TownError.TOWN_NOT_FOUND, townName)
        if (town.state == TownState.DISBANDED) throw TownException(TownError.TOWN_DISBANDED, town.name)
        repos.members.find(town.id, town.ownerUuid)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER, "镇长成员行缺失")
        TownService(repos, TownRules.fromSettings()).disband(town.ownerUuid)
        repos.audit.append(null, town.id, "force-disband", townName)
    }
}

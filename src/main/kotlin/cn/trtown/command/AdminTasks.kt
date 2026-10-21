package cn.trtown.command

import cn.trtown.dominion.DominionApiGateway
import cn.trtown.model.TownState
import cn.trtown.service.Repos
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownRules
import cn.trtown.service.TownService

/** 运维命令的实现（命令树在 [CmdAdmin]） */
internal object AdminTasks {

    /** 比对 DB 期望与 Dominion 实际；返回差异列表（空 = 一致） */
    fun verify(townName: String): List<String> {
        val repos = Repos.fromStorage()
        val town = repos.towns.findByNameLower(townName)
            ?: throw TownException(TownError.TOWN_NOT_FOUND, townName)
        val dominionId = town.dominionId ?: throw TownException(TownError.TOWN_NOT_BOUND, town.name)
        val gateway = DominionApiGateway()
        val ref = gateway.resolveById(dominionId)
            ?: throw TownException(TownError.DOMINION_NOT_FOUND, "#$dominionId")

        val diffs = mutableListOf<String>()
        val groups = repos.groups.listByTown(town.id).associateBy { it.role }
        if (groups.size != 3) diffs += "阶级组记录不足：${groups.keys.map { it.name }}"

        repos.members.listByTown(town.id).forEach { member ->
            when (gateway.isMember(ref, member.uuid)) {
                null -> diffs += "${member.uuid} 不在 Dominion 缓存（稍后重试）"
                false -> diffs += "${member.uuid} 不是领地成员"
                true -> {
                    val actual = gateway.memberGroupId(ref, member.uuid)
                    val expected = groups[member.role]?.groupId
                    if (expected != null && actual != expected) {
                        diffs += "${member.uuid} 组不符：实际=$actual 期望=$expected(${member.role.name})"
                    }
                }
            }
        }
        // M-10：DEAD 任务必须可见，否则权限可能残留在 Dominion 而账本已被删
        repos.tasks.listByTown(town.id)
            .filter { it.state == cn.trtown.model.SyncState.DEAD }
            .forEach { diffs += "DEAD 任务 #${it.id} ${it.action}：${it.lastError ?: "未知错误"}（可 admin retry 重试）" }
        return diffs
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

package cn.trtown.command

import cn.trtown.Settings
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.DominionConfig
import cn.trtown.dominion.FlagConfig
import cn.trtown.model.SyncAction
import cn.trtown.model.TownState
import cn.trtown.service.Repos
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.component.CommandBase

/** 管理员 / 运维命令（PLAN §5.6、§5.9） */
object AdminCommand {

    private const val PERM = "trtown.admin"

    fun attach(base: CommandBase) {
        base.literal("admin") {
            literal("reload") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    if (!sender.hasPermission(PERM)) return@execute Cmd.replyKey(sender, "common.no-permission")
                    Cmd.run(sender) {
                        Settings.reload()
                        val validated = FlagConfig.validate()
                        DominionConfig.install(validated)
                        validated.warnings.forEach { Cmd.reply(sender, "§e[配置] $it") }
                        Cmd.replyKey(sender, "admin.reload.done")
                    }
                }
            }

            literal("retry") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    if (!sender.hasPermission(PERM)) return@execute Cmd.replyKey(sender, "common.no-permission")
                    Cmd.run(sender) {
                        val count = Repos.fromStorage().tasks.resetDead()
                        Cmd.replyKey(sender, "admin.retry.done", count)
                    }
                }
            }

            literal("verify") {
                dynamic("town") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        if (!sender.hasPermission(PERM)) return@execute Cmd.replyKey(sender, "common.no-permission")
                        Cmd.run(sender) {
                            val diffs = verify(ctx["town"])
                            if (diffs.isEmpty()) {
                                Cmd.replyKey(sender, "admin.verify.ok", ctx["town"])
                            } else {
                                Cmd.replyKey(sender, "admin.verify.diff", diffs.size)
                                diffs.forEach { Cmd.reply(sender, "§7- §c$it") }
                            }
                        }
                    }
                }
            }

            literal("force-disband") {
                dynamic("town") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        if (!sender.hasPermission(PERM)) return@execute Cmd.replyKey(sender, "common.no-permission")
                        Cmd.run(sender) {
                            val repos = Repos.fromStorage()
                            val town = repos.towns.findByNameLower(ctx["town"])
                                ?: throw TownException(TownError.TOWN_NOT_FOUND, ctx["town"])
                            if (town.state == TownState.DISBANDED) throw TownException(TownError.TOWN_DISBANDED, town.name)
                            val owner = town.ownerUuid
                            val service = cn.trtown.service.TownService(repos, cn.trtown.service.TownRules.fromSettings())
                            // 管理员强制解散：直接按 owner 的身份走同一套流程（审计里记录操作者）
                            repos.members.find(town.id, owner)?.let {
                                service.disband(owner)
                                repos.audit.append(null, town.id, "force-disband", ctx["town"])
                                Cmd.replyKey(sender, "admin.force-disband.done", town.name)
                                return@run
                            }
                            throw TownException(TownError.TARGET_NOT_MEMBER, "镇长成员行缺失")
                        }
                    }
                }
            }
        }
    }

    /** 比对 DB 期望与 Dominion 实际；返回差异列表（空 = 一致） */
    private fun verify(townName: String): List<String> {
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
            val isMember = gateway.isMember(ref, member.uuid)
            when (isMember) {
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
}

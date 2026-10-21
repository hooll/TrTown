package cn.trtown.command

import cn.trtown.Settings
import cn.trtown.model.TownRole
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.subCommand
import taboolib.common.platform.command.suggestUncheck

/**
 * 玩家子命令（PLAN §5.6.2 结构映射表）。
 *
 * 约定（AC-46/AC-48）：
 * - 有可选参数的命令：**先 `execute`（无参分支）再挂 `dynamic`**，两条路径共用 `Cmds.doXxx`；
 * - 补全一律 `suggestUncheck`，lambda 里只做"一行转发"到 `Suggestions`，数据全部来自内存。
 */

// ---------------- 无参命令 ----------------

internal object CmdVersion {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> sender.sendMessage("§bTrTown §fv${MainCommand.versionText}") }
    }
}

internal object CmdHelp {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.sendHelp(sender) }
    }
}

internal object CmdLeave {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.player(sender) { player ->
                Cmd.run(sender) {
                    Cmds.membership().leave(player.uniqueId)
                    Cmd.replyKey(sender, "town.leave.success")
                }
            }
        }
    }
}

internal object CmdPrefix {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.player(sender) { player ->
                Cmd.run(sender) {
                    val town = Cmds.towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                    Cmd.replyKey(sender, "town.prefix.current", Cmds.prefix().render(town), town.name)
                }
            }
        }
    }
}

internal object CmdUnsetTerritory {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.player(sender) { player ->
                Cmd.run(sender) {
                    Cmds.territory().unsetterritory(player.uniqueId)
                    Cmd.replyKey(sender, "town.territory.unbound")
                }
            }
        }
    }
}

internal object CmdMembers {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.player(sender) { player ->
                Cmd.run(sender) {
                    val town = Cmds.towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                    val list = Cmds.repos().members.listByTown(town.id)
                    Cmd.replyKey(sender, "town.members.header", town.name, list.size)
                    list.forEach { m ->
                        Cmd.reply(sender, "§7- §f${cn.trtown.platform.Players.nameOf(m.uuid)} §8[§b${m.role.name}§8]")
                    }
                }
            }
        }
    }
}

internal object CmdList {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.run(sender) {
                val all = Cmds.repos().towns.listActive()
                Cmd.replyKey(sender, "town.list.header", all.size)
                all.forEach { t -> Cmd.reply(sender, "§7- §b${t.name} §8(${Cmds.repos().members.countByTown(t.id)})") }
            }
        }
    }
}

// ---------------- 带一个必填参数 ----------------

internal object CmdCreate {
    val command = subCommand {
        dynamic("name") {
            execute<ProxyCommandSender> { sender, ctx, _ ->
                val name = ctx["name"]
                Cmd.player(sender) { player ->
                    Cmd.run(sender) {
                        val town = Cmds.towns().create(player.uniqueId, name)
                        Cmd.replyKey(sender, "town.create.success", town.name)
                    }
                }
            }
        }
    }
}

internal object CmdInvite {
    val command = subCommand {
        dynamic("player") {
            suggestUncheck { Suggestions.playerNames(cn.trtown.service.SuggestHooks.onlineNames(), sender.name) }
            execute<ProxyCommandSender> { sender, ctx, _ ->
                Cmd.player(sender) { player ->
                    val target = Cmds.player(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                    Cmd.run(sender) {
                        Cmds.invites().invite(player.uniqueId, target)
                        Cmd.replyKey(sender, "town.invite.success", ctx["player"])
                    }
                }
            }
        }
    }
}

internal object CmdAccept {
    val command = subCommand {
        // 无参：0 条 → 提示；1 条 → 直接加入；≥2 条 → 只列镇名（PLAN §5.15 / R30）
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.doAccept(sender, null) }
        dynamic("town") {
            suggestUncheck { Suggestions.pendingInvites(SuggestCache.pendingInvites(Cmds.uuidOf(sender))) }
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doAccept(sender, ctx["town"]) }
        }
    }
}

internal object CmdDeny {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.doDeny(sender, null) }
        dynamic("town") {
            suggestUncheck { Suggestions.pendingInvites(SuggestCache.pendingInvites(Cmds.uuidOf(sender))) }
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doDeny(sender, ctx["town"]) }
        }
    }
}

internal object CmdKick {
    val command = subCommand {
        dynamic("player") {
            suggestUncheck { Suggestions.playerNames(cn.trtown.service.SuggestHooks.onlineNames(), sender.name) }
            execute<ProxyCommandSender> { sender, ctx, _ ->
                Cmd.player(sender) { player ->
                    val target = Cmds.player(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                    Cmd.run(sender) {
                        Cmds.membership().kick(player.uniqueId, target)
                        Cmd.replyKey(sender, "town.kick.success", ctx["player"])
                    }
                }
            }
        }
    }
}

internal object CmdTransfer {
    val command = subCommand {
        dynamic("player") {
            suggestUncheck { Suggestions.playerNames(cn.trtown.service.SuggestHooks.onlineNames(), sender.name) }
            // 已绑定主领地时：无 confirm 只出提示、零写入（PLAN §5.16.7）
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doTransfer(sender, ctx["player"], confirmed = false) }
            literal("confirm") {
                execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doTransfer(sender, ctx["player"], confirmed = true) }
            }
        }
    }
}

internal object CmdSetPrefix {
    val command = subCommand {
        dynamic("text") {
            execute<ProxyCommandSender> { sender, ctx, _ ->
                Cmd.player(sender) { player ->
                    Cmd.run(sender) {
                        val value = Cmds.prefix().set(player.uniqueId, ctx["text"])
                        if (value == null) Cmd.replyKey(sender, "town.prefix.reset")
                        else Cmd.replyKey(sender, "town.prefix.success", value)
                    }
                }
            }
        }
    }
}

// ---------------- 两级参数 ----------------

internal object CmdSetRole {
    val command = subCommand {
        dynamic("player") {
            suggestUncheck { Suggestions.playerNames(cn.trtown.service.SuggestHooks.onlineNames(), sender.name) }
            dynamic("role") {
                suggestUncheck { Suggestions.roles() }
                execute<ProxyCommandSender> { sender, ctx, _ ->
                    Cmd.player(sender) { player ->
                        val target = Cmds.player(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                        val role = runCatching { TownRole.valueOf(ctx["role"].uppercase()) }.getOrNull()
                            ?: return@player Cmd.replyKey(sender, "town.setrole.invalid", ctx["role"])
                        if (role == TownRole.MAYOR) return@player Cmd.replyKey(sender, "town.setrole.invalid", ctx["role"])
                        Cmd.run(sender) {
                            Cmds.towns().setrole(player.uniqueId, target, role)
                            Cmd.replyKey(sender, "town.setrole.success", ctx["player"], role.name)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- 可选参数：先 execute 再 dynamic（M-17） ----------------

internal object CmdSetTerritory {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.doSetTerritory(sender, null) }
        dynamic("name") {
            suggestUncheck { Suggestions.territories(SuggestCache.territories(Cmds.uuidOf(sender))) }
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doSetTerritory(sender, ctx["name"]) }
        }
    }
}

internal object CmdInfo {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.doInfo(sender, null) }
        dynamic("town") {
            suggestUncheck { Suggestions.towns(SuggestCache.towns()) }
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doInfo(sender, ctx["town"]) }
        }
    }
}

internal object CmdFlags {
    val command = subCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.doPerms(sender, null) }
        dynamic("player") {
            suggestUncheck { Suggestions.playerNames(cn.trtown.service.SuggestHooks.onlineNames(), sender.name) }
            execute<ProxyCommandSender> { sender, ctx, _ -> Cmds.doPerms(sender, ctx["player"]) }
        }
    }
}

// ---------------- 解散（二次确认） ----------------

internal object CmdDisband {
    val command = subCommand {
        // 有 executor → 子节点 `confirm` 自动 optional（框架语义）
        execute<ProxyCommandSender> { sender, _, _ ->
            Cmd.player(sender) { player ->
                Cmd.run(sender) {
                    val town = Cmds.towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                    Cmds.DisbandConfirm.request(player.uniqueId)
                    Cmd.replyKey(sender, "town.disband.confirm", town.name)
                }
            }
        }
        literal("confirm") {
            execute<ProxyCommandSender> { sender, _, _ ->
                Cmd.player(sender) { player ->
                    if (!Cmds.DisbandConfirm.consume(player.uniqueId)) {
                        return@player Cmd.replyKey(sender, "town.disband.no-confirm")
                    }
                    Cmd.run(sender) {
                        Cmds.towns().disband(player.uniqueId)
                        Cmd.replyKey(sender, "town.disband.success")
                    }
                }
            }
        }
    }
}

// ---------------- 运维 ----------------

internal object CmdAdmin {
    val command = subCommand {
        // `/trtown admin` 无参 → 打印用法（M-19）
        execute<ProxyCommandSender> { sender, _, _ ->
            listOf(
                "§b§lTrTown §7运维命令",
                "§7/trtown admin reload §8- 重载配置并重新校验",
                "§7/trtown admin retry §8- 重置 DEAD 同步任务",
                "§7/trtown admin verify <镇名> [fix] §8- 比对数据库与 Dominion（fix = 按同一判据修复）",
                "§7/trtown admin force-disband <镇名> §8- 强制解散",
            ).forEach { Cmd.reply(sender, it) }
        }

        literal("reload") {
            execute<ProxyCommandSender> { sender, _, _ ->
                if (!sender.hasPermission("trtown.admin")) return@execute Cmd.replyKey(sender, "common.no-permission")
                Cmd.run(sender) {
                    Settings.reload()
                    Cmds.repos()
                    cn.trtown.dominion.DominionConfig.install(cn.trtown.dominion.FlagConfig.validate())
                    Cmd.replyKey(sender, "admin.reload.done")
                }
            }
        }

        literal("retry") {
            execute<ProxyCommandSender> { sender, _, _ ->
                if (!sender.hasPermission("trtown.admin")) return@execute Cmd.replyKey(sender, "common.no-permission")
                Cmd.run(sender) {
                    val count = Cmds.repos().tasks.resetDead()
                    Cmd.replyKey(sender, "admin.retry.done", count)
                }
            }
        }

        literal("verify") {
            dynamic("town") {
                suggestUncheck { Suggestions.towns(SuggestCache.towns()) }
                execute<ProxyCommandSender> { sender, ctx, _ ->
                    if (!sender.hasPermission("trtown.admin")) return@execute Cmd.replyKey(sender, "common.no-permission")
                    Cmd.run(sender) {
                        val diffs = AdminTasks.verify(ctx["town"])
                        if (diffs.isEmpty()) Cmd.replyKey(sender, "admin.verify.ok", ctx["town"])
                        else {
                            Cmd.replyKey(sender, "admin.verify.diff", diffs.size)
                            diffs.forEach { Cmd.reply(sender, "§7- §c$it") }
                        }
                    }
                }
                literal("fix") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        if (!sender.hasPermission("trtown.admin")) return@execute Cmd.replyKey(sender, "common.no-permission")
                        Cmd.run(sender) {
                            val fixed = AdminTasks.fix(ctx["town"])
                            if (fixed.isEmpty()) Cmd.replyKey(sender, "admin.verify.ok", ctx["town"])
                            else {
                                Cmd.replyKey(sender, "admin.verify.fixed", fixed.size)
                                fixed.forEach { Cmd.reply(sender, "§7- §a$it") }
                            }
                        }
                    }
                }
            }
        }

        literal("force-disband") {
            dynamic("town") {
                suggestUncheck { Suggestions.towns(SuggestCache.towns()) }
                execute<ProxyCommandSender> { sender, ctx, _ ->
                    if (!sender.hasPermission("trtown.admin")) return@execute Cmd.replyKey(sender, "common.no-permission")
                    Cmd.run(sender) {
                        AdminTasks.forceDisband(ctx["town"])
                        Cmd.replyKey(sender, "admin.force-disband.done", ctx["town"])
                    }
                }
            }
        }
    }
}

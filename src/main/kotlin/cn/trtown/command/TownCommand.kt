package cn.trtown.command

import cn.trtown.VERSION
import cn.trtown.Settings
import cn.trtown.dominion.DominionConfig
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.service.InviteService
import cn.trtown.service.MembershipService
import cn.trtown.service.PermissionService
import cn.trtown.service.PrefixService
import cn.trtown.service.Repos
import cn.trtown.service.TerritoryService
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownRules
import cn.trtown.service.TownService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.command
import java.util.UUID

/** 玩家命令树（PLAN §5.6） */
object TownCommand {

    private fun repos(): Repos = Repos.fromStorage()
    private fun rules(): TownRules = TownRules.fromSettings()
    private fun towns(): TownService = TownService(repos(), rules())
    private fun invites(): InviteService = InviteService(repos(), rules())
    private fun membership(): MembershipService = MembershipService(repos())
    private fun territory(): TerritoryService =
        TerritoryService(repos(), cn.trtown.dominion.DominionApiGateway(), towns(), Settings.groupNameFormat)

    private fun prefix(): PrefixService = PrefixService(repos())

    private fun permission(): PermissionService = PermissionService(
        repos(), Settings.allowOverride, DominionConfig.current.editable.toSet(), Settings.flagAliases,
    )

    fun register() {
        registerPermissions()
        command("trtown", aliases = listOf("tt"), permission = "trtown.use") {
            literal("version") {
                execute<ProxyCommandSender> { sender, _, _ -> sender.sendMessage("§bTrTown §fv$VERSION") }
            }

            literal("help") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    listOf(
                        "§b§lTrTown §7帮助",
                        "§7/trtown create <镇名> §8- 建镇",
                        "§7/trtown invite|kick|transfer|setrole <玩家> §8- 成员管理",
                        "§7/trtown accept|deny <镇名> §8- 处理邀请",
                        "§7/trtown leave §8- 退出小镇（镇长需先转让）",
                        "§7/trtown setprefix <文本> §8- 改前缀（管理/镇长）",
                        "§7/trtown setterritory <领地> | unsetterritory §8- 绑定/解绑主领地（镇长）",
                        "§7/trtown info|members|list §8- 查看",
                        "§7/trtown perm|perms <玩家> ... §8- 个人权限（本服默认关闭）",
                        "§7/trtown disband [confirm] §8- 解散（镇长）",
                        "§7/trtown admin reload|retry|verify|force-disband §8- 运维",
                        "§c管理拥有领地大小/改名/子领地操作权，且 Dominion 无撤销——管理只给信得过的人",
                    ).forEach { Cmd.reply(sender, it) }
                }
            }

            literal("create") {
                dynamic("name") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        val name = ctx["name"]
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val town = towns().create(player.uniqueId, name)
                                Cmd.replyKey(sender, "town.create.success", town.name)
                            }
                        }
                    }
                }
            }

            literal("invite") {
                dynamic("player") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            val target = findPlayer(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                            Cmd.run(sender) {
                                invites().invite(player.uniqueId, target)
                                Cmd.replyKey(sender, "town.invite.success", ctx["player"])
                            }
                        }
                    }
                }
            }

            literal("accept") {
                dynamic("town") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val invite = findInvite(player.uniqueId, ctx["town"])
                                invites().accept(player.uniqueId, invite.townId)
                                Cmd.replyKey(sender, "town.accept.success", ctx["town"])
                            }
                        }
                    }
                }
            }

            literal("deny") {
                dynamic("town") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val invite = findInvite(player.uniqueId, ctx["town"])
                                invites().deny(player.uniqueId, invite.townId)
                                Cmd.replyKey(sender, "town.deny.success", ctx["town"])
                            }
                        }
                    }
                }
            }

            literal("leave") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.player(sender) { player ->
                        Cmd.run(sender) {
                            membership().leave(player.uniqueId)
                            Cmd.replyKey(sender, "town.leave.success")
                        }
                    }
                }
            }

            literal("kick") {
                dynamic("player") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            val target = findPlayer(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                            Cmd.run(sender) {
                                membership().kick(player.uniqueId, target)
                                Cmd.replyKey(sender, "town.kick.success", ctx["player"])
                            }
                        }
                    }
                }
            }

            literal("transfer") {
                dynamic("player") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            val target = findPlayer(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                            Cmd.run(sender) {
                                towns().transfer(player.uniqueId, target)
                                Cmd.replyKey(sender, "town.transfer.success", ctx["player"])
                            }
                        }
                    }
                }
            }

            literal("setrole") {
                dynamic("player") {
                    dynamic("role") {
                        execute<ProxyCommandSender> { sender, ctx, _ ->
                            Cmd.player(sender) { player ->
                                val target = findPlayer(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                                val role = runCatching { TownRole.valueOf(ctx["role"].uppercase()) }.getOrNull()
                                    ?: return@player Cmd.replyKey(sender, "town.setrole.invalid", ctx["role"])
                                if (role == TownRole.MAYOR) return@player Cmd.replyKey(sender, "town.setrole.invalid", ctx["role"])
                                Cmd.run(sender) {
                                    towns().setrole(player.uniqueId, target, role)
                                    Cmd.replyKey(sender, "town.setrole.success", ctx["player"], role.name)
                                }
                            }
                        }
                    }
                }
            }

            literal("setprefix") {
                dynamic("text") {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val value = prefix().set(player.uniqueId, ctx["text"])
                                if (value == null) Cmd.replyKey(sender, "town.prefix.reset") else Cmd.replyKey(sender, "town.prefix.success", value)
                            }
                        }
                    }
                }
            }

            literal("prefix") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.player(sender) { player ->
                        Cmd.run(sender) {
                            val town = towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                            Cmd.replyKey(sender, "town.prefix.current", prefix().render(town), town.name)
                        }
                    }
                }
            }

            literal("setterritory") {
                dynamic("name", optional = true) {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val town = territory().setterritory(player.uniqueId, ctx.get("name"))
                                Cmd.replyKey(sender, "town.territory.bound", town.dominionId ?: 0)
                            }
                        }
                    }
                }
            }

            literal("unsetterritory") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.player(sender) { player ->
                        Cmd.run(sender) {
                            territory().unsetterritory(player.uniqueId)
                            Cmd.replyKey(sender, "town.territory.unbound")
                        }
                    }
                }
            }

            literal("info") {
                dynamic("town", optional = true) {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            Cmd.run(sender) {
                                val town = ctx.get("town")?.let { name ->
                                    repos().towns.findByNameLower(name) ?: throw TownException(TownError.TOWN_NOT_FOUND, name)
                                } ?: towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                                val count = repos().members.countByTown(town.id)
                                Cmd.replyKey(sender, "town.info.line", town.name, count, Settings.maxMembers,
                                    town.dominionId?.toString() ?: "-", prefix().render(town))
                            }
                        }
                    }
                }
            }

            literal("members") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.player(sender) { player ->
                        Cmd.run(sender) {
                            val town = towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                            val list = repos().members.listByTown(town.id)
                            Cmd.replyKey(sender, "town.members.header", town.name, list.size)
                            list.forEach { m ->
                                Cmd.reply(sender, "§7- §f${Bukkit.getOfflinePlayer(m.uuid).name ?: m.uuid} §8[§b${m.role.name}§8]")
                            }
                        }
                    }
                }
            }

            literal("list") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.run(sender) {
                        val all = repos().towns.listActive()
                        Cmd.replyKey(sender, "town.list.header", all.size)
                        all.forEach { t -> Cmd.reply(sender, "§7- §b${t.name} §8(${repos().members.countByTown(t.id)})") }
                    }
                }
            }

            literal("perm") {
                dynamic("player") {
                    dynamic("flag") {
                        dynamic("value") {
                            execute<ProxyCommandSender> { sender, ctx, _ ->
                                Cmd.player(sender) { player ->
                                    val target = findPlayer(ctx["player"]) ?: return@player Cmd.replyKey(sender, "common.player-offline")
                                    val raw = ctx["value"]
                                    Cmd.run(sender) {
                                        if (raw.equals("reset", true)) {
                                            permission().reset(player.uniqueId, target)
                                            Cmd.replyKey(sender, "town.perm.reset", ctx["player"])
                                        } else {
                                            val value = when (raw.lowercase()) {
                                                "on", "true", "开" -> true
                                                "off", "false", "关" -> false
                                                else -> throw TownException(TownError.FLAG_INVALID, raw)
                                            }
                                            permission().set(player.uniqueId, target, ctx["flag"], value)
                                            Cmd.replyKey(sender, "town.perm.success", ctx["player"], ctx["flag"], value)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            literal("perms") {
                dynamic("player", optional = true) {
                    execute<ProxyCommandSender> { sender, ctx, _ ->
                        Cmd.player(sender) { player ->
                            val target = ctx.get("player")?.let { findPlayer(it) } ?: player.uniqueId
                            Cmd.run(sender) {
                                val rows = permission().describe(target)
                                Cmd.replyKey(sender, "town.perms.header")
                                rows.forEach { (flag, base, effective) ->
                                    Cmd.reply(sender, "§7- §f$flag §8默认=$base §7生效=$effective")
                                }
                            }
                        }
                    }
                }
            }

            literal("disband") {
                literal("confirm") {
                    execute<ProxyCommandSender> { sender, _, _ ->
                        Cmd.player(sender) { player ->
                            if (!DisbandConfirm.consume(player.uniqueId)) {
                                return@player Cmd.replyKey(sender, "town.disband.no-confirm")
                            }
                            Cmd.run(sender) {
                                towns().disband(player.uniqueId)
                                Cmd.replyKey(sender, "town.disband.success")
                            }
                        }
                    }
                }
                execute<ProxyCommandSender> { sender, _, _ ->
                    Cmd.player(sender) { player ->
                        Cmd.run(sender) {
                            val town = towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                            DisbandConfirm.request(player.uniqueId)
                            Cmd.replyKey(sender, "town.disband.confirm", town.name)
                        }
                    }
                }
            }

            AdminCommand.attach(this)
        }
    }

    private fun findPlayer(name: String): UUID? = Bukkit.getPlayerExact(name)?.uniqueId

    /**
     * M-12：命令权限节点必须在运行时注册，否则未在 plugin.yml 声明的节点对非 OP 默认 false，
     * 玩家连 `/trtown` 都进不去。
     */
    private fun registerPermissions() {
        val manager = Bukkit.getPluginManager()
        manager.addPermission(Permission("trtown.use", null, PermissionDefault.TRUE))
        manager.addPermission(Permission("trtown.admin", null, PermissionDefault.OP))
    }

    private fun findInvite(uuid: UUID, townName: String): cn.trtown.model.Invite {
        val pending = repos().invites.listPendingByTarget(uuid)
        return pending.firstOrNull { invite ->
            repos().towns.findById(invite.townId)?.name.equals(townName, ignoreCase = true)
        } ?: throw TownException(TownError.INVITE_NOT_FOUND, townName)
    }

    /** 解散二次确认（10 秒内有效） */
    private object DisbandConfirm {
        private val pending = mutableMapOf<UUID, Long>()

        fun request(uuid: UUID) {
            pending[uuid] = System.currentTimeMillis()
        }

        fun consume(uuid: UUID): Boolean {
            val ts = pending.remove(uuid) ?: return false
            return System.currentTimeMillis() - ts <= Settings.disbandConfirmSeconds * 1000L
        }
    }
}

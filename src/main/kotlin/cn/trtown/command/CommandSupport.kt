package cn.trtown.command

import cn.trtown.Settings
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.dominion.DominionConfig
import cn.trtown.model.Invite
import cn.trtown.platform.Players
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
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandContext
import java.util.UUID

/**
 * 命令层的共享依赖与"父无参 / 子带参"共用的实现（PLAN §5.6.1、§5.6.2 硬约束 2）。
 *
 * 只做装配，不含命令树定义 —— 命令树在 [MainCommand] 与各 `CmdXxx`。
 */
internal object Cmds {

    fun repos(): Repos = Repos.fromStorage()

    fun rules(): TownRules = TownRules.fromSettings()

    fun towns(): TownService = TownService(repos(), rules())

    fun invites(): InviteService = InviteService(repos(), rules())

    fun membership(): MembershipService = MembershipService(repos())

    fun territory(): TerritoryService =
        TerritoryService(repos(), DominionApiGateway(), towns(), Settings.groupNameFormat)

    fun prefix(): PrefixService = PrefixService(repos())

    fun permission(): PermissionService = PermissionService(
        repos(),
        Settings.allowOverride,
        DominionConfig.current.editable.toSet(),
        Settings.flagAliases,
    )

    /** 玩家名 → UUID；不在线返回 null */
    fun player(name: String): UUID? = Players.find(name)

    /**
     * 发送者 UUID（补全用）。
     *
     * 补全 lambda 里拿不到玩家对象，而 `castSafely<Player>()` 因泛型擦除会在调用点抛异常，
     * 所以统一委托给 [Cmd.uuidOf]（唯一允许碰 Bukkit 类型的地方）。
     */
    fun uuidOf(sender: ProxyCommandSender): UUID? = Cmd.uuidOf(sender)

    /** 可选参数只用 `getOrNull`（M-18：`get` 在父执行器里取不到子节点参数并会抛） */
    fun opt(ctx: CommandContext<ProxyCommandSender>, id: String): String? = ctx.getOrNull(id)

    fun inviteFor(uuid: UUID, townName: String): Invite {
        val pending = repos().invites.listPendingByTarget(uuid)
        return pending.firstOrNull { invite ->
            repos().towns.findById(invite.townId)?.name.equals(townName, ignoreCase = true)
        } ?: throw TownException(TownError.INVITE_NOT_FOUND, townName)
    }

    // ---- 三条"无参可用"命令的共用实现（M-17） ----

    fun doSetTerritory(sender: ProxyCommandSender, name: String?) {
        Cmd.player(sender) { player ->
            Cmd.run(sender) {
                val town = territory().setterritory(player.uniqueId, name)
                Cmd.replyKey(sender, "town.territory.bound", town.dominionId ?: 0)
            }
        }
    }

    fun doInfo(sender: ProxyCommandSender, townName: String?) {
        Cmd.player(sender) { player ->
            Cmd.run(sender) {
                val town = townName?.let { name ->
                    repos().towns.findByNameLower(name) ?: throw TownException(TownError.TOWN_NOT_FOUND, name)
                } ?: towns().townOf(player.uniqueId) ?: throw TownException(TownError.NOT_IN_TOWN)
                Cmd.replyKey(
                    sender, "town.info.line", town.name, repos().members.countByTown(town.id),
                    Settings.maxMembers, town.dominionId?.toString() ?: "-", prefix().render(town),
                )
            }
        }
    }

    fun doPerms(sender: ProxyCommandSender, playerName: String?) {
        Cmd.player(sender) { player ->
            val target = playerName?.let { player(it) } ?: player.uniqueId
            Cmd.run(sender) {
                val rows = permission().describe(target)
                Cmd.replyKey(sender, "town.perms.header")
                rows.forEach { (flag, base, effective) ->
                    Cmd.reply(sender, "§7- §f$flag §8默认=$base §7生效=$effective")
                }
            }
        }
    }

    /** `perm` 缺参的统一用法提示（M-22：perm / player / flag 三层共用，禁止文案漂移） */
    fun usagePerm(sender: ProxyCommandSender) {
        Cmd.reply(sender, "§c用法：/trtown perm <玩家> <flag> <on|off|reset>")
    }

    // ---- 帮助（`/trtown`、`/tt`、`/trtown help` 共用同一实现，禁止文案漂移） ----

    fun sendHelp(sender: ProxyCommandSender) {
        HELP_LINES.forEach { Cmd.reply(sender, it) }
    }

    private val HELP_LINES = listOf(
        "§b§lTrTown §7帮助",
        "§7/trtown create <镇名> §8- 建镇",
        "§7/trtown invite|kick|transfer|setrole <玩家> §8- 成员管理",
        "§7/trtown accept|deny <镇名> §8- 处理邀请",
        "§7/trtown leave §8- 退出小镇（镇长需先转让）",
        "§7/trtown setprefix <文本> §8- 改前缀（管理/镇长）",
        "§7/trtown setterritory [领地] | unsetterritory §8- 绑定/解绑主领地（镇长）",
        "§7/trtown info [镇名] | members | list §8- 查看",
        "§7/trtown perm|perms [玩家] ... §8- 个人权限（本服默认关闭）",
        "§7/trtown disband [confirm] §8- 解散（镇长）",
        "§7/trtown admin reload|retry|verify|force-disband §8- 运维",
        "§c管理拥有领地大小/改名/子领地操作权，且 Dominion 无撤销——管理只给信得过的人",
    )

    /** 解散二次确认（10 秒内有效） */
    object DisbandConfirm {
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

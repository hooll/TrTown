package cn.trtown.command

import cn.trtown.Settings
import cn.trtown.dominion.DominionApiGateway
import cn.trtown.model.Invite
import cn.trtown.platform.Players
import cn.trtown.service.InviteChoice
import cn.trtown.service.InviteService
import cn.trtown.service.MembershipService
import cn.trtown.service.PendingInvite
import cn.trtown.service.PrefixService
import cn.trtown.service.Repos
import cn.trtown.service.TerritoryService
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownRules
import cn.trtown.service.TownService
import cn.trtown.service.chooseInvite
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
        TerritoryService(repos(), DominionApiGateway(), towns())

    fun prefix(): PrefixService = PrefixService(repos())

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
            // 位置必须在玩家自己的 region 线程上取；Cmd.run 之后就是异步线程了（Folia 纪律）
            val at = Players.positionOf(player)
            Cmd.run(sender) {
                val town = territory().setterritory(player.uniqueId, name, at)
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

    /** `/tt perms`：展示当前角色在 Dominion 里的 flag 实况（v1.25 保留只读展示） */
    fun doPerms(sender: ProxyCommandSender, playerName: String?) {
        Cmd.player(sender) { player ->
            val target = playerName?.let { player(it) } ?: player.uniqueId
            Cmd.run(sender) {
                val rows = territory().describeFlags(target)
                Cmd.replyKey(sender, "town.perms.flags-header")
                rows.forEach { row ->
                    Cmd.reply(
                        sender,
                        "§7- §f${row.flag} §8角色应有=${row.expected} §7Dominion 实际=${row.actual ?: "未绑定"}",
                    )
                }
            }
        }
    }

    /**
     * `/tt transfer <玩家> [confirm]`（PLAN §5.16.7）。
     *
     * 未绑定主领地 → 直接转让（保持旧行为）；已绑定 → 第一次只出确认提示、零写入，
     * 10 秒内带 `confirm` 才真正落库并排 `TRANSFER_DOMINION`。
     */
    fun doTransfer(sender: ProxyCommandSender, targetName: String, confirmed: Boolean) {
        Cmd.player(sender) { player ->
            val target = player(targetName) ?: return@player Cmd.replyKey(sender, "common.player-offline", targetName)
            Cmd.run(sender) {
                val preview = towns().previewTransfer(player.uniqueId, target)
                val dominionId = preview.dominionId
                if (dominionId == null) {
                    // 未绑定领地：没有"领地跟着走"的问题，保持旧的一次确认行为
                    towns().transfer(player.uniqueId, target)
                    Cmd.replyKey(sender, "town.transfer.success", targetName)
                    return@run
                }
                if (!confirmed) {
                    TransferConfirm.request(player.uniqueId, target)
                    val dominionName = territory().dominionName(dominionId)
                    Cmd.replyKey(sender, "town.transfer.confirm", preview.town.name, dominionName, targetName)
                    return@run
                }
                if (!TransferConfirm.consume(player.uniqueId, target)) {
                    Cmd.replyKey(sender, "town.transfer.no-confirm")
                    return@run
                }
                towns().transfer(player.uniqueId, target)
                Cmd.replyKey(sender, "town.transfer.success", targetName)
            }
        }
    }

    // ---- 邀请处理（PLAN §5.15 / M-25；无参时自动选唯一一条，≥2 绝不自动选 —— R30） ----

    fun doAccept(sender: ProxyCommandSender, townName: String?) {
        Cmd.player(sender) { player ->
            Cmd.run(sender) {
                if (townName != null) {
                    // 带参路径行为完全不变
                    val invite = inviteFor(player.uniqueId, townName)
                    invites().accept(player.uniqueId, invite.townId)
                    Cmd.replyKey(sender, "town.accept.success", townName)
                } else {
                    when (val choice = chooseInvite(invites().pendingOf(player.uniqueId))) {
                        is InviteChoice.None -> Cmd.replyKey(sender, "town.accept.none")
                        is InviteChoice.Single -> {
                            invites().accept(player.uniqueId, choice.invite.townId)
                            Cmd.replyKey(sender, "town.accept.success", choice.invite.townName)
                        }
                        // 选错镇要退镇再进，代价远大于多敲四个字 → 只列出来
                        is InviteChoice.Ambiguous ->
                            Cmd.replyKey(sender, "town.accept.multi", choice.invites.size, townNames(choice.invites))
                    }
                }
            }
        }
    }

    fun doDeny(sender: ProxyCommandSender, townName: String?) {
        Cmd.player(sender) { player ->
            Cmd.run(sender) {
                if (townName != null) {
                    val invite = inviteFor(player.uniqueId, townName)
                    invites().deny(player.uniqueId, invite.townId)
                    Cmd.replyKey(sender, "town.deny.success", townName)
                } else {
                    when (val choice = chooseInvite(invites().pendingOf(player.uniqueId))) {
                        is InviteChoice.None -> Cmd.replyKey(sender, "town.deny.none")
                        is InviteChoice.Single -> {
                            invites().deny(player.uniqueId, choice.invite.townId)
                            Cmd.replyKey(sender, "town.deny.success", choice.invite.townName)
                        }
                        is InviteChoice.Ambiguous ->
                            Cmd.replyKey(sender, "town.deny.multi", choice.invites.size, townNames(choice.invites))
                    }
                }
            }
        }
    }

    /** 镇名列表的展示格式（`、` 连接，与消息键 `{1}` 对应） */
    private fun townNames(pending: List<PendingInvite>): String =
        pending.joinToString("、") { it.townName }

    // ---- 帮助（`/trtown`、`/tt`、`/trtown help` 共用同一实现，禁止文案漂移） ----

    fun sendHelp(sender: ProxyCommandSender) {
        HELP_LINES.forEach { Cmd.reply(sender, it) }
    }

    private val HELP_LINES = listOf(
        "§b§lTrTown §7帮助",
        "§7/trtown create <镇名> §8- 建镇",
        "§7/trtown invite|kick|setrole <玩家> §8- 成员管理",
        "§7/trtown transfer <玩家> [confirm] §8- 转让镇长（已绑领地时需二次确认，领地一起转）",
        "§7/trtown accept|deny [镇名] §8- 处理邀请（不带镇名＝只有一条时直接处理）",
        "§7/trtown leave §8- 退出小镇（镇长需先转让）",
        "§7/trtown setprefix <文本> §8- 改前缀（管理/镇长）",
        "§7/trtown setterritory [领地] | unsetterritory §8- 绑定/解绑主领地（无参＝脚下领地）",
        "§7/trtown info [镇名] | members | list §8- 查看",
        "§7/trtown perms [玩家] §8- 查看角色在 Dominion 里的 flag 实况",
        "§7/trtown disband [confirm] §8- 解散（镇长）",
        "§7/trtown admin reload|retry|verify|force-disband §8- 运维",
        "§c管理拥有领地大小/改名/子领地操作权，且 Dominion 无撤销——管理只给信得过的人",
        "§c在 Dominion GUI 里转让主领地会被拦截：请用 /trtown transfer 同时转让小镇",
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

    /**
     * 领地转让二次确认（PLAN §5.16.7）。
     *
     * 键是 `(操作者, 目标)`：确认必须针对同一个人，否则 `/tt transfer B` 之后
     * `/tt transfer C confirm` 会把镇转给 C。
     */
    object TransferConfirm {
        private val pending = mutableMapOf<Pair<UUID, UUID>, Long>()

        fun request(actor: UUID, target: UUID) {
            pending[actor to target] = System.currentTimeMillis()
        }

        fun consume(actor: UUID, target: UUID): Boolean {
            val ts = pending.remove(actor to target) ?: return false
            return System.currentTimeMillis() - ts <= Settings.transferConfirmSeconds * 1000L
        }
    }
}

package cn.trtown.command

import cn.trtown.VERSION
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandBody
import taboolib.common.platform.command.CommandHeader
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.mainCommand

/**
 * 根命令（PLAN §5.6.2，注解式）。
 *
 * `@CommandHeader` 由 TabooLib 的 `SimpleCommandRegister` 在 ENABLE 阶段扫描注册；
 * `@CommandBody` 的字段名即子命令名，`permission` 会自动注册成权限节点
 * （因此不再需要手写 `registerPermissions()`）。
 */
@CommandHeader(
    name = "trtown",
    aliases = ["tt"],
    permission = "trtown.use",
    permissionDefault = PermissionDefault.TRUE,
)
object MainCommand {

    /**
     * 根命令：`/trtown` 与 `/tt` 无参（PLAN §5.18.1 / AC-56 / AC-60）。
     *
     * 默认打开主菜单；`gui.enabled: false` 或发送者不是玩家时回退文字帮助。
     */
    @CommandBody
    val main = mainCommand {
        execute<ProxyCommandSender> { sender, _, _ -> Cmds.openMainMenu(sender) }
    }

    @CommandBody
    val version = CmdVersion.command

    @CommandBody
    val help = CmdHelp.command

    @CommandBody
    val create = CmdCreate.command

    @CommandBody
    val invite = CmdInvite.command

    @CommandBody
    val accept = CmdAccept.command

    @CommandBody
    val deny = CmdDeny.command

    @CommandBody
    val leave = CmdLeave.command

    @CommandBody
    val kick = CmdKick.command

    @CommandBody
    val transfer = CmdTransfer.command

    @CommandBody
    val setrole = CmdSetRole.command

    @CommandBody
    val setprefix = CmdSetPrefix.command

    @CommandBody
    val prefix = CmdPrefix.command

    @CommandBody
    val setterritory = CmdSetTerritory.command

    @CommandBody
    val unsetterritory = CmdUnsetTerritory.command

    @CommandBody
    val settp = CmdSetTp.command

    @CommandBody
    val tp = CmdTp.command

    @CommandBody
    val info = CmdInfo.command

    @CommandBody
    val members = CmdMembers.command

    @CommandBody
    val list = CmdList.command

    @CommandBody
    val disband = CmdDisband.command

    @CommandBody(permission = "trtown.admin", permissionDefault = PermissionDefault.OP)
    val admin = CmdAdmin.command

    /** 供 `/trtown version` 使用 */
    internal val versionText: String = VERSION
}

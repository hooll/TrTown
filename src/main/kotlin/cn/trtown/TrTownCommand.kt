package cn.trtown

import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.command

/**
 * 玩家命令树骨架。
 *
 * 目前只有 `version`；S4 起并入 `command/TownCommand.kt` 的完整命令树（AUDIT S-3）。
 */
object TrTownCommand {

    fun register() {
        command("trtown", aliases = listOf("tt")) {
            literal("version") {
                execute<ProxyCommandSender> { sender, _, _ ->
                    sender.sendMessage("§bTrTown §fv$VERSION")
                }
            }
        }
    }
}

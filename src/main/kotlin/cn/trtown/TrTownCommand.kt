package cn.trtown

import org.bukkit.Bukkit
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.command
import taboolib.common.platform.function.info

/**
 * 玩家命令树。S0 阶段只有 `version` 子命令，用于验证 TabooLib `command()` DSL 注册与补全。
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
        val registered = Bukkit.getPluginCommand("trtown") != null
        info("[S0] command() DSL 注册结果: ${if (registered) "OK" else "FAIL(plugin.yml 未注册 trtown)"}")
    }
}

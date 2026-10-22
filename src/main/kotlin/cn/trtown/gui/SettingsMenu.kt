package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * 设置菜单（PLAN §5.18.4）：查看/修改聊天前缀。
 *
 * 前缀写入走"关菜单 → 聊天栏输入 → 转 `/tt setprefix`"（§5.18.5），
 * 校验、冷却、权限全部留在 [cn.trtown.command.CmdSetPrefix] 那条链路上。
 */
internal object SettingsMenu {

    private data class View(val townName: String, val prefix: String)

    fun open(player: Player) {
        if (!Settings.guiEnabled) {
            GuiBase.notice(player, Messages.get("gui.disabled"))
            return
        }
        val uuid = player.uniqueId
        GuiBase.fetch(player, load = { load(uuid) }, then = { view -> render(player, view) })
    }

    private fun load(uuid: UUID): View? {
        val repos = Cmds.repos()
        val member = repos.members.findByUuid(uuid) ?: return null
        val town = repos.towns.findById(member.townId) ?: return null
        return View(town.name, Cmds.prefix().render(town))
    }

    private fun render(player: Player, view: View) {
        val grid = GuiBase.grid(3)
        grid[0][0] = 'B'
        grid[0][8] = 'X'
        grid[1][4] = 'P'

        GuiBase.open(player, Messages.get("gui.settings.title"), GuiBase.lines(grid)) {
            button(
                'P',
                GuiBase.item(XMaterial.NAME_TAG, "gui.settings.prefix", "gui.settings.prefix-lore", view.prefix),
            ) {
                ChatInput.begin(player, ChatInput.Kind.PREFIX)
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }
}

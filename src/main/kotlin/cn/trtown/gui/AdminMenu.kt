package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Messages
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial

/**
 * 管理员菜单（PLAN §5.18.4）：`verify` / `retry` / `force-disband` / `reload`。
 *
 * 只在主菜单里对 `trtown.admin` 显示；这里再校验一次，避免有人靠子菜单入口绕过。
 * 需要镇名的两项走聊天输入（§5.18.5），其余直接转发 `/tt admin ...`。
 */
internal object AdminMenu {

    fun open(player: Player) {
        if (!Settings.guiEnabled) {
            GuiBase.notice(player, Messages.get("gui.disabled"))
            return
        }
        if (!player.hasPermission("trtown.admin")) {
            GuiBase.notice(player, Messages.get("common.no-permission"))
            return
        }

        val grid = GuiBase.grid(3)
        grid[0][0] = 'B'
        grid[0][8] = 'X'
        grid[1][1] = 'R'
        grid[1][3] = 'T'
        grid[1][5] = 'V'
        grid[1][7] = 'F'

        GuiBase.open(player, Messages.get("gui.admin.title"), GuiBase.lines(grid)) {
            button('R', GuiBase.item(XMaterial.BOOK, "gui.admin.reload", "gui.admin.reload-lore")) {
                GuiActions.dispatch(player, "tt admin reload")
            }
            button('T', GuiBase.item(XMaterial.CLOCK, "gui.admin.retry", "gui.admin.retry-lore")) {
                GuiActions.dispatch(player, "tt admin retry")
            }
            button('V', GuiBase.item(XMaterial.MAP, "gui.admin.verify", "gui.admin.verify-lore")) {
                ChatInput.begin(player, ChatInput.Kind.VERIFY)
            }
            button('F', GuiBase.item(XMaterial.BARRIER, "gui.admin.forcedisband", "gui.admin.forcedisband-lore")) {
                ChatInput.begin(player, ChatInput.Kind.FORCE_DISBAND)
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }
}

package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.platform.Players
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * 邀请菜单（PLAN §5.18.4）：左键接受、右键拒绝。
 *
 * 接受/拒绝都转发给 `/tt accept <镇名>` / `/tt deny <镇名>`，
 * 复用命令层的选择逻辑（多镇邀请的歧义处理也一致）。
 */
internal object InviteMenu {

    private data class Row(
        val townName: String,
        val inviter: String,
        val remainingSeconds: Long,
    )

    private const val PER_ROW = 7
    private const val CAPACITY = PER_ROW * 3

    fun open(player: Player) {
        if (!Settings.guiEnabled) {
            GuiBase.notice(player, Messages.get("gui.disabled"))
            return
        }
        val uuid = player.uniqueId
        GuiBase.fetch(player, load = { load(uuid) }, then = { rows -> render(player, rows) })
    }

    private fun load(uuid: UUID): List<Row> {
        val repos = Cmds.repos()
        val now = System.currentTimeMillis()
        return repos.invites.listPendingByTarget(uuid).mapNotNull { invite ->
            val town = repos.towns.findById(invite.townId) ?: return@mapNotNull null
            Row(
                townName = town.name,
                inviter = Players.nameOf(invite.inviterUuid),
                remainingSeconds = ((invite.expiresAt - now) / 1000L).coerceAtLeast(0L),
            )
        }
    }

    private fun slotChar(index: Int): Char = if (index < 10) '0' + index else 'a' + (index - 10)

    private fun slotAt(index: Int): Int = 10 + (index / PER_ROW) * 9 + (index % PER_ROW)

    private fun render(player: Player, rows: List<Row>) {
        val grid = GuiBase.grid(6)
        grid[5][0] = 'B'
        grid[5][8] = 'X'
        if (rows.isEmpty()) {
            grid[3][4] = 'E'
        } else {
            rows.take(CAPACITY).forEachIndexed { index, _ ->
                val slot = slotAt(index)
                grid[slot / 9][slot % 9] = slotChar(index)
            }
        }

        GuiBase.open(player, GuiBase.text("gui.invites.title", rows.size), GuiBase.lines(grid)) {
            if (rows.isEmpty()) {
                button('E', GuiBase.item(XMaterial.PAPER, "gui.invites.empty")) { player.closeInventory() }
            }
            rows.take(CAPACITY).forEachIndexed { index, row ->
                val item = GuiBase.item(
                    XMaterial.PAPER, "gui.invites.item", "gui.invites.item-lore",
                    row.townName, row.inviter, row.remainingSeconds,
                )
                button(slotChar(index), GuiBase.glow(item)) { event ->
                    val click = event.clickEventOrNull()?.click
                    if (click == ClickType.RIGHT || click == ClickType.SHIFT_RIGHT) {
                        GuiActions.dispatch(player, "tt deny ${row.townName}")
                    } else {
                        GuiActions.dispatch(player, "tt accept ${row.townName}")
                    }
                }
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }
}

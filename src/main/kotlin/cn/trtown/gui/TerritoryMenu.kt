package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * "领地与传送"菜单（PLAN §5.18.4）。
 *
 * 领地名/大小/坐标与传送点**现场读 Dominion**（`TerritoryService.dominionName/boundsOf/tpLocationOf`），
 * 本地不存副本（§5.17.1）。按钮直接转发 `/tt settp` 与 `/tt tp`，跨镇不进 GUI。
 */
internal object TerritoryMenu {

    private data class View(
        val dominionId: Int?,
        val dominionName: String,
        val bounds: String,
        val tp: String,
    )

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
        val dominionId = town.dominionId
            ?: return View(null, "", Messages.get("gui.common.none"), Messages.get("gui.common.not-set"))
        val territory = Cmds.territory()
        val name = runCatching { territory.dominionName(dominionId) }.getOrDefault("")
        val bounds = runCatching { territory.boundsOf(dominionId) }.getOrNull()?.let {
            "${it.x1}, ${it.y1}, ${it.z1} → ${it.x2}, ${it.y2}, ${it.z2}（${it.square} 格）"
        } ?: Messages.get("gui.common.none")
        val tp = runCatching { territory.tpLocationOf(dominionId) }.getOrNull()
            ?.let { "${it.x.toInt()}, ${it.y.toInt()}, ${it.z.toInt()}" }
            ?: Messages.get("gui.common.not-set")
        return View(dominionId, name, bounds, tp)
    }

    private fun render(player: Player, view: View) {
        val grid = GuiBase.grid(3)
        grid[0][0] = 'B'
        grid[0][8] = 'X'
        if (view.dominionId == null) {
            grid[1][4] = 'N'
        } else {
            grid[1][4] = 'O'
            grid[2][1] = 'S'
            grid[2][7] = 'P'
        }

        GuiBase.open(player, Messages.get("gui.territory.title"), GuiBase.lines(grid)) {
            if (view.dominionId == null) {
                button('N', GuiBase.item(XMaterial.BARRIER, "gui.territory.none", "gui.territory.none-lore")) {
                    player.closeInventory()
                }
            } else {
                button(
                    'O',
                    GuiBase.item(
                        XMaterial.GRASS_BLOCK, "gui.territory.info", "gui.territory.info-lore",
                        // M-47：玩家可见处显示领地名，读不到才回退内部编号
                        view.dominionName.ifBlank { "#${view.dominionId}" }, view.tp,
                    ),
                ) {
                    GuiBase.notice(
                        player,
                        "${GuiBase.text("gui.territory.info", view.dominionName)} §7| §f${view.bounds}",
                    )
                }
                button('S', GuiBase.item(XMaterial.ENDER_EYE, "gui.territory.settp", "gui.territory.settp-lore")) {
                    GuiActions.dispatch(player, "tt settp")
                }
                button('P', GuiBase.item(XMaterial.ENDER_PEARL, "gui.territory.tp", "gui.territory.tp-lore")) {
                    GuiActions.dispatch(player, "tt tp")
                }
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }
}

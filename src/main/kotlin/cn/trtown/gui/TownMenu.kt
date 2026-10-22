package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.platform.Players
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * "我的小镇"菜单（PLAN §5.18.4）。
 *
 * 展示镇名、前缀、成员数/上限、领地与传送点；按钮为传送、解绑领地（二次确认）、解散（二次确认）。
 * 传送点坐标**现场读 Dominion**（`TerritoryService.tpLocationOf`），不在本地留副本（§5.17.1）。
 */
internal object TownMenu {

    private data class View(
        val name: String,
        val mayor: String,
        val prefix: String,
        val members: Int,
        val maxMembers: Int,
        val dominionId: Int?,
        val dominionName: String?,
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
        val territory = Cmds.territory()
        val dominionId = town.dominionId
        val dominionName = dominionId?.let { runCatching { territory.dominionName(it) }.getOrNull() }
        val tp = dominionId?.let { runCatching { territory.tpLocationOf(it) }.getOrNull() }
        return View(
            name = town.name,
            mayor = Players.nameOf(town.ownerUuid),
            prefix = Cmds.prefix().render(town),
            members = repos.members.countByTown(town.id),
            maxMembers = Settings.maxMembers,
            dominionId = dominionId,
            dominionName = dominionName,
            tp = tp?.let { "${it.x.toInt()}, ${it.y.toInt()}, ${it.z.toInt()}" } ?: Messages.get("gui.common.not-set"),
        )
    }

    private fun render(player: Player, view: View) {
        val grid = GuiBase.grid(4)
        grid[1][4] = 'O'
        grid[2][2] = 'P'
        grid[2][4] = 'U'
        grid[2][6] = 'D'
        grid[3][0] = 'B'
        grid[3][8] = 'X'

        val townTitle = GuiBase.text("gui.town.title", view.name)
        GuiBase.open(player, townTitle, GuiBase.lines(grid)) {
            button(
                'O',
                GuiBase.item(
                    XMaterial.BELL, "gui.town.info", "gui.town.info-lore",
                    view.mayor, view.members, view.maxMembers,
                    view.dominionName ?: Messages.get("gui.common.none"),
                    view.tp, view.prefix,
                ),
            ) { player.closeInventory() }
            button('P', GuiBase.item(XMaterial.ENDER_PEARL, "gui.town.tp", "gui.town.tp-lore")) {
                GuiActions.dispatch(player, "tt tp")
            }
            if (view.dominionId == null) {
                button('U', GuiBase.item(XMaterial.BARRIER, "gui.town.unbind", "gui.common.none")) {
                    player.closeInventory()
                }
            } else {
                button('U', GuiBase.item(XMaterial.LEAD, "gui.town.unbind", "gui.town.unbind-lore")) {
                    GuiBase.confirm(
                        player, "gui.town.unbind",
                        onYes = {
                            GuiActions.dispatch(player, "tt unsetterritory")
                        },
                        onNo = { TownMenu.open(player) },
                    )
                }
            }
            button('D', GuiBase.item(XMaterial.TNT, "gui.town.disband", "gui.town.disband-lore")) {
                GuiBase.confirm(
                    player, "gui.town.disband",
                    onYes = {
                        GuiActions.submit(player) {
                            Cmds.towns().disband(player.uniqueId)
                            GuiBase.notice(player, Messages.get("gui.done.disband"))
                        }
                    },
                    onNo = { TownMenu.open(player) },
                )
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }
}

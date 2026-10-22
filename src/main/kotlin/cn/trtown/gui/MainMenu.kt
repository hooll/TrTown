package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.platform.Players
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * 主菜单（PLAN §5.18.4）：`/tt` 无参打开，3×9。
 *
 * 取数在异步线程（[GuiBase.fetch]），铺格子与开店回玩家 region 线程；
 * 点击只做"关菜单 / 打开子菜单 / 转发命令"，不在这里碰数据库。
 */
internal object MainMenu {

    /** 一次取数的只读快照 */
    private data class View(
        val townName: String?,
        val members: Int,
        val online: Int,
        val invites: Int,
    )

    fun open(player: Player) {
        if (!Settings.guiEnabled) {
            GuiBase.notice(player, Messages.get("gui.disabled"))
            return
        }
        val uuid = player.uniqueId
        val admin = player.hasPermission("trtown.admin")
        GuiBase.fetch(player, load = { load(uuid) }, then = { view -> render(player, view, admin) })
    }

    private fun load(uuid: UUID): View {
        val repos = Cmds.repos()
        val invites = repos.invites.listPendingByTarget(uuid).size
        val member = repos.members.findByUuid(uuid)
        val town = member?.let { repos.towns.findById(it.townId) }
            ?: return View(townName = null, members = 0, online = 0, invites = invites)
        val members = repos.members.listByTown(town.id)
        return View(
            townName = town.name,
            members = members.size,
            online = members.count { Players.isOnline(it.uuid) },
            invites = invites,
        )
    }

    private fun render(player: Player, view: View, admin: Boolean) {
        val grid = GuiBase.grid(3)
        grid[1][1] = 'T'
        grid[1][4] = 'I'
        grid[2][8] = 'X'
        if (view.townName != null) {
            grid[1][3] = 'M'
            grid[1][5] = 'R'
            grid[1][7] = 'S'
        }
        if (admin) grid[2][0] = 'A'

        val title = GuiBase.text("gui.title.main", view.townName ?: Messages.get("gui.no-town"))
        GuiBase.open(player, title, GuiBase.lines(grid)) {
            if (view.townName == null) {
                button('T', GuiBase.item(XMaterial.BELL, "gui.main.town", "gui.main.no-town-lore")) {
                    player.closeInventory()
                }
            } else {
                button('T', GuiBase.item(XMaterial.BELL, "gui.main.town", "gui.main.town-lore")) {
                    TownMenu.open(player)
                }
                button(
                    'M',
                    GuiBase.item(XMaterial.PLAYER_HEAD, "gui.main.members", "gui.main.members-lore", view.members, view.online),
                ) { MembersMenu.open(player) }
                button('R', GuiBase.item(XMaterial.GRASS_BLOCK, "gui.main.territory", "gui.main.territory-lore")) {
                    TerritoryMenu.open(player)
                }
                button('S', GuiBase.item(XMaterial.COMPARATOR, "gui.main.settings", "gui.main.settings-lore")) {
                    SettingsMenu.open(player)
                }
            }
            val inviteItem = GuiBase.item(XMaterial.PAPER, "gui.main.invites", "gui.main.invites-lore", view.invites)
            button('I', if (view.invites > 0) GuiBase.glow(inviteItem) else inviteItem) {
                InviteMenu.open(player)
            }
            if (admin) {
                button('A', GuiBase.item(XMaterial.COMMAND_BLOCK, "gui.main.admin", "gui.main.admin-lore")) {
                    AdminMenu.open(player)
                }
            }
            closeButton('X', player)
        }
    }
}

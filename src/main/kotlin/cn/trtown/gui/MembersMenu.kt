package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.model.Member
import cn.trtown.model.TownRole
import cn.trtown.platform.Players
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * 成员列表菜单 + 单个成员的操作菜单（PLAN §5.18.4）。
 *
 * 头颅规则：在线用玩家头颅（亮），离线用骷髅头（灰）；点击进入"踢出 / 设为 MANAGER /
 * 降为 MEMBER"，无权限的按钮置灰并在 lore 里说明（AUDIT 23.7「管理员菜单可见性」）。
 */
internal object MembersMenu {

    /** 列表行：一次取数得到的只读快照 */
    private data class Row(
        val uuid: UUID,
        val name: String,
        val role: TownRole,
        val online: Boolean,
    )

    private data class View(
        val townName: String,
        val actor: UUID,
        val actorRole: TownRole,
        val rows: List<Row>,
    )

    /** 一屏最多 21 个成员（3 行 × 7 格） */
    private const val PER_ROW = 7
    private const val CAPACITY = PER_ROW * 3

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
        val actor = repos.members.findByUuid(uuid) ?: return null
        val town = repos.towns.findById(actor.townId) ?: return null
        val rows = repos.members.listByTown(town.id)
            .sortedWith(compareBy<Member>({ roleWeight(it.role) }, { Players.nameOf(it.uuid) }))
            .map { Row(it.uuid, Players.nameOf(it.uuid), it.role, Players.isOnline(it.uuid)) }
        return View(town.name, uuid, actor.role, rows)
    }

    private fun roleWeight(role: TownRole): Int = when (role) {
        TownRole.MAYOR -> 0
        TownRole.MANAGER -> 1
        TownRole.MEMBER -> 2
    }

    private fun slotChar(index: Int): Char = if (index < 10) '0' + index else 'a' + (index - 10)

    private fun slotAt(index: Int): Int = 10 + (index / PER_ROW) * 9 + (index % PER_ROW)

    private fun render(player: Player, view: View) {
        val grid = GuiBase.grid(6)
        grid[5][0] = 'B'
        grid[5][8] = 'X'
        view.rows.take(CAPACITY).forEachIndexed { index, _ ->
            val slot = slotAt(index)
            grid[slot / 9][slot % 9] = slotChar(index)
        }

        val title = GuiBase.text("gui.members.title", view.townName, view.rows.size)
        GuiBase.open(player, title, GuiBase.lines(grid)) {
            view.rows.take(CAPACITY).forEachIndexed { index, row ->
                val state = Messages.get(if (row.online) "gui.members.online" else "gui.members.offline")
                val head = if (row.online) {
                    GuiBase.head(row.name, "gui.members.head", "gui.members.head-lore", row.name, row.role.name, state)
                } else {
                    GuiBase.item(
                        XMaterial.SKELETON_SKULL, "gui.members.head", "gui.members.head-lore",
                        row.name, row.role.name, state,
                    )
                }
                button(slotChar(index), head) { MemberActionMenu.open(player, view, row) }
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }

    /**
     * 单个成员的操作菜单。
     *
     * 权限判定与服务层口径一致（`MembershipService.kick` / `TownService.setrole`）：
     * 这里只提前置灰，真正的校验仍在命令与服务层。
     */
    private object MemberActionMenu {

        fun open(player: Player, view: View, row: Row) {
            val canKick = view.actorRole.isManagerOrAbove &&
                row.uuid != view.actor &&
                row.role != TownRole.MAYOR &&
                (view.actorRole == TownRole.MAYOR || row.role == TownRole.MEMBER)
            val canPromote = view.actorRole == TownRole.MAYOR && row.role == TownRole.MEMBER
            val canDemote = view.actorRole == TownRole.MAYOR && row.role == TownRole.MANAGER

            val grid = GuiBase.grid(3)
            grid[1][2] = 'K'
            grid[1][4] = 'P'
            grid[1][6] = 'D'
            grid[2][0] = 'B'
            grid[2][8] = 'X'

            GuiBase.open(player, GuiBase.text("gui.member.action.title", row.name), GuiBase.lines(grid)) {
                if (canKick) {
                    button('K', GuiBase.item(XMaterial.IRON_SWORD, "gui.member.kick", "gui.member.kick-lore")) {
                        GuiActions.dispatch(player, "tt kick ${row.name}")
                    }
                } else {
                    button('K', greyed(XMaterial.IRON_SWORD, "gui.member.kick")) { player.closeInventory() }
                }
                if (canPromote) {
                    button('P', GuiBase.item(XMaterial.GOLDEN_HELMET, "gui.member.promote", "gui.member.promote-lore")) {
                        GuiActions.dispatch(player, "tt setrole ${row.name} manager")
                    }
                } else {
                    button('P', greyed(XMaterial.GOLDEN_HELMET, "gui.member.promote")) { player.closeInventory() }
                }
                if (canDemote) {
                    button('D', GuiBase.item(XMaterial.LEATHER_HELMET, "gui.member.demote", "gui.member.demote-lore")) {
                        GuiActions.dispatch(player, "tt setrole ${row.name} member")
                    }
                } else {
                    button('D', greyed(XMaterial.LEATHER_HELMET, "gui.member.demote")) { player.closeInventory() }
                }
                backButton('B', player) { MembersMenu.open(player) }
                closeButton('X', player)
            }
        }

        private fun greyed(material: XMaterial, nameKey: String) =
            GuiBase.item(material, nameKey, "gui.member.disabled-lore")
    }
}

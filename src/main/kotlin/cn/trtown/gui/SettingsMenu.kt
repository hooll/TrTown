package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.model.TownRole
import cn.trtown.platform.PlatformScheduler
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial
import java.util.UUID

/**
 * 设置菜单：查看/修改聊天前缀。
 *
 * 镇长与管理进入铁砧快捷编辑器；成员只读（能看到已保存效果，但修改入口禁用）。
 * 编辑器入口与保存都会再次核对 `gui.enabled` / `prefix.enabled` / 镇内角色。
 */
internal object SettingsMenu {

    private data class View(
        val townName: String,
        val rendered: String,
        val role: TownRole,
        val canEdit: Boolean,
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
        val view = Cmds.prefix().readView(uuid)
        return View(
            townName = view.town.name,
            rendered = view.rendered,
            role = view.role,
            canEdit = view.role.isManagerOrAbove,
        )
    }

    private fun render(player: Player, view: View) {
        val grid = GuiBase.grid(3)
        grid[0][0] = 'B'
        grid[0][8] = 'X'
        grid[1][4] = 'P'

        val roleLabel = Messages.get("gui.prefix.role-${view.role.name.lowercase()}")
        val prefixItem = if (view.canEdit) {
            GuiBase.item(
                XMaterial.NAME_TAG,
                "gui.prefix-entry",
                "gui.prefix-entry-lore",
                view.rendered,
                roleLabel,
            )
        } else {
            GuiBase.item(
                XMaterial.NAME_TAG,
                "gui.prefix-entry",
                "gui.prefix-entry-readonly-lore",
                view.rendered,
                roleLabel,
            )
        }

        GuiBase.open(player, Messages.get("gui.settings.title"), GuiBase.lines(grid)) {
            button('P', prefixItem) {
                if (!Settings.guiEnabled) {
                    GuiBase.notice(player, Messages.get("gui.disabled"))
                    return@button
                }
                if (!view.canEdit) {
                    GuiBase.notice(player, Messages.get("gui.prefix.readonly"))
                    return@button
                }
                openEditor(player)
            }
            backButton('B', player) { MainMenu.open(player) }
            closeButton('X', player)
        }
    }

    /** 异步取目标（成员/镇/开关校验），回玩家线程建草稿并打开编辑主页。 */
    private fun openEditor(player: Player) {
        if (!Settings.prefixEnabled) {
            GuiBase.notice(player, Messages.get("gui.prefix.disabled"))
            return
        }
        val accepted = PlatformScheduler.runPrefixIo {
            val result = runCatching { Cmds.prefix().openTarget(player.uniqueId) }
            PlatformScheduler.runForPlayer(player) {
                if (!player.isOnline) return@runForPlayer
                result.fold(
                    onSuccess = { target ->
                        val session = PrefixEditSessions.begin(player.uniqueId, target)
                        PrefixEditSessions.put(player.uniqueId, session)
                        if (session.unreadable) {
                            PrefixEditSessions.notice(player, Messages.get("gui.prefix.unreadable"))
                        }
                        PrefixEditorMenu.open(player, session)
                    },
                    onFailure = { ex ->
                        val text = if (ex is cn.trtown.service.TownException) {
                            when (ex.error) {
                                cn.trtown.service.TownError.PREFIX_DISABLED -> Messages.get("gui.prefix.disabled")
                                cn.trtown.service.TownError.NO_PERMISSION,
                                cn.trtown.service.TownError.NOT_IN_TOWN -> Messages.get("gui.prefix.readonly")
                                else -> ex.message ?: Messages.get("gui.disabled")
                            }
                        } else {
                            Messages.get("gui.disabled")
                        }
                        GuiBase.notice(player, text)
                    },
                )
            }
        }
        if (!accepted) GuiBase.notice(player, Messages.get("gui.prefix.save-failed"))
    }
}

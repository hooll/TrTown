package cn.trtown.gui

import cn.trtown.command.Messages
import taboolib.library.xseries.XMaterial

/**
 * 前缀编辑主页（27 格）。
 *
 * 4 预览 / 10 改字 / 12 选字配色 / 14 保存 / 16 恢复默认 / 22 返回 / 26 关闭。
 * 所有点击只改内存草稿；保存才写库。
 */
internal object PrefixEditorMenu {

    private const val PREVIEW = 4
    private const val TEXT = 10
    private const val STYLE = 12
    private const val SAVE = 14
    private const val RESET = 16
    private const val BACK = 22
    private const val CLOSE = 26

    fun open(player: org.bukkit.entity.Player, session: PrefixEditSession) {
        if (!PrefixEditSessions.isCurrent(session)) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        val window = ++session.windowId
        GuiBase.openSlots(
            player = player,
            title = Messages.get("gui.prefix.editor-title"),
            rows = 3,
            guard = { session.windowId == window && PrefixEditSessions.isCurrent(session) },
            onOpened = { session.inventory = it },
        ) {
            setSlot(PREVIEW, PrefixMenuItems.preview(session))

            buttonAt(TEXT, GuiBase.item(XMaterial.PAPER, "gui.prefix.text", "gui.prefix.text-lore")) {
                if (session.windowId != window) return@buttonAt
                if (session.saving) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.saving"))
                    return@buttonAt
                }
                PrefixInputMenu.openText(player, session)
            }

            buttonAt(STYLE, GuiBase.item(XMaterial.NAME_TAG, "gui.prefix.style", "gui.prefix.style-lore")) {
                if (session.windowId != window) return@buttonAt
                if (session.saving) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.saving"))
                    return@buttonAt
                }
                if (session.chars.isEmpty()) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.empty"))
                } else {
                    PrefixEditSessions.show(player, session, PrefixPage.STYLE)
                }
            }

            val saveItem = if (session.saving) {
                GuiBase.item(XMaterial.EMERALD_BLOCK, "gui.prefix.saving")
            } else {
                GuiBase.item(XMaterial.EMERALD_BLOCK, "gui.prefix.save", "gui.prefix.save-lore")
            }
            buttonAt(SAVE, saveItem) {
                if (session.windowId != window) return@buttonAt
                PrefixEditSessions.save(player, session)
            }

            buttonAt(RESET, GuiBase.item(XMaterial.REDSTONE_TORCH, "gui.prefix.reset", "gui.prefix.reset-lore")) {
                if (session.windowId != window) return@buttonAt
                if (session.saving) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.saving"))
                    return@buttonAt
                }
                session.markReset()
                PrefixEditorMenu.open(player, session)
            }

            buttonAt(BACK, GuiBase.item(XMaterial.ARROW, "gui.prefix.discard", "gui.prefix.discard-lore")) {
                if (session.windowId != window) return@buttonAt
                PrefixEditSessions.discard(player, session) { SettingsMenu.open(player) }
            }

            buttonAt(CLOSE, GuiBase.item(XMaterial.BARRIER, "gui.prefix.close")) {
                if (session.windowId != window) return@buttonAt
                PrefixEditSessions.discard(player, session)
            }

            // Esc 与关闭一致：放弃未保存草稿
            onClose(true, false) { event ->
                if (session.windowId != window) return@onClose
                if (session.inventory !== event.inventory) return@onClose
                PrefixEditSessions.remove(session)
                session.closed = true
                session.windowId++
            }
        }
    }
}

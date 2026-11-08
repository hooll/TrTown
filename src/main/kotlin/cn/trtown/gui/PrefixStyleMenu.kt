package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.util.PrefixStyleEditor
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial

/**
 * 样式页（54 格）：逐字选区、16 色、HEX、格式开关与渐变入口。
 *
 * 字符以 Unicode code point 为单位，emoji 代理对不会被拆开；
 * 颜色/格式只改选区，选区外全部保持。
 */
internal object PrefixStyleMenu {

    private const val PER_PAGE = 18
    private const val CHAR_START = 9
    private const val COLOR_START = 27
    private const val HEX = 43
    private const val CLEAR = 44
    private const val PREV = 45
    private const val NEXT = 46
    private const val ALL = 47
    private const val GRADIENT = 48
    private const val BACK = 49
    private const val BOLD = 50
    private const val STRIKETHROUGH = 51
    private const val UNDERLINE = 52
    private const val ITALIC = 53

    private val COLORS = "0123456789abcdef"

    fun open(player: Player, session: PrefixEditSession) {
        if (!PrefixEditSessions.isCurrent(session)) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        val window = ++session.windowId
        val pages = maxOf(1, (session.chars.size + PER_PAGE - 1) / PER_PAGE)
        session.page = session.page.coerceIn(0, pages - 1)

        GuiBase.openSlots(
            player = player,
            title = Messages.get("gui.prefix.style-title"),
            rows = 6,
            guard = { session.windowId == window && PrefixEditSessions.isCurrent(session) },
            onOpened = { session.inventory = it },
        ) {
            setSlot(0, GuiBase.item(XMaterial.BOOK, "gui.prefix.style-help", "gui.prefix.style-help-lore"))
            setSlot(4, PrefixMenuItems.preview(session))
            setSlot(8, PrefixMenuItems.selectionInfo(session))

            // 9-26：每页 18 个字格
            for (local in 0 until PER_PAGE) {
                val index = session.page * PER_PAGE + local
                if (index < session.chars.size) {
                    buttonAt(CHAR_START + local, PrefixMenuItems.character(session, index)) {
                        if (session.windowId != window) return@buttonAt
                        session.clickCharacter(index)
                        PrefixStyleMenu.open(player, session)
                    }
                }
            }

            // 27-42：传统 16 色
            COLORS.forEachIndexed { offset, code ->
                val slot = COLOR_START + offset
                val disabled = !session.canColor || session.selection() == null
                buttonAt(slot, PrefixMenuItems.colorSwatch(code, disabled)) {
                    if (session.windowId != window) return@buttonAt
                    if (!session.canColor) {
                        PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
                        return@buttonAt
                    }
                    val range = session.selection()
                    if (range == null) {
                        PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
                        return@buttonAt
                    }
                    val chars = PrefixStyleEditor.withColor(session.chars, range, "&$code", session.shellColor)
                    if (chars == null) {
                        PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
                    } else {
                        session.applyChars(chars)
                        PrefixStyleMenu.open(player, session)
                    }
                }
            }

            buttonAt(HEX, GuiBase.item(XMaterial.NETHER_STAR, "gui.prefix.hex", "gui.prefix.hex-lore")) {
                if (session.windowId != window) return@buttonAt
                if (!session.canColor) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
                } else if (session.selection() == null) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
                } else {
                    PrefixInputMenu.openHex(player, session, PrefixInputMode.HEX_RANGE)
                }
            }

            buttonAt(CLEAR, GuiBase.item(XMaterial.BARRIER, "gui.prefix.clear-style", "gui.prefix.clear-style-lore")) {
                if (session.windowId != window) return@buttonAt
                if (!session.canColor) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
                    return@buttonAt
                }
                val range = session.selection()
                if (range == null) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
                    return@buttonAt
                }
                session.applyChars(PrefixStyleEditor.cleared(session.chars, range, session.shellColor))
                PrefixStyleMenu.open(player, session)
            }

            if (session.page > 0) {
                buttonAt(PREV, GuiBase.item(XMaterial.ARROW, "gui.prefix.prev")) {
                    if (session.windowId != window) return@buttonAt
                    session.page--
                    PrefixStyleMenu.open(player, session)
                }
            } else {
                setSlot(PREV, GuiBase.item(XMaterial.GRAY_STAINED_GLASS_PANE, "gui.prefix.prev"))
            }

            if (session.page < pages - 1) {
                buttonAt(NEXT, GuiBase.item(XMaterial.ARROW, "gui.prefix.next")) {
                    if (session.windowId != window) return@buttonAt
                    session.page++
                    PrefixStyleMenu.open(player, session)
                }
            } else {
                setSlot(NEXT, GuiBase.item(XMaterial.GRAY_STAINED_GLASS_PANE, "gui.prefix.next"))
            }

            buttonAt(ALL, GuiBase.item(XMaterial.CHEST, "gui.prefix.all", "gui.prefix.all-lore")) {
                if (session.windowId != window) return@buttonAt
                session.selectAll()
                PrefixStyleMenu.open(player, session)
            }

            buttonAt(GRADIENT, GuiBase.item(XMaterial.FIREWORK_STAR, "gui.prefix.gradient", "gui.prefix.gradient-lore")) {
                if (session.windowId != window) return@buttonAt
                if (!session.canColor) {
                    PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
                } else {
                    PrefixEditSessions.show(player, session, PrefixPage.GRADIENT)
                }
            }

            buttonAt(BACK, GuiBase.item(XMaterial.ARROW, "gui.prefix.back", "gui.prefix.back-lore")) {
                if (session.windowId != window) return@buttonAt
                PrefixEditSessions.show(player, session, PrefixPage.EDITOR)
            }

            formatButton(this, session, window, BOLD, 'l', player)
            formatButton(this, session, window, STRIKETHROUGH, 'm', player)
            formatButton(this, session, window, UNDERLINE, 'n', player)
            formatButton(this, session, window, ITALIC, 'o', player)

            onClose(true, false) { event ->
                if (session.windowId != window) return@onClose
                if (session.inventory !== event.inventory) return@onClose
                // 样式页 Esc / 关窗：回编辑主页，保留草稿
                PrefixEditSessions.switchTo(player, session, PrefixPage.EDITOR)
            }
        }
    }

    private fun formatButton(
        chest: taboolib.module.ui.type.Chest,
        session: PrefixEditSession,
        window: Long,
        slot: Int,
        code: Char,
        player: Player,
    ) {
        val disabled = !session.canColor
        val range = session.selection()
        val state = if (range == null) {
            PrefixStyleEditor.FormatState.DISABLED
        } else {
            PrefixStyleEditor.formatState(session.chars, range, code)
        }
        chest.buttonAt(slot, PrefixMenuItems.formatButton(code, state, disabled)) {
            if (session.windowId != window) return@buttonAt
            if (!session.canColor) {
                PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
                return@buttonAt
            }
            val target = session.selection()
            if (target == null) {
                PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
                return@buttonAt
            }
            session.applyChars(PrefixStyleEditor.toggleFormat(session.chars, target, code, session.shellColor))
            PrefixStyleMenu.open(player, session)
        }
    }
}

package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.util.PrefixStyleEditor
import org.bukkit.entity.Player
import taboolib.library.xseries.XMaterial

/**
 * 渐变页（27 格）：四个预设、自定义端点、交换与应用。
 *
 * 各通道取 `round(start + (end-start)*i/(n-1))`；单字取起点，端点准确，范围外不变。
 */
internal object PrefixGradientMenu {

    private const val PREVIEW = 4
    private const val WARM = 10
    private const val SEA = 12
    private const val PURPLE = 14
    private const val LIME = 16
    private const val FROM = 19
    private const val TO = 20
    private const val BACK = 22
    private const val SWAP = 24
    private const val APPLY = 25

    private data class Preset(val slot: Int, val key: String, val from: String, val to: String)

    private val PRESETS = listOf(
        Preset(WARM, "gui.prefix.gradient-warm", "FF5555", "FFAA00"),
        Preset(SEA, "gui.prefix.gradient-sea", "55FFFF", "5555FF"),
        Preset(PURPLE, "gui.prefix.gradient-purple", "FF55FF", "AA00AA"),
        Preset(LIME, "gui.prefix.gradient-lime", "55FF55", "FFFF55"),
    )

    fun open(player: Player, session: PrefixEditSession) {
        if (!PrefixEditSessions.isCurrent(session)) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        val window = ++session.windowId
        GuiBase.openSlots(
            player = player,
            title = Messages.get("gui.prefix.gradient-title"),
            rows = 3,
            guard = { session.windowId == window && PrefixEditSessions.isCurrent(session) },
            onOpened = { session.inventory = it },
        ) {
            setSlot(PREVIEW, PrefixMenuItems.preview(session))

            PRESETS.forEach { preset ->
                buttonAt(preset.slot, GuiBase.item(XMaterial.FIREWORK_STAR, preset.key, "gui.prefix.gradient-preset-lore")) {
                    if (session.windowId != window) return@buttonAt
                    applyGradient(player, session, preset.from, preset.to)
                }
            }

            buttonAt(
                FROM,
                GuiBase.item(XMaterial.PAPER, "gui.prefix.gradient-from", "gui.prefix.gradient-endpoint-lore", session.gradientFrom),
            ) {
                if (session.windowId != window) return@buttonAt
                PrefixInputMenu.openHex(player, session, PrefixInputMode.HEX_FROM)
            }

            buttonAt(
                TO,
                GuiBase.item(XMaterial.PAPER, "gui.prefix.gradient-to", "gui.prefix.gradient-endpoint-lore", session.gradientTo),
            ) {
                if (session.windowId != window) return@buttonAt
                PrefixInputMenu.openHex(player, session, PrefixInputMode.HEX_TO)
            }

            buttonAt(SWAP, GuiBase.item(XMaterial.ARROW, "gui.prefix.gradient-swap", "gui.prefix.gradient-swap-lore")) {
                if (session.windowId != window) return@buttonAt
                val from = session.gradientFrom
                session.gradientFrom = session.gradientTo
                session.gradientTo = from
                PrefixGradientMenu.open(player, session)
            }

            buttonAt(APPLY, GuiBase.item(XMaterial.LIME_DYE, "gui.prefix.gradient-apply", "gui.prefix.gradient-apply-lore")) {
                if (session.windowId != window) return@buttonAt
                applyGradient(player, session, session.gradientFrom, session.gradientTo)
            }

            buttonAt(BACK, GuiBase.item(XMaterial.ARROW, "gui.prefix.back", "gui.prefix.back-lore")) {
                if (session.windowId != window) return@buttonAt
                PrefixEditSessions.show(player, session, PrefixPage.STYLE)
            }

            onClose(true, false) { event ->
                if (session.windowId != window) return@onClose
                if (session.inventory !== event.inventory) return@onClose
                PrefixEditSessions.switchTo(player, session, PrefixPage.STYLE)
            }
        }
    }

    private fun applyGradient(player: Player, session: PrefixEditSession, from: String, to: String) {
        if (!session.canColor) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
            return
        }
        val range = session.selection()
        if (range == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
            return
        }
        val chars = PrefixStyleEditor.withGradient(session.chars, range, from, to)
        if (chars == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
            return
        }
        session.applyChars(chars)
        PrefixGradientMenu.open(player, session)
    }
}


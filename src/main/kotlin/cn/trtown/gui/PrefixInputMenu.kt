package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.service.PrefixValidator
import cn.trtown.service.TownException
import cn.trtown.util.PrefixCodec
import cn.trtown.util.PrefixStyleEditor
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType

/**
 * 铁砧输入页：整段前缀文字与 HEX 颜色。
 *
 * - 只点结果槽（raw slot 2）才确认；
 * - rename 包只写 [PrefixEditSession.pendingInput]，不在数据包线程碰玩家/背包；
 * - Esc 取消本次输入，下一玩家 tick 回到输入前页面，保留原草稿与选区。
 */
internal object PrefixInputMenu {

    fun openText(player: Player, session: PrefixEditSession) {
        open(
            player = player,
            session = session,
            mode = PrefixInputMode.TEXT,
            initial = session.plainText,
            returnPage = PrefixPage.EDITOR,
        )
    }

    fun openHex(player: Player, session: PrefixEditSession, mode: PrefixInputMode) {
        if (!session.canColor) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
            PrefixEditSessions.show(player, session, PrefixPage.GRADIENT)
            return
        }
        val initial = when (mode) {
            PrefixInputMode.HEX_FROM -> session.gradientFrom
            PrefixInputMode.HEX_TO -> session.gradientTo
            else -> ""
        }
        open(
            player = player,
            session = session,
            mode = mode,
            initial = initial,
            returnPage = if (mode == PrefixInputMode.HEX_RANGE) PrefixPage.STYLE else PrefixPage.GRADIENT,
        )
    }

    private fun open(
        player: Player,
        session: PrefixEditSession,
        mode: PrefixInputMode,
        initial: String,
        returnPage: PrefixPage,
    ) {
        if (!PrefixEditSessions.isCurrent(session)) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        val window = ++session.windowId
        session.pendingInput = initial
        val titleKey = if (mode == PrefixInputMode.TEXT) "gui.prefix.input-title" else "gui.prefix.hex-title"
        val hintKey = if (mode == PrefixInputMode.TEXT) "gui.prefix.input-hint" else "gui.prefix.hex-hint"
        GuiBase.openAnvil(
            player = player,
            title = Messages.get(titleKey),
            initial = initial,
            hint = Messages.get(hintKey),
            guard = { session.windowId == window && PrefixEditSessions.isCurrent(session) },
            onOpened = { session.inventory = it },
        ) {
            onRename { eventPlayer, text, _ ->
                if (eventPlayer.uniqueId == session.uuid && session.windowId == window) {
                    session.pendingInput = text
                }
            }
            onClick(2) { event ->
                if (session.windowId != window) return@onClick
                val click = event.clickEventOrNull()?.click
                if (click != ClickType.LEFT && click != ClickType.RIGHT) return@onClick
                confirm(player, session, mode, returnPage)
            }
            onClose(true, false) { event ->
                if (session.windowId != window) return@onClose
                if (session.inventory !== event.inventory) return@onClose
                // Esc = 取消本次输入；保留打开铁砧前的草稿与选区
                session.pendingInput = null
                PrefixEditSessions.switchTo(player, session, returnPage)
            }
        }
    }

    private fun confirm(
        player: Player,
        session: PrefixEditSession,
        mode: PrefixInputMode,
        returnPage: PrefixPage,
    ) {
        val text = session.pendingInput ?: ""
        session.pendingInput = null
        when (mode) {
            PrefixInputMode.TEXT -> applyText(player, session, text)
            PrefixInputMode.HEX_RANGE -> applyHexRange(player, session, text)
            PrefixInputMode.HEX_FROM -> applyHexEndpoint(player, session, text, start = true)
            PrefixInputMode.HEX_TO -> applyHexEndpoint(player, session, text, start = false)
        }
        PrefixEditSessions.switchTo(player, session, returnPage)
    }

    /**
     * Q2 语义：确认内容与进入前纯文字相同 → 保留整份原草稿及配色；
     * 不同 → 整段替换、清旧样式、重新全选。
     */
    private fun applyText(player: Player, session: PrefixEditSession, text: String) {
        if (text == session.plainText) return
        val canonical = try {
            PrefixValidator.validate(text, session.rules, allowEmpty = false)
        } catch (ex: TownException) {
            PrefixEditSessions.notice(player, PrefixEditSessions.feedback(ex, session))
            return
        } catch (ex: Throwable) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.save-failed"))
            return
        } ?: run {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.text-empty"))
            return
        }
        val chars = PrefixStyleEditor.decode(canonical, session.rules.allowColor)
        if (chars == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
            return
        }
        session.replaceText(chars)
    }

    private fun applyHexRange(player: Player, session: PrefixEditSession, text: String) {
        if (!session.canColor) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
            return
        }
        val hex = PrefixCodec.normalizeHex(text)
        if (hex == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
            return
        }
        val range = session.selection()
        if (range == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.no-selection"))
            return
        }
        val chars = PrefixStyleEditor.withColor(
            session.chars,
            range,
            "&#$hex",
            session.shellColor,
            session.shellFormats,
        )
        if (chars == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
            return
        }
        session.applyChars(chars)
    }

    private fun applyHexEndpoint(player: Player, session: PrefixEditSession, text: String, start: Boolean) {
        if (!session.canColor) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.color-disabled"))
            return
        }
        val hex = PrefixCodec.normalizeHex(text)
        if (hex == null) {
            PrefixEditSessions.notice(player, Messages.get("gui.prefix.invalid-color"))
            return
        }
        if (start) session.gradientFrom = hex else session.gradientTo = hex
    }
}

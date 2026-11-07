package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.service.PrefixService
import cn.trtown.util.PrefixCodec
import cn.trtown.util.PrefixStyleEditor
import org.bukkit.inventory.ItemStack
import taboolib.library.xseries.XMaterial

/**
 * 前缀编辑器的共用物品构建。
 *
 * 预览文本先转成 `§` 串再交给 `buildItem` 上色，避免不同版本对 `&#RRGGBB` 的解析差异。
 */
internal object PrefixMenuItems {

    /** 真实效果预览：名字是"效果预览"，lore 第一行放当前草稿的真实颜色。 */
    fun preview(session: PrefixEditSession): ItemStack {
        val saved = PrefixService.previewOf(session.openedPrefix, session.townName, session.rules)
        val draft = PrefixService.previewOf(session.previewRaw, session.townName, session.rules)
        val status = when {
            session.reset -> Messages.get("gui.prefix.default")
            session.unreadable -> Messages.get("gui.prefix.unreadable")
            session.openedPrefix.isNullOrBlank() && !session.draftChanged -> Messages.get("gui.prefix.default")
            else -> Messages.get("gui.prefix.custom")
        }
        val lore = Messages.get(
            "gui.prefix.preview-lore",
            display(saved),
            display(draft),
            session.chars.size,
            session.rules.maxLength,
            status,
        ).split('\n')
        return GuiBase.itemRaw(XMaterial.NAME_TAG, Messages.get("gui.prefix.preview"), lore)
    }

    /** 当前选区说明。 */
    fun selectionInfo(session: PrefixEditSession): ItemStack {
        val range = session.selection()
        val rangeText = if (range == null) Messages.get("gui.common.none") else "${range.first + 1} - ${range.last + 1}"
        val size = if (range == null) 0 else range.last - range.first + 1
        val anchor = session.anchor?.let { Messages.get("gui.prefix.anchor", it + 1) }
            ?: Messages.get("gui.prefix.no-anchor")
        val lore = Messages.get(
            "gui.prefix.selection-lore",
            rangeText,
            size,
            anchor,
        ).split('\n')
        return GuiBase.itemRaw(XMaterial.MAP, Messages.get("gui.prefix.selection"), lore)
    }

    /** 字格：显示字符本身、全局序号与是否选中。 */
    fun character(session: PrefixEditSession, index: Int): ItemStack {
        val char = session.chars[index]
        val selected = session.selection()?.contains(index) == true
        val anchor = session.anchor == index
        val name = if (char.value == " ") Messages.get("gui.prefix.space") else char.value
        val state = when {
            anchor -> Messages.get("gui.prefix.anchor-state")
            selected -> Messages.get("gui.prefix.selected")
            else -> Messages.get("gui.prefix.unselected")
        }
        val lore = Messages.get("gui.prefix.character-lore", index + 1, state).split('\n')
        val stack = GuiBase.itemRaw(XMaterial.PAPER, name, lore)
        if (selected) GuiBase.glow(stack)
        return stack
    }

    /** 16 色玻璃板。 */
    fun colorSwatch(code: Char, disabled: Boolean): ItemStack {
        val material = colorMaterial(code)
        val name = Messages.get("gui.prefix.color-$code")
        val lore = if (disabled) Messages.get("gui.prefix.color-disabled") else Messages.get("gui.prefix.color-lore")
        return GuiBase.itemRaw(material, name, lore.split('\n'))
    }

    fun formatButton(code: Char, state: PrefixStyleEditor.FormatState, disabled: Boolean): ItemStack {
        val material = when (code) {
            'l' -> XMaterial.IRON_SWORD
            'm' -> XMaterial.STRING
            'n' -> XMaterial.BOOK
            'o' -> XMaterial.FEATHER
            else -> XMaterial.PAPER
        }
        val stateText = when (state) {
            PrefixStyleEditor.FormatState.ENABLED -> Messages.get("gui.prefix.format-on")
            PrefixStyleEditor.FormatState.DISABLED -> Messages.get("gui.prefix.format-off")
            PrefixStyleEditor.FormatState.MIXED -> Messages.get("gui.prefix.format-mixed")
        }
        val lore = if (disabled) {
            Messages.get("gui.prefix.color-disabled")
        } else {
            Messages.get("gui.prefix.format-lore", stateText)
        }
        val nameKey = when (code) {
            'l' -> "gui.prefix.bold"
            'm' -> "gui.prefix.strikethrough"
            'n' -> "gui.prefix.underline"
            else -> "gui.prefix.italic"
        }
        val stack = GuiBase.itemRaw(material, Messages.get(nameKey), lore.split('\n'))
        if (state == PrefixStyleEditor.FormatState.ENABLED) GuiBase.glow(stack)
        return stack
    }

    fun display(raw: String?): String {
        if (raw.isNullOrEmpty()) return Messages.get("gui.common.none")
        return PrefixCodec.toSection(raw)
    }

    private fun colorMaterial(code: Char): XMaterial = when (code) {
        '0' -> XMaterial.BLACK_STAINED_GLASS_PANE
        '1' -> XMaterial.BLUE_STAINED_GLASS_PANE
        '2' -> XMaterial.GREEN_STAINED_GLASS_PANE
        '3' -> XMaterial.CYAN_STAINED_GLASS_PANE
        '4' -> XMaterial.RED_STAINED_GLASS_PANE
        '5' -> XMaterial.PURPLE_STAINED_GLASS_PANE
        '6' -> XMaterial.ORANGE_STAINED_GLASS_PANE
        '7' -> XMaterial.LIGHT_GRAY_STAINED_GLASS_PANE
        '8' -> XMaterial.GRAY_STAINED_GLASS_PANE
        '9' -> XMaterial.BLUE_STAINED_GLASS_PANE
        'a' -> XMaterial.LIME_STAINED_GLASS_PANE
        'b' -> XMaterial.LIGHT_BLUE_STAINED_GLASS_PANE
        'c' -> XMaterial.RED_STAINED_GLASS_PANE
        'd' -> XMaterial.MAGENTA_STAINED_GLASS_PANE
        'e' -> XMaterial.YELLOW_STAINED_GLASS_PANE
        else -> XMaterial.WHITE_STAINED_GLASS_PANE
    }
}

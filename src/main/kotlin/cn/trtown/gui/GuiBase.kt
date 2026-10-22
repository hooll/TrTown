package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.platform.Log
import cn.trtown.platform.PlatformScheduler
import cn.trtown.service.TownException
import cn.trtown.store.ThreadGuard
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import taboolib.library.xseries.XMaterial
import taboolib.module.ui.ClickEvent
import taboolib.module.ui.openMenu
import taboolib.module.ui.type.Chest
import taboolib.platform.util.buildItem

/**
 * GUI 公共件（PLAN §5.18.2–§5.18.5）。
 *
 * 所有菜单都必须走这里，才能同时满足四条硬约束：
 * 1. **线程**：取数只用 [fetch]（异步线程只读），开店与点击处理一律在玩家 region 线程；
 * 2. **防刷物品**：`handLocked(false)` + 每个按钮显式取消 + `onClick(true)` 兜底；
 * 3. **文案**：名字与描述全部取自 `lang/zh_CN.yml`，代码里不写玩家可见文案；
 * 4. **命令不重复实现**：写操作优先 [GuiActions.dispatch] 复用 `/tt` 命令链路。
 */
internal object GuiBase {

    /** 空白占位字符（填充玻璃板） */
    const val FILL: Char = ' '

    /** 取数失败时的兜底文案键 */
    private const val FAIL_KEY = "gui.disabled"

    /** 标题 / 文本（`&` 上色由 [Messages] 完成） */
    fun text(key: String, vararg args: Any?): String = Messages.get(key, *args)

    /** `rows` 行 × 9 格、全部填 [FILL] 的布局 */
    fun grid(rows: Int): Array<StringBuilder> = Array(rows) { StringBuilder(FILL.toString().repeat(9)) }

    /** 布局 → [open] 需要的字符串列表 */
    fun lines(grid: Array<StringBuilder>): List<String> = grid.map { it.toString() }

    /** 构建"名字 + 描述"都走消息键的物品 */
    fun item(material: XMaterial, nameKey: String, loreKey: String? = null, vararg args: Any?): ItemStack =
        buildItem(material) {
            name = text(nameKey, *args)
            if (loreKey != null) lore.addAll(text(loreKey, *args).split('\n'))
            colored()
        }

    /** 空白格玻璃板 */
    fun pane(): ItemStack = buildItem(XMaterial.GRAY_STAINED_GLASS_PANE) { name = text("gui.button.pane") }

    /** 玩家头颅（离线玩家用名字当 owner，纹理可能缺失但不影响点击） */
    fun head(owner: String, nameKey: String, loreKey: String? = null, vararg args: Any?): ItemStack =
        buildItem(XMaterial.PLAYER_HEAD) {
            name = text(nameKey, *args)
            if (loreKey != null) lore.addAll(text(loreKey, *args).split('\n'))
            skullOwner = owner
            colored()
        }

    /** 加一道附魔光（用于"有待处理邀请"这类提示；失败不影响功能） */
    fun glow(stack: ItemStack): ItemStack {
        runCatching { stack.addUnsafeEnchantment(Enchantment.UNBREAKING, 1) }
        return stack
    }

    /**
     * 在玩家 region 线程打开 Chest 菜单。
     *
     * `handLocked(false)` 防止把菜单物品拖进背包；`onClick(true)` 兜底取消任何
     * 没被单独接管的点击（AUDIT 23.3 / 23.7 的"GUI 刷物品"检查点）。
     */
    fun open(player: Player, title: String, layout: List<String>, content: Chest.() -> Unit) {
        PlatformScheduler.runForPlayer(player) {
            player.openMenu<Chest>(title) {
                rows(layout.size)
                map(*layout.toTypedArray())
                handLocked(false)
                set(FILL, pane())
                // 注意：参数不能叫 build —— Chest 自己有 build()，在接收者作用域里会先绑到成员函数，
                // 于是菜单只剩玻璃板、一个按钮都不铺（第 23 轮真机实测踩到）。
                content()
                onClick(true) { event -> event.isCancelled = true }
            }
        }
    }

    /** 二次确认菜单：绿玻璃 = 执行，红玻璃 = 放弃 */
    fun confirm(player: Player, titleKey: String, onYes: () -> Unit, onNo: () -> Unit) {
        val grid = grid(3)
        grid[1][2] = 'Y'
        grid[1][6] = 'N'
        open(player, text(titleKey), lines(grid)) {
            button('Y', item(XMaterial.LIME_STAINED_GLASS_PANE, "gui.confirm.yes", "gui.confirm.yes-lore")) {
                player.closeInventory()
                onYes()
            }
            button('N', item(XMaterial.RED_STAINED_GLASS_PANE, "gui.confirm.no", "gui.confirm.no-lore")) {
                onNo()
            }
        }
    }

    /**
     * 异步取数 → 回玩家 region 线程执行 [then]（PLAN §5.18.3 两段式）。
     *
     * 取数失败一律提示 [FAIL_KEY]（存储未就绪 / Dominion 抛异常），
     * **绝不拿默认数据开店**。
     */
    fun <T : Any> fetch(player: Player, load: () -> T?, then: (T) -> Unit) {
        PlatformScheduler.runAsync {
            val data = try {
                load()
            } catch (ex: TownException) {
                notice(player, "§c${ex.message}")
                return@runAsync
            } catch (ex: Throwable) {
                Log.failure("GUI 取数", ex)
                notice(player, text(FAIL_KEY))
                return@runAsync
            }
            if (data == null) {
                notice(player, text(FAIL_KEY))
                return@runAsync
            }
            PlatformScheduler.runForPlayer(player) { then(data) }
        }
    }

    /** 任意线程给玩家发一条已上色文本 */
    fun notice(player: Player, text: String) {
        PlatformScheduler.runForPlayer(player) { player.sendMessage(text) }
    }
}

/** 注册一个可点物品：点击时再次显式取消，再执行 [click]（防刷物品） */
internal fun Chest.button(slot: Char, stack: ItemStack, click: (ClickEvent) -> Unit) {
    set(slot, stack)
    onClick(slot) { event ->
        event.isCancelled = true
        click(event)
    }
}

/** 通用"关闭"按钮 */
internal fun Chest.closeButton(slot: Char, player: Player) {
    button(slot, GuiBase.item(XMaterial.BARRIER, "gui.button.close")) { player.closeInventory() }
}

/** 通用"返回"按钮 */
internal fun Chest.backButton(slot: Char, player: Player, back: () -> Unit) {
    button(slot, GuiBase.item(XMaterial.ARROW, "gui.button.back")) { back() }
}

/**
 * GUI 写操作的统一入口（PLAN §5.18.3）。
 *
 * - [dispatch]：关菜单 → 玩家 region 线程执行 `/tt ...`，权限/线程/异常/文案全部复用命令链路；
 * - [submit]：与 `Cmd.run` 同构的异步写入口，仅供**命令链路无法表达**的操作
 *   （如"GUI 内已二次确认的解散"）直接调服务层。
 */
internal object GuiActions {

    /** 关菜单 + 执行命令（在玩家 region 线程派发） */
    fun dispatch(player: Player, command: String) {
        PlatformScheduler.runForPlayer(player) {
            player.closeInventory()
            player.performCommand(command)
        }
    }

    /** 异步执行一次写操作；异常按命令层同样的口径回包 */
    fun submit(player: Player, block: () -> Unit) {
        ThreadGuard.enterMain()
        try {
            PlatformScheduler.runAsync(delayTicks = 1) {
                try {
                    block()
                } catch (ex: TownException) {
                    GuiBase.notice(player, "§c${ex.message}")
                } catch (ex: Throwable) {
                    GuiBase.notice(player, GuiBase.text("gui.disabled"))
                    Log.failure("GUI 操作", ex)
                }
            }
        } finally {
            ThreadGuard.exitMain()
        }
    }
}

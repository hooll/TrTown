package cn.trtown.gui

import cn.trtown.command.Messages
import cn.trtown.platform.PlatformScheduler
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 聊天栏输入（PLAN §5.18.5）。
 *
 * TabooLib 的 Chest 菜单没有"关菜单读输入"的能力，所以菜单里点需要参数的按钮时：
 * 关菜单 → 记一条 pending → 在聊天栏提示 → 玩家发言被本监听器拦下并转成对应的 `/tt` 命令。
 *
 * 清理时机：输入成功、输入"取消"、30 秒超时、玩家退出、执行任意 `/tt` 子命令。
 * 只对 pending 白名单里的玩家生效，绝不吞普通聊天。
 */
internal object ChatInput : Listener {

    /** 需要聊天输入的三种动作 */
    enum class Kind(val promptKey: String) {
        /** 改聊天前缀 → `/tt setprefix <文本>` */
        PREFIX("gui.input.prefix") {
            override fun command(text: String): String = "tt setprefix $text"
        },

        /** 校验小镇 → `/tt admin verify <镇名>` */
        VERIFY("gui.input.verify") {
            override fun command(text: String): String = "tt admin verify $text"
        },

        /** 强制解散 → `/tt admin force-disband <镇名>` */
        FORCE_DISBAND("gui.input.force-disband") {
            override fun command(text: String): String = "tt admin force-disband $text"
        };

        /** 把输入拼成要执行的命令 */
        abstract fun command(text: String): String
    }

    private const val TIMEOUT_MS = 30_000L
    private const val CANCEL_WORD = "取消"

    private data class Pending(val kind: Kind, val deadline: Long)

    private val pending = ConcurrentHashMap<UUID, Pending>()

    /** 开始等待某玩家的聊天输入（关菜单 + 发提示） */
    fun begin(player: Player, kind: Kind) {
        pending[player.uniqueId] = Pending(kind, System.currentTimeMillis() + TIMEOUT_MS)
        PlatformScheduler.runForPlayer(player) {
            player.closeInventory()
            player.sendMessage(Messages.get(kind.promptKey))
        }
    }

    /** 清掉待输入状态（玩家退出、执行了任意 `/tt` 子命令时调用） */
    fun cancel(uuid: UUID?) {
        if (uuid != null) pending.remove(uuid)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val waiting = pending.remove(player.uniqueId) ?: return
        event.isCancelled = true

        if (System.currentTimeMillis() > waiting.deadline) {
            PlatformScheduler.runForPlayer(player) { player.sendMessage(Messages.get("gui.input.timeout")) }
            return
        }
        val text = event.message.trim()
        if (text.isEmpty() || text == CANCEL_WORD) {
            PlatformScheduler.runForPlayer(player) { player.sendMessage(Messages.get("gui.input.cancelled")) }
            return
        }
        GuiActions.dispatch(player, waiting.kind.command(text))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pending.remove(event.player.uniqueId)
    }
}

package cn.trtown.command

import cn.trtown.service.TownException
import cn.trtown.platform.PlatformScheduler
import cn.trtown.store.ThreadGuard
import org.bukkit.entity.Player
import taboolib.common.platform.ProxyCommandSender
import taboolib.module.chat.colored
import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 消息层。
 *
 * 用 TabooLib `@Config` 读取 `lang/zh_CN.yml`（不手写 YAML 解析）。
 * 未使用 I18n 模块的原因见 EXEC：6.3.0 的 lang 文件 schema 无文档、无法本地验证，
 * 为保证消息一定可用，先走配置读取；切 I18n 只需换这一层。
 */
object Messages {

    @Config("lang/zh_CN.yml")
    lateinit var lang: Configuration

    fun get(key: String, vararg args: Any?): String {
        val raw = if (::lang.isInitialized) lang.getString(key) else null
        var text = raw ?: key
        args.forEachIndexed { i, arg -> text = text.replace("{$i}", arg?.toString() ?: "") }
        return text.colored()
    }

    fun send(sender: ProxyCommandSender, key: String, vararg args: Any?) {
        sender.sendMessage(get(key, *args))
    }
}

/** 命令公共逻辑：线程守卫 + 异步执行 + 错误回包 */
object Cmd {

    /** 把业务逻辑丢到异步线程；主线程标记在命令线程上（M-8） */
    fun run(sender: ProxyCommandSender, block: () -> Unit) {
        ThreadGuard.enterMain()
        try {
            PlatformScheduler.runAsync(delayTicks = 1) {
                try {
                    block()
                } catch (ex: TownException) {
                    reply(sender, "§c${ex.message}")
                } catch (ex: Throwable) {
                    reply(sender, "§c操作失败：${ex.javaClass.simpleName}: ${ex.message}")
                    ex.printStackTrace()
                }
            }
        } finally {
            ThreadGuard.exitMain()
        }
    }

    fun player(sender: ProxyCommandSender, block: (Player) -> Unit) {
        // 注意：不能用 castSafely<Player>()，泛型擦除会在调用点抛 ClassCastException
        val player = sender.origin as? Player
        if (player == null) {
            reply(sender, "§c该命令只能由玩家执行")
            return
        }
        block(player)
    }

    /** 回包必须回到玩家自己的 region 线程 */
    fun reply(sender: ProxyCommandSender, text: String) {
        val player = sender.origin as? Player
        if (player != null) {
            PlatformScheduler.runForPlayer(player) { player.sendMessage(text) }
        } else {
            PlatformScheduler.runGlobal { sender.sendMessage(text) }
        }
    }

    fun replyKey(sender: ProxyCommandSender, key: String, vararg args: Any?) {
        reply(sender, Messages.get(key, *args))
    }

    /**
     * 发送者 UUID（补全用）。
     *
     * 走 [ProxyCommandSender] 的 `origin` 再交给 `Players`，**不直接引用 Bukkit 类型**：
     * 命令目录一旦 `import org.bukkit.entity.Player`，单测（无 Bukkit）就会
     * `NoClassDefFoundError`，也违反 AC-48 ② 的可测性契约。
     */
    fun uuidOf(sender: ProxyCommandSender): java.util.UUID? =
        cn.trtown.platform.Players.uuidOf(sender.origin)
}

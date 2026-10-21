package cn.trtown.platform

import org.bukkit.Bukkit
import java.util.UUID

/**
 * 玩家查询封装。
 *
 * 命令目录（`cn.trtown.command`）禁止出现 `Bukkit.` / `getOnlinePlayers`
 * （PLAN §5.6.3 AC-48 ②），所有 Bukkit 玩家 API 都收在这里。
 */
object Players {

    fun find(name: String): UUID? = Bukkit.getPlayerExact(name)?.uniqueId

    fun nameOf(uuid: UUID): String = Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()

    fun onlineNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    fun isOnline(uuid: UUID): Boolean = Bukkit.getPlayer(uuid) != null

    /**
     * 任意对象的 UUID（如果它是一个在线玩家）。
     *
     * 用反射判断，避免在命令目录里出现 Bukkit 类型 —— 单测环境没有 `org.bukkit.entity.Player`，
     * 直接 `as? Player` 会 `NoClassDefFoundError`。
     *
     * 用 `declaredMethods` 而不是 `getMethod`：测试用的 sender 是 `private class`，
     * 它生成的 `getUniqueId()` 不是 public 方法，`getMethod` 找不到。
     */
    fun uuidOf(origin: Any?): UUID? {
        if (origin == null) return null
        return runCatching {
            origin.javaClass.declaredMethods
                .firstOrNull { it.name == "getUniqueId" && it.parameterCount == 0 }
                ?.also { it.isAccessible = true }
                ?.invoke(origin) as? UUID
        }.getOrNull()
    }
}

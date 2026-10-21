package cn.trtown.command

/**
 * 补全候选集的**纯函数**（PLAN §5.6.3）。
 *
 * 零 Bukkit、零 DB、零配置读取 —— 输入即全部依赖，因此可在单测里直接断言。
 * 命令文件只做"一行转发"，候选生成逻辑一律写在这里。
 */
object Suggestions {

    /** 在线玩家名，排除自己 */
    fun playerNames(online: Collection<String>, self: String?): List<String> {
        val me = self ?: return online.toList()
        return online.filter { !it.equals(me, ignoreCase = true) }
    }

    /** 可授予的阶级：不含 mayor（`setrole` 显式拒绝把别人设为镇长） */
    fun roles(): List<String> = listOf("manager", "member")

    /** 可开关的 flag：小写 flag_name 与中文别名并集，去重且保持顺序 */
    fun flags(editable: List<String>, aliases: Map<String, String>): List<String> {
        val result = LinkedHashSet<String>()
        editable.forEach { result += it }
        aliases.keys.forEach { result += it }
        return result.toList()
    }

    /** 覆写值 */
    fun values(): List<String> = listOf("on", "off", "reset")

    /** 全服活跃镇名 */
    fun towns(cached: List<String>): List<String> = cached

    /** 该玩家待处理邀请的镇名 */
    fun pendingInvites(cached: List<String>): List<String> = cached

    /** 该玩家名下可绑定的领地名 */
    fun territories(cached: List<String>): List<String> = cached
}

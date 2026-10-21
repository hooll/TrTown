package cn.trtown.platform

import cn.trtown.command.Messages
import org.bukkit.Bukkit
import java.util.UUID

/**
 * 玩家消息的唯一投递出口（PLAN §5.15 硬规则 1，R29）。
 *
 * 为什么必须有这一层：
 * 1. `InviteService.invite()` 跑在**异步线程**，Folia 上直接 `player.sendMessage` 跨 region
 *    会出问题，必须经 [PlatformScheduler.runForPlayer] 切到目标玩家的 region 线程；
 * 2. 服务层不许出现 `Bukkit.` / `sendMessage`（AC-50 ④ 两条 grep 卡死），
 *    所以消息只能由接线层经这里发出。
 *
 * 离线玩家 → **静默失败**：邀请本身仍然有效（下次登录由 `Lifecycle.onJoin` 汇总提醒），
 * 只是当场看不到。
 */
object Notify {

    /** @param key `lang/zh_CN.yml` 的键；[args] 按 `{0}`/`{1}` 顺序替换 */
    fun send(uuid: UUID, key: String, vararg args: Any?) {
        val player = Bukkit.getPlayer(uuid) ?: return
        val text = Messages.get(key, *args)
        PlatformScheduler.runForPlayer(player) { player.sendMessage(text) }
    }
}

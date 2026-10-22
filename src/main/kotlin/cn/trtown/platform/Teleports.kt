package cn.trtown.platform

import cn.lunadeer.dominion.api.DominionAPI
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 小镇传送的执行端（PLAN §5.17.3 / AUDIT 23.5）。
 *
 * 为什么在 `platform/` 而不在命令层：`TeleportProvider.teleport(Player, DominionDTO)` 需要
 * Bukkit 的 [Player] 与 Dominion 的 DTO 实例，而 `command/` 目录禁止出现 Bukkit 类型
 * （AC-48 ②）。服务层则完全不碰 Bukkit（`service/` 只接收 `BlockPosition`）。
 *
 * **线程契约**：入口可以从任意线程调用（命令线程 / 异步线程）；真正调 provider 的那一步
 * 一定被切到**玩家自己的 region 线程**，回调也同样切回 region 线程，保证调用方
 * （`Cmd.reply` / GUI 重开菜单）永远在 region 线程上碰玩家对象。
 */
object Teleports {

    /**
     * 把 [uuid] 传到 `dominionId` 的传送点，结果通过 [onResult] 回调（**只回调一次**，
     * 且在玩家 region 线程上）。
     *
     * `true` = 传送成功；`false` = 玩家不在线 / 领地不存在 / provider 抛异常 / 返回 false
     * （无进入权限、目标点不可用）。失败细节进日志，调用方只负责一句玩家可见文案。
     */
    fun teleportTo(uuid: UUID, dominionId: Int, onResult: (Boolean) -> Unit) {
        val player = Bukkit.getPlayer(uuid)
        if (player == null) {
            onResult(false)
            return
        }
        teleportTo(player, dominionId, onResult)
    }

    /** 同 [teleportTo]；已有 [Player] 时用它（GUI 点击回调里本来就在 region 线程） */
    fun teleportTo(player: Player, dominionId: Int, onResult: (Boolean) -> Unit) {
        PlatformScheduler.runForPlayer(player) {
            val dom = runCatching { DominionAPI.getInstance().getDominion(dominionId) }.getOrElse {
                Log.failure("dominion.getDominion(#$dominionId)", it)
                onResult(false)
                return@runForPlayer
            }
            if (dom == null) {
                onResult(false)
                return@runForPlayer
            }
            val future = runCatching { DominionAPI.getTeleportProvider().teleport(player, dom) }.getOrElse {
                // provider 未装 / 初始化失败：不能静默当成功
                Log.failure("dominion.teleport(#$dominionId)", it)
                onResult(false)
                return@runForPlayer
            }
            future.whenComplete { ok, ex ->
                if (ex != null) Log.failure("dominion.teleport(#$dominionId)", ex)
                val success = ex == null && ok == true
                // provider 的回调线程不保证是 region 线程，统一切回去
                runCatching {
                    PlatformScheduler.runForPlayer(player) { onResult(success) }
                }.onFailure {
                    Log.warn("传送回调无法回到玩家线程（player=${player.name}, dominion=#$dominionId）：${it.message}")
                }
            }
        }
    }
}

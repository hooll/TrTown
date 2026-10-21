package cn.trtown.platform

import cn.trtown.store.ThreadGuard
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.platform.BukkitPlugin
import java.util.concurrent.TimeUnit

/**
 * Folia / Paper 调度封装。
 *
 * **全插件唯一允许接触 Bukkit / Folia 调度器的文件**（PLAN §5.5、AC-2）。
 * 异步执行复用 TabooLib `submitAsync`，region / 玩家调度才用 Folia 原生 API。
 */
object PlatformScheduler : Scheduler {

    private val isFolia: Boolean by lazy {
        runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess
    }

    private val plugin get() = BukkitPlugin.getInstance()

    override fun runOnMain(delayTicks: Long, task: () -> Unit) = runGlobal(delayTicks, task)

    override fun runAsync(delayTicks: Long, task: () -> Unit) {
        if (isFolia) {
            if (delayTicks <= 0) {
                Bukkit.getAsyncScheduler().runNow(plugin) { task() }
            } else {
                Bukkit.getAsyncScheduler().runDelayed(plugin, { task() }, delayTicks * 50L, TimeUnit.MILLISECONDS)
            }
        } else {
            // Paper 退路：TabooLib 的异步调度（now = true 会同步执行，这里强制 delay >= 1）
            submitAsync(delay = delayTicks.coerceAtLeast(1)) { task() }
        }
    }

    /** 全局线程（无 region 归属的任务） */
    fun runGlobal(delayTicks: Long = 0, task: () -> Unit) {
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { guarded(task) }, delayTicks.coerceAtLeast(1))
        } else {
            submit(delay = delayTicks) { guarded(task) }
        }
    }

    /** 指定位置所属 region */
    fun runAt(location: Location, delayTicks: Long = 0, task: () -> Unit) {
        if (isFolia) {
            Bukkit.getRegionScheduler().runDelayed(plugin, location, { guarded(task) }, delayTicks.coerceAtLeast(1))
        } else {
            submit(delay = delayTicks) { guarded(task) }
        }
    }

    /** 玩家所属 region */
    fun runForPlayer(player: Player, delayTicks: Long = 0, task: () -> Unit) {
        if (isFolia) {
            player.scheduler.runDelayed(plugin, { guarded(task) }, null, delayTicks.coerceAtLeast(1))
        } else {
            submit(delay = delayTicks) { guarded(task) }
        }
    }

    /**
     * 当前线程是不是**全局线程**（PLAN §5.14）。
     *
     * Folia 上命令线程是 region 线程、不等于全局线程，所以不能用 `isPrimaryThread()` 代替；
     * 非 Folia（Paper）退化成主线程判断。
     */
    fun isGlobalThread(): Boolean = if (isFolia) Bukkit.isGlobalTickThread() else Bukkit.isPrimaryThread()

    /** 把任务丢到全局线程（[GlobalBridge] 的 dispatch 接线，见 [dominionBridge]） */
    fun dispatchGlobal(task: () -> Unit) {
        runGlobal(0, task)
    }

    /** Dominion 写操作用的桥（M-23 / AC-49） */
    fun dominionBridge(): GlobalBridge.Bridge = GlobalBridge.bind(::isGlobalThread, ::dispatchGlobal)

    /** 主线程上下文：打标记，主线程上的 DB 访问会被 [ThreadGuard] 拦下 */
    private fun guarded(task: () -> Unit) {
        ThreadGuard.enterMain()
        try {
            task()
        } finally {
            ThreadGuard.exitMain()
        }
    }
}

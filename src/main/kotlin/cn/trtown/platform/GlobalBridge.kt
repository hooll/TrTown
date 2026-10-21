package cn.trtown.platform

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 等待全局线程超时（PLAN §5.14 硬规则 4）。
 *
 * 单独一个类型是为了让调用方能区分"业务失败"和"线程调度失败"：
 * 前者按 §5.4 回滚，后者说明调度接线坏了（通常是插件正在卸载）。
 */
class GlobalBridgeTimeout(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * "切到全局线程执行并等结果"的桥（PLAN §5.14，M-23）。
 *
 * **纯 Kotlin，零 Bukkit** —— 线程探测与调度都由调用方注入，所以能在普通 JVM 单测里跑
 * （AC-49 ①）。真实接线见 [PlatformScheduler.isGlobalThread] / [PlatformScheduler.dispatchGlobal]。
 *
 * 存在的原因：Paper 的 `PaperEventManager` 规定**非 async 事件只能在全局线程触发**，
 * 而 Folia 的命令线程是 region 线程、同步队列是 async 线程。Dominion 的 8 个写方法内部同步
 * `callEvent(...)`，从错误线程调必抛 `X may only be triggered synchronously.`。
 */
object GlobalBridge {

    const val DEFAULT_TIMEOUT_MS: Long = 10_000L

    /**
     * 已在全局线程 → 内联执行 [block]；否则交给 [dispatch] 调度并阻塞等待结果。
     *
     * 内联分支不是优化，是**防死锁**：全局线程里再 `runGlobal` 会等自己，10 秒后超时。
     */
    fun <T> run(
        isGlobal: () -> Boolean,
        dispatch: ((() -> Unit) -> Unit),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        block: () -> T,
    ): T {
        if (isGlobal()) return block()

        val future = CompletableFuture<T>()
        dispatch {
            try {
                future.complete(block())
            } catch (ex: Throwable) {
                // 异常原样带回调用线程，不在调度线程上吞掉
                future.completeExceptionally(ex)
            }
        }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ex: TimeoutException) {
            throw GlobalBridgeTimeout("等待全局线程超过 ${timeoutMs}ms", ex)
        } catch (ex: ExecutionException) {
            throw ex.cause ?: ex
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw GlobalBridgeTimeout("等待全局线程时被中断", ex)
        }
    }

    /** 绑定好线程探测的实例；[Bridge.run] 多一个 `op` 参数，只用于超时文案 */
    fun bind(
        isGlobal: () -> Boolean,
        dispatch: ((() -> Unit) -> Unit),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Bridge = Bridge(isGlobal, dispatch, timeoutMs)

    class Bridge internal constructor(
        private val isGlobal: () -> Boolean,
        private val dispatch: ((() -> Unit) -> Unit),
        private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ) {

        /** 在全局线程执行 [block] 并带回结果；超时的异常文案带上 [op]，便于定位是哪一步卡住 */
        fun <T> run(op: String, block: () -> T): T = try {
            GlobalBridge.run(isGlobal, dispatch, timeoutMs, block)
        } catch (ex: GlobalBridgeTimeout) {
            throw GlobalBridgeTimeout("$op: ${ex.message}", ex)
        }
    }
}

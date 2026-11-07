package cn.trtown.platform

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 前缀专用单线程 IO 执行器（守护线程，具名）。
 *
 * 只承载前缀读取与保存，不接其它业务；关闭插件时先停止接受新任务，
 * 再排空已接受的任务，最后关闭执行器。enable 时重新 [start]，
 * 避免同 JVM 内 disable/enable（例如服务器 reload）后执行器永久拒绝任务。
 */
object PrefixIo {

    private const val THREAD_NAME = "TrTown-Prefix-IO"

    private val accepting = AtomicBoolean(false)

    @Volatile
    private var executor: ExecutorService? = null

    /** 插件 enable 时调用；可重复调用。 */
    @Synchronized
    fun start() {
        if (executor == null || executor?.isShutdown == true) {
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, THREAD_NAME).apply { isDaemon = true }
            }
        }
        accepting.set(true)
    }

    /** 投递任务；关闭后返回 false（调用方据此提示，不得继续假装成功）。 */
    fun submit(task: () -> Unit): Boolean {
        if (!accepting.get()) return false
        val current = synchronized(this) { executor } ?: return false
        return runCatching {
            current.execute {
                try {
                    task()
                } catch (ex: Throwable) {
                    Log.failure("[前缀] 任务失败", ex)
                }
            }
            true
        }.getOrDefault(false)
    }

    val isAccepting: Boolean
        get() = accepting.get()

    /** 停止接受新任务 → 排空已接受任务 → 关闭执行器。 */
    fun shutdown() {
        if (!accepting.compareAndSet(true, false)) return
        val current = synchronized(this) {
            executor.also { executor = null }
        } ?: return
        current.shutdown()
        runCatching { current.awaitTermination(5, TimeUnit.SECONDS) }
    }
}

package cn.trtown.platform

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 前缀专用单线程 IO 执行器（守护线程，具名）。
 *
 * 只承载前缀读取与保存，不接其它业务；关闭插件时先停止接受新任务，
 * 再排空已接受的任务，最后关闭执行器。旧执行器尚未真正终止时拒绝创建
 * 第二条写线程；只有它退出后才允许重新 [start]。
 */
object PrefixIo {

    private const val THREAD_NAME = "TrTown-Prefix-IO"

    private val accepting = AtomicBoolean(false)

    @Volatile
    private var executor: ExecutorService? = null

    /** 插件 enable 时调用；旧写线程仍存活时返回 false，不创建并行写线程。 */
    @Synchronized
    fun start(): Boolean {
        val current = executor
        if (current != null && !current.isTerminated) {
            if (current.isShutdown) {
                Log.warn("[前缀] 旧执行器尚未终止，拒绝启动第二个前缀写线程")
                return false
            }
            accepting.set(true)
            return true
        }
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, THREAD_NAME).apply { isDaemon = true }
        }
        accepting.set(true)
        return true
    }

    fun submit(task: () -> Unit): Boolean = submit(task, {})

    /**
     * 投递任务；关闭后返回 false。
     *
     * 任务已被接受但尚未开始时若执行器被强制关闭，会调用 [onCancelled]，
     * 让调用方能把排队中的保存/读取明确收尾，而不是无声丢失。
     */
    fun submit(task: () -> Unit, onCancelled: () -> Unit): Boolean {
        if (!accepting.get()) return false
        val current = synchronized(this) { executor } ?: return false
        if (current.isShutdown) return false
        val wrapped = PrefixTask(task, onCancelled)
        return runCatching {
            current.execute(wrapped)
            true
        }.getOrDefault(false)
    }

    val isAccepting: Boolean
        get() = accepting.get()

    /**
     * 停止接受新任务 → 排空已接受任务 → 关闭执行器；返回是否在限时内排空。
     *
     * 超时后保留旧执行器引用：重复关闭不会误报成功，[start] 也不会在旧线程
     * 仍存活时创建第二条写线程。已排队但被 `shutdownNow` 取消的任务会收到取消回调。
     */
    fun shutdown(timeoutMs: Long = 5_000L): Boolean {
        accepting.set(false)
        val current = synchronized(this) { executor } ?: return true
        if (!current.isShutdown) current.shutdown()
        if (awaitTermination(current, timeoutMs)) {
            clear(current)
            return true
        }
        Log.warn("[前缀] 关闭超时：仍有已接受任务未完成，尝试中断")
        val pending = runCatching { current.shutdownNow() }.getOrDefault(emptyList())
        pending.forEach { (it as? PrefixTask)?.cancel() }
        val stopped = awaitTermination(current, 1_000L)
        if (stopped) {
            clear(current)
        } else {
            Log.warn("[前缀] 执行器未能完全停止，保留归属并拒绝新的写线程")
        }
        // 超过优雅等待时限时明确返回 false，即使中断后线程已经退出
        return false
    }

    private fun awaitTermination(target: ExecutorService, timeoutMs: Long): Boolean {
        if (target.isTerminated) return true
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        try {
            while (System.nanoTime() < deadline) {
                if (target.awaitTermination(50, TimeUnit.MILLISECONDS)) return true
            }
        } catch (_: InterruptedException) {
            // 调用线程被中断也必须继续走强制关闭路径，不能把中断吞掉后假装成功
            Thread.currentThread().interrupt()
        }
        return target.isTerminated
    }

    private fun clear(target: ExecutorService) {
        synchronized(this) {
            if (executor === target) executor = null
        }
    }

    private class PrefixTask(
        private val action: () -> Unit,
        private val onCancelled: () -> Unit,
    ) : Runnable {

        private companion object {
            const val PENDING = 0
            const val RUNNING = 1
            const val CANCELLED = 2
            const val DONE = 3
        }

        private val state = AtomicInteger(PENDING)

        override fun run() {
            if (!state.compareAndSet(PENDING, RUNNING)) return
            try {
                action()
            } catch (ex: Throwable) {
                Log.failure("[前缀] 任务失败", ex)
            } finally {
                state.compareAndSet(RUNNING, DONE)
            }
        }

        fun cancel() {
            if (!state.compareAndSet(PENDING, CANCELLED)) return
            try {
                onCancelled()
            } catch (ex: Throwable) {
                Log.failure("[前缀] 取消回调失败", ex)
            }
        }
    }
}

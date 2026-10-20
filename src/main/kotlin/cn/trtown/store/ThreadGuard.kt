package cn.trtown.store

import java.util.concurrent.atomic.AtomicInteger

/**
 * 主线程 DB 访问守卫（AC-24 / M-4）。
 *
 * 调度器在"主线程 / region 线程"上下文里调用 [enterMain]，
 * 所有数据库连接都必须经过 [GuardedDataSource]，后者调用 [onDbAccess]。
 * 一旦在主线程上取连接，立即抛异常并计数，测试断言计数为 0。
 */
object ThreadGuard {

    private val mainMarker = ThreadLocal.withInitial { false }
    private val mainCalls = AtomicInteger()
    private val asyncCalls = AtomicInteger()

    fun enterMain() {
        mainMarker.set(true)
    }

    fun exitMain() {
        mainMarker.remove()
    }

    fun isMain(): Boolean = mainMarker.get()

    /** 每次取数据库连接时调用 */
    fun onDbAccess() {
        if (mainMarker.get()) {
            mainCalls.incrementAndGet()
            throw IllegalStateException("禁止在主线程访问数据库（AC-24）")
        }
        asyncCalls.incrementAndGet()
    }

    fun reset() {
        mainCalls.set(0)
        asyncCalls.set(0)
    }

    /** 主线程上发生的 DB 访问次数（必须为 0） */
    val mainThreadDbCalls: Int
        get() = mainCalls.get()

    /** 异步线程上发生的 DB 访问次数 */
    val asyncDbCalls: Int
        get() = asyncCalls.get()
}

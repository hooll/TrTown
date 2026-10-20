package cn.trtown.platform

import cn.trtown.model.Town
import cn.trtown.service.Repos
import cn.trtown.service.TestSupport
import cn.trtown.service.TownService
import cn.trtown.store.ThreadGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-24 / M-4：主线程零 DB。
 *
 * 用严格调度器模拟"命令在 region 线程执行、DB 在异步线程执行"：
 * 主线程上下文里任何取连接的动作都会被 [ThreadGuard] 拦下并计数。
 */
class AsyncDispatchTest {

    /** 严格调度器：主线程上下文打标记，异步任务走独立线程 */
    private class StrictScheduler : Scheduler {
        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "strict-async") }

        override fun runOnMain(delayTicks: Long, task: () -> Unit) {
            ThreadGuard.enterMain()
            try {
                task()
            } finally {
                ThreadGuard.exitMain()
            }
        }

        override fun runAsync(delayTicks: Long, task: () -> Unit) {
            executor.submit { task() }
        }

        fun awaitIdle() {
            executor.submit { }.get(10, TimeUnit.SECONDS)
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    @AfterEach
    fun tearDown() {
        TestSupport.cleanup()
        ThreadGuard.reset()
    }

    @Test
    fun `命令路径上的 DB 全部在异步线程，主线程 0 次`() {
        val store = TestSupport.store()
        val repos = Repos.from(store)
        val scheduler = StrictScheduler()
        val service = TownService(repos, TestSupport.rules)
        ThreadGuard.reset()

        var town: Town? = null
        var failure: Throwable? = null

        // 模拟命令线程：先 runOnMain，再把 DB 工作丢给 runAsync
        scheduler.runOnMain {
            scheduler.runAsync {
                try {
                    town = service.create(UUID.randomUUID(), "异步镇")
                } catch (t: Throwable) {
                    failure = t
                }
            }
        }
        scheduler.awaitIdle()

        assertNull(failure)
        assertNotNull(town)
        assertEquals(0, ThreadGuard.mainThreadDbCalls, "主线程不允许发生任何 DB 访问")
        assertTrue(ThreadGuard.asyncDbCalls > 0, "异步线程应发生 DB 访问")

        scheduler.shutdown()
    }

    @Test
    fun `守卫真的能拦住主线程上的仓储调用`() {
        val store = TestSupport.store()
        val repos = Repos.from(store)
        val scheduler = StrictScheduler()
        ThreadGuard.reset()

        val ex = assertFailsWith<IllegalStateException> {
            scheduler.runOnMain { repos.towns.findByNameLower("任意") }
        }
        assertTrue(ex.message!!.contains("AC-24"))
        // 一次违规可能触发多次取连接（查询 + 关闭时的重试），只要计数 > 0 即证明守卫生效
        assertTrue(ThreadGuard.mainThreadDbCalls >= 1, "违规访问必须被计数")

        scheduler.shutdown()
    }
}

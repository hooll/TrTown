package cn.trtown.platform

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefixIoTest {

    @BeforeEach
    fun setUp() {
        PrefixIo.start()
    }

    @AfterEach
    fun tearDown() {
        PrefixIo.shutdown(1_000L)
    }

    @Test
    fun `关闭排空已接受任务`() {
        val executed = AtomicInteger()
        val firstDone = CountDownLatch(1)
        assertTrue(PrefixIo.submit {
            Thread.sleep(200L)
            executed.incrementAndGet()
            firstDone.countDown()
        })
        assertTrue(PrefixIo.submit {
            executed.incrementAndGet()
            firstDone.countDown()
        })
        assertTrue(PrefixIo.shutdown(3_000L), "慢任务与排队任务应在限时内完成")
        assertEquals(2, executed.get())
        assertTrue(firstDone.await(1, TimeUnit.SECONDS))
    }

    @Test
    fun `超时可观察且不假装排空`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        assertTrue(PrefixIo.submit {
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                // shutdownNow 中断后允许退出
            }
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val drained = PrefixIo.shutdown(200L)
        assertFalse(drained, "超时必须返回 false，不能把 5 秒超时当成功")
        release.countDown()
    }

    @Test
    fun `旧线程不响应中断时重复关闭不误报且拒绝并行写线程`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = AtomicInteger()
        assertTrue(PrefixIo.submit {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // 模拟不响应中断的 JDBC 调用
                }
            }
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(PrefixIo.submit { queued.incrementAndGet() })

        assertFalse(PrefixIo.shutdown(100L), "首次超时必须返回 false")
        assertFalse(PrefixIo.shutdown(100L), "旧线程仍存活时重复关闭不能返回 true")
        assertFalse(PrefixIo.start(), "旧线程仍存活时不能创建第二条写线程")
        assertFalse(PrefixIo.submit { queued.incrementAndGet() }, "关闭后必须拒绝新任务")
        assertEquals(0, queued.get(), "被 shutdownNow 取消的排队任务不能执行")

        release.countDown()
        assertTrue(PrefixIo.shutdown(2_000L), "旧线程退出后应能完成关闭")
        assertTrue(PrefixIo.start(), "旧线程真正退出后应允许重新启动")
        val restarted = AtomicInteger()
        assertTrue(PrefixIo.submit { restarted.incrementAndGet() })
        assertTrue(PrefixIo.shutdown(2_000L))
        assertEquals(1, restarted.get())
    }

    @Test
    fun `被取消的排队任务收到失败回调`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val executed = AtomicBoolean(false)
        assertTrue(PrefixIo.submit {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // 模拟不响应中断的 JDBC 调用
                }
            }
        })
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertTrue(PrefixIo.submit(
            { executed.set(true) },
            onCancelled = { cancelled.set(true) },
        ))

        assertFalse(PrefixIo.shutdown(100L))
        assertTrue(cancelled.get(), "排队任务被取消时必须回调，不能无声抛弃")
        assertFalse(executed.get())
        release.countDown()
        assertTrue(PrefixIo.shutdown(2_000L))
    }

    @Test
    fun `关闭后可以重新启动`() {
        val first = AtomicInteger()
        assertTrue(PrefixIo.submit { first.incrementAndGet() })
        assertTrue(PrefixIo.shutdown(2_000L))
        assertEquals(1, first.get())

        assertTrue(PrefixIo.start())
        val second = AtomicInteger()
        assertTrue(PrefixIo.submit { second.incrementAndGet() })
        assertTrue(PrefixIo.shutdown(2_000L))
        assertEquals(1, second.get())
    }

    @Test
    fun `关闭后拒绝新任务`() {
        assertTrue(PrefixIo.shutdown(1_000L))
        assertFalse(PrefixIo.submit { error("不应执行") })
    }
}

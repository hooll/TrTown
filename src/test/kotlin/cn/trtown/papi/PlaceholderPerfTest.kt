package cn.trtown.papi

import cn.trtown.store.ThreadGuard
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-25：PAPI 变量单次求值 < 1 µs，且零 DB。
 *
 * 这里测的是 PAPI 真正走的路径（`PlaceholderCache.get`），
 * 缓存命中时只做一次 HashMap 读 + 字符串返回。
 */
@Tag("perf")
class PlaceholderPerfTest {

    @Test
    fun `10 万次求值小于 100ms 且零 DB`() {
        val uuid = UUID.randomUUID()
        PlaceholderCache.put(
            uuid,
            CachedInfo("&8[&b青云&8] &r", "青云", "青云镇", "MAYOR", "镇长", 0),
        )
        // 预热
        repeat(10_000) { PlaceholderCache.get(uuid)?.prefix?.length }
        ThreadGuard.reset()

        val start = System.nanoTime()
        var acc = 0
        repeat(100_000) { acc += PlaceholderCache.get(uuid)?.prefix?.length ?: 0 }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        assertTrue(elapsedMs < 100.0, "100,000 次求值耗时 ${elapsedMs}ms，超过 100ms 红线")
        assertEquals(0, ThreadGuard.asyncDbCalls, "PAPI 求值期间不允许访问数据库")
        assertTrue(acc > 0)
        println("[perf] 100,000 次前缀求值耗时 ${"%.2f".format(elapsedMs)} ms（${"%.3f".format(elapsedMs / 1000.0)} µs/次）")
    }
}

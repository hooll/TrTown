package cn.trtown.papi

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M-13：无镇玩家的负缓存 */
class PlaceholderNegativeCacheTest {

    @AfterEach
    fun tearDown() = PlaceholderCache.clear()

    @Test
    fun `负缓存期内不再触发刷新`() {
        val uuid = UUID.randomUUID()
        assertTrue(PlaceholderCache.shouldRefresh(uuid), "首次未命中应允许刷新")
        PlaceholderCache.markMissing(uuid)
        assertFalse(PlaceholderCache.shouldRefresh(uuid), "60 秒内不得重复查询")
        assertNull(PlaceholderCache.get(uuid))
    }

    @Test
    fun `命中缓存后清除负缓存`() {
        val uuid = UUID.randomUUID()
        PlaceholderCache.markMissing(uuid)
        PlaceholderCache.put(uuid, CachedInfo("&8[&b青云&8] &r", "青云", "青云镇", "MAYOR", "镇长", 0))
        assertNotNull(PlaceholderCache.get(uuid))
        assertTrue(PlaceholderCache.shouldRefresh(uuid))
    }

    @Test
    fun `invalidate 同时清除正负缓存`() {
        val uuid = UUID.randomUUID()
        PlaceholderCache.put(uuid, CachedInfo("x", "x", "x", "MEMBER", "成员", 0))
        PlaceholderCache.markMissing(uuid)
        PlaceholderCache.invalidate(uuid)
        assertNull(PlaceholderCache.get(uuid))
        assertTrue(PlaceholderCache.shouldRefresh(uuid))
    }
}

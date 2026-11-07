package cn.trtown.service

import cn.trtown.PrefixConfig
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PrefixConfigTest {

    @Test
    fun `缺失使用默认且不告警`() {
        var warned = false
        assertEquals(16, PrefixConfig.clampMaxLength(null, 16) { warned = true })
        assertTrue(!warned)
    }

    @Test
    fun `合法整数原样返回`() {
        assertEquals(1, PrefixConfig.clampMaxLength(1, 16) { error("不应告警") })
        assertEquals(64, PrefixConfig.clampMaxLength("64", 16) { error("不应告警") })
    }

    @Test
    fun `越界钳制并告警`() {
        var message = ""
        assertEquals(1, PrefixConfig.clampMaxLength(0, 16) { message = it })
        assertTrue(message.contains("钳制"))
        assertEquals(64, PrefixConfig.clampMaxLength(999, 16) { message = it })
        assertTrue(message.contains("钳制"))
    }

    @Test
    fun `类型错误回退默认并告警`() {
        var message = ""
        assertEquals(16, PrefixConfig.clampMaxLength("abc", 16) { message = it })
        assertTrue(message.contains("使用默认值"))
        assertEquals(16, PrefixConfig.clampMaxLength(12.5, 16) { message = it })
        assertTrue(message.contains("使用默认值"))
    }
}

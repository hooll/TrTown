package cn.trtown.gui

import cn.trtown.Settings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefixOpenRequestsTest {

    private val uuid = UUID.randomUUID()

    @AfterEach
    fun tearDown() {
        PrefixOpenRequests.clearForTest()
    }

    @Test
    fun `同一请求与窗口仍有效`() {
        val window = Any()
        val request = PrefixOpenRequests.begin(uuid, window)
        assertTrue(PrefixOpenRequests.isCurrent(uuid, request))
        PrefixOpenRequests.complete(uuid, request)
        assertFalse(PrefixOpenRequests.isCurrent(uuid, request))
    }

    @Test
    fun `新请求使旧请求失效`() {
        val first = PrefixOpenRequests.begin(uuid, Any())
        val second = PrefixOpenRequests.begin(uuid, Any())
        assertFalse(PrefixOpenRequests.isCurrent(uuid, first))
        assertTrue(PrefixOpenRequests.isCurrent(uuid, second))
    }

    @Test
    fun `关闭只取消原窗口的请求`() {
        val firstWindow = Any()
        val secondWindow = Any()
        val first = PrefixOpenRequests.begin(uuid, firstWindow)
        PrefixOpenRequests.cancelIfWindow(uuid, firstWindow)
        assertFalse(PrefixOpenRequests.isCurrent(uuid, first))

        val second = PrefixOpenRequests.begin(uuid, secondWindow)
        PrefixOpenRequests.cancelIfWindow(uuid, firstWindow)
        assertTrue(PrefixOpenRequests.isCurrent(uuid, second))
        PrefixOpenRequests.cancelIfWindow(uuid, secondWindow)
        assertFalse(PrefixOpenRequests.isCurrent(uuid, second))
    }

    @Test
    fun `配置代次不符时请求失效`() {
        val request = PrefixOpenRequests.begin(uuid, Any(), Settings.generation - 1)
        assertFalse(PrefixOpenRequests.isCurrent(uuid, request))
    }

    @Test
    fun `退出与全量失效清掉请求`() {
        val request = PrefixOpenRequests.begin(uuid, Any())
        PrefixOpenRequests.onQuit(uuid)
        assertFalse(PrefixOpenRequests.isCurrent(uuid, request))

        val another = PrefixOpenRequests.begin(uuid, Any())
        PrefixOpenRequests.invalidateAll()
        assertFalse(PrefixOpenRequests.isCurrent(uuid, another))
    }
}

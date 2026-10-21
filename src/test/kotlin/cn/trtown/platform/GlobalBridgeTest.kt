package cn.trtown.platform

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-49 ①：`GlobalBridge` 四例（M-23 回归闸门）。
 *
 * 纯 JVM：线程探测与调度都是注入的，不启动服务器、不碰 Bukkit。
 * 这四条覆盖的正是真服踩过的坑 —— 事件必须在**全局线程**触发，而我们的调用来自 async 线程。
 */
class GlobalBridgeTest {

    private fun onThread(name: String, block: () -> Unit): Thread =
        Thread(block, name).apply { start() }

    @Test
    fun `已在全局线程时内联执行且不走调度`() {
        var dispatched = 0
        var ranOn = ""

        onThread("global-test") {
            GlobalBridge.run(
                isGlobal = { Thread.currentThread().name == "global-test" },
                dispatch = { dispatched++ },
            ) {
                ranOn = Thread.currentThread().name
            }
        }.join(2_000)

        // 内联分支不是优化：全局线程里再 runGlobal 就是等自己，10 秒后超时
        assertEquals(0, dispatched, "已在全局线程却还去调度，会死锁")
        assertEquals("global-test", ranOn)
    }

    @Test
    fun `不在全局线程时调度执行并把返回值带回来`() {
        var dispatched = 0

        val value = GlobalBridge.run(
            isGlobal = { false },
            dispatch = { task ->
                dispatched++
                onThread("global-worker") { task() }.join(2_000)
            },
        ) { Thread.currentThread().name }

        assertEquals(1, dispatched)
        assertEquals("global-worker", value, "block 必须在被调度的线程上跑，结果要带回调用线程")
    }

    @Test
    fun `block 抛出的异常原样抛给调用方`() {
        val ex = assertFailsWith<IllegalStateException> {
            GlobalBridge.run(
                isGlobal = { false },
                dispatch = { task -> onThread("global-worker") { task() }.join(2_000) },
            ) { throw IllegalStateException("may only be triggered synchronously.") }
        }
        assertEquals("may only be triggered synchronously.", ex.message)
    }

    @Test
    fun `dispatch 从不执行时按超时抛 GlobalBridgeTimeout`() {
        val started = System.currentTimeMillis()
        val ex = assertFailsWith<GlobalBridgeTimeout> {
            GlobalBridge.run(
                isGlobal = { false },
                dispatch = { /* 永远不跑：模拟全局线程被占满 / 插件正在卸载 */ },
                timeoutMs = 80,
            ) { 1 }
        }
        assertTrue(ex.message!!.contains("80"), "超时文案要带毫秒数：${ex.message}")
        assertTrue(System.currentTimeMillis() - started >= 70, "不能立刻返回，必须真的等过超时")
    }

    @Test
    fun `Bridge 绑定实例在超时时把 op 写进文案`() {
        val bridge = GlobalBridge.bind(isGlobal = { false }, dispatch = { /* 不执行 */ }, timeoutMs = 80)
        val ex = assertFailsWith<GlobalBridgeTimeout> { bridge.run("createGroup(TrTown_1_MAYOR)") { 1 } }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.startsWith("createGroup(TrTown_1_MAYOR)"), "定位不到是哪一步卡住：${ex.message}")
    }
}

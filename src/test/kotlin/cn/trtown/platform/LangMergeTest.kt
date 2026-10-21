package cn.trtown.platform

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M-28：升级后新增的语言键必须自动补进磁盘文件，**已有条目不许被覆盖**。
 *
 * 真服现象：被邀请人看到的不是中文，而是原始键名 `town.invite.received` ——
 * 插件不覆盖已存在的 `plugins/TrTown/lang/zh_CN.yml`，新 jar 的 6 条新键从未落盘。
 */
class LangMergeTest {

    private val old = """
        # TrTown 中文消息
        # 支持 & 颜色码；{0} {1} 为参数占位符

        common.player-offline: "&c找不到在线玩家：{0}"
        town.invite.success: "&a已邀请 &b{0}"
        town.accept.success: "&a已加入小镇：&b{0}"
    """.trimIndent()

    private val new = """
        # TrTown 中文消息
        # 支持 & 颜色码；{0} {1} 为参数占位符

        common.player-offline: "&c找不到在线玩家：{0}"
        town.invite.success: "&a已邀请 &b{0}"
        town.accept.success: "&a已加入小镇：&b{0}"
        town.invite.received: "&e{1} &7邀请你加入小镇 &b{0}&7，输入 &a/tt accept &7直接加入"
        town.accept.none: "&7你没有待处理的邀请"
    """.trimIndent()

    @Test
    fun `缺失的键被追加且原有内容逐字保留`() {
        val plan = LangMerge.merge(old, new)

        assertTrue(plan.changed)
        assertEquals(listOf("town.invite.received", "town.accept.none"), plan.appended)
        // 原有行原样保留（包括注释与顺序）
        assertTrue(plan.text.startsWith(old), "已有内容被改动了：\n${plan.text}")
        assertTrue(plan.text.contains("town.invite.received: \"&e{1} &7邀请你加入小镇 &b{0}&7，输入 &a/tt accept &7直接加入\""))
        assertEquals(
            LangMerge.keysOf(old) + setOf("town.invite.received", "town.accept.none"),
            LangMerge.keysOf(plan.text),
        )
    }

    @Test
    fun `用户改过的文案不被覆盖`() {
        val customised = old.replace("&a已邀请 &b{0}", "&a你邀请了 &b{0}（本服自定义）")
        val plan = LangMerge.merge(customised, new)

        assertTrue(plan.text.contains("本服自定义"), "用户改过的值被覆盖了")
        assertEquals(1, plan.text.lines().count { it.trimStart().startsWith("town.invite.success:") })
    }

    @Test
    fun `键齐全时不改动文件`() {
        val plan = LangMerge.merge(new, new)
        assertFalse(plan.changed)
        assertTrue(plan.appended.isEmpty())
        assertEquals(new, plan.text)
    }

    @Test
    fun `注释与空行不会被当成键`() {
        assertEquals(
            setOf("a.b", "c.d"),
            LangMerge.keysOf("# x: y\n\n  a.b : \"1\"\nc.d: 2\n"),
        )
    }

    /**
     * 走真实文件 + 真实 jar 内资源（`/lang/zh_CN.yml` 在测试类路径上）。
     *
     * 这一条覆盖的是接线末端：读资源、读磁盘、写回。上面几条只测纯函数。
     */
    /**
     * M-31：反复补键不能把 HEADER 注释越堆越多。
     *
     * 上一版每次追加都写一遍 HEADER，而 header 以 `#` 开头、`keyOf()` 返回 null，
     * 永远不会被"已有键"命中，于是每补一次就多一行注释。
     */
    @Test
    fun `M-31 反复合并不会重复追加 header`() {
        val header = "# 以下条目由 TrTown 启动时自动补齐"

        // 第一次合并：补上两个新键
        val once = LangMerge.merge(old, new).text
        assertEquals(1, once.lines().count { it.trim().startsWith(header) }, "第一次合并后应有且只有一行 header")

        // 第二次合并：把其中一个键删掉，模拟下一次升级又缺一条
        val shrunk = once.replace("town.accept.none:", "# removed:")
        val twice = LangMerge.merge(shrunk, new).text
        assertEquals(1, twice.lines().count { it.trim().startsWith(header) }, "第二次合并后 header 仍只能有一行")

        // 幂等：键齐全时再合并不该改动任何东西
        assertEquals(twice, LangMerge.merge(twice, new).text)
    }

    @Test
    fun `run 会把 jar 内新增的键写进磁盘文件`() {
        val dir = kotlin.io.path.createTempDirectory("trtown-lang").toFile()
        try {
            val file = java.io.File(dir, "zh_CN.yml")
            // 模拟"旧 jar 生成的磁盘文件"：只有老键
            file.writeText(old)

            val plan = LangMerge.run(file)

            assertTrue(plan.changed)
            assertTrue(plan.appended.contains("town.invite.received"), "应补上 M-25 的新键：${plan.appended}")
            val onDisk = file.readText()
            assertTrue(onDisk.contains("town.invite.received"), "磁盘文件没被更新")
            assertTrue(onDisk.startsWith(old), "已有内容被改动了")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `文件不存在时不动手（交给 TabooLib 从 jar 生成）`() {
        val dir = kotlin.io.path.createTempDirectory("trtown-lang2").toFile()
        try {
            val file = java.io.File(dir, "missing.yml")
            val plan = LangMerge.run(file)
            assertFalse(plan.changed)
            assertFalse(file.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}

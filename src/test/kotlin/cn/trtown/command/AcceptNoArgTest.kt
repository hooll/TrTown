package cn.trtown.command

import cn.trtown.service.InviteChoice
import cn.trtown.service.PendingInvite
import cn.trtown.service.chooseInvite
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-50 ③：无参 `/tt accept` / `/tt deny`（M-25）。
 *
 * 两件事分开验：
 * 1. **命令树形状** —— 根节点有 executor、`town` 被顶成 optional，否则 `/tt accept` 会落
 *    `CommandBase` 的 else 分支打"未知或不完整的命令"（M-22 的同一类坑）；
 * 2. **选哪一条的决策** —— `chooseInvite` 是纯函数，≥2 条必须返回 [InviteChoice.Ambiguous]，
 *    绝不许"自动挑第一个"（R30：选错镇要退镇再进，走的是 Dominion 撤销链路）。
 */
class AcceptNoArgTest {

    private val tree = CommandTreeTest()

    @Test
    fun `AC-50 ③ accept 与 deny 根节点都有 executor`() {
        val root = tree.buildTree()
        listOf("accept", "deny").forEach { name ->
            val component = assertNotNull(tree.resolve(root, name), "找不到子命令 $name")
            assertTrue(
                tree.hasExecutor(component),
                "/tt $name 无参没有 executor → 会打印『未知或不完整的命令』",
            )
        }
    }

    @Test
    fun `AC-50 ③ accept 与 deny 的 town 子节点是 optional`() {
        val root = tree.buildTree()
        listOf("accept/town", "deny/town").forEach { path ->
            val component = assertNotNull(tree.resolve(root, path), "找不到节点 $path")
            assertTrue(component.optional, "$path 应为 optional（由父节点 executor 隐式决定）")
        }
    }

    @Test
    fun `没有待处理邀请时不选中任何一条`() {
        assertEquals(InviteChoice.None, chooseInvite(emptyList()))
    }

    @Test
    fun `只有一条时直接选它`() {
        val only = pending("青云镇")
        val single = assertIs<InviteChoice.Single>(chooseInvite(listOf(only)))
        assertEquals(only, single.invite)
    }

    @Test
    fun `两条以上绝不自动挑一条`() {
        val a = pending("青云镇")
        val b = pending("白露镇")
        val ambiguous = assertIs<InviteChoice.Ambiguous>(chooseInvite(listOf(a, b)))
        assertEquals(listOf(a, b), ambiguous.invites)
    }

    private fun pending(townName: String) = PendingInvite(1, townName, UUID.randomUUID())
}

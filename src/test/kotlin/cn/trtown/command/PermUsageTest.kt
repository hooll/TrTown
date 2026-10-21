package cn.trtown.command

import org.junit.jupiter.api.Test
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.CommandStructure
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.component.CommandBase
import java.util.UUID
import kotlin.test.assertEquals

/**
 * M-22（第 10 轮审计）：**框架语义的回归测试**。
 *
 * 审计给的两个事实必须一直成立，否则 `perm` 三层缺参会退回"未知或不完整的命令"：
 * 1. 组件**先挂 `execute` 再挂子节点**时，子节点自动 `optional = true`
 *    （`CommandComponent.kt:27-31 / 46-50`）；
 * 2. 于是 `CommandBase.kt:106-116` 的三选一条件 `commandExecutor != null` 成立，
 *    **不会**走 else → `commandIncorrectCommand`。
 *
 * 这里用「等价形状的探针树」而不是真实的 `CmdPerm`：真实树的 executor 会调
 * `Cmd.reply` → `PlatformScheduler`，单测类路径没有 Bukkit（`folia-api` 是 compileOnly），
 * 会 `NoClassDefFoundError`。真实树确实有 executor 由 `CommandTreeTest` 的静态断言负责，两者互补。
 */
class PermUsageTest {

    private class FakeSender(override val name: String, val uniqueId: UUID) : ProxyCommandSender {
        override val origin: Any = this
        override var isOp: Boolean = true
        override fun isOnline(): Boolean = true
        override fun sendMessage(message: String) = Unit
        override fun performCommand(command: String): Boolean = false
        override fun hasPermission(permission: String): Boolean = true
    }

    private val sender = FakeSender("Alice", UUID.randomUUID())
    private val hits = mutableListOf<String>()
    private var incorrectCommandCalls = 0

    /** 与 `CmdPerm` 同形状：三层各挂 execute，最里层 value 也挂 execute */
    private fun buildTree(): CommandBase {
        val root = CommandBase()
        root.literal("perm") {
            execute<ProxyCommandSender> { _, _, _ -> hits += "perm" }
            dynamic("player") {
                execute<ProxyCommandSender> { _, _, _ -> hits += "perm/player" }
                dynamic("flag") {
                    execute<ProxyCommandSender> { _, _, _ -> hits += "perm/player/flag" }
                    dynamic("value") {
                        execute<ProxyCommandSender> { _, _, _ -> hits += "perm/player/flag/value" }
                    }
                }
            }
        }
        root.incorrectCommand { _, _, _, _ -> incorrectCommandCalls++ }
        return root
    }

    private fun run(vararg args: String) {
        val root = buildTree()
        val context = CommandContext(
            sender,
            CommandStructure("trtown", emptyList(), "", "", "", "", PermissionDefault.TRUE, emptyMap(), false),
            "trtown",
            root,
            false,
            arrayOf(*args),
        )
        root.execute(context)
    }

    @Test
    fun `缺参落到最近的 executor 而不是 incorrectCommand`() {
        val cases = listOf(
            arrayOf("perm") to "perm",
            arrayOf("perm", "Bob") to "perm/player",
            arrayOf("perm", "Bob", "fly") to "perm/player/flag",
        )
        cases.forEach { (args, expected) ->
            hits.clear()
            incorrectCommandCalls = 0
            run(*args)
            assertEquals(listOf(expected), hits, "输入 ${args.joinToString(" ")} 应落到 $expected")
            assertEquals(0, incorrectCommandCalls, "输入 ${args.joinToString(" ")} 不该判成未知命令")
        }
    }

    @Test
    fun `完整参数落到最里层 executor`() {
        hits.clear()
        incorrectCommandCalls = 0
        run("perm", "Bob", "fly", "on")
        assertEquals(listOf("perm/player/flag/value"), hits)
        assertEquals(0, incorrectCommandCalls)
    }

    /**
     * 真实 `CmdPerm` 树：三个 executor 必须真的被调用到。
     *
     * 单测类路径没有 Bukkit（`folia-api` 是 compileOnly），executor 一走到
     * `Cmds.usagePerm` → `Cmd.reply` 就会 `NoClassDefFoundError: org/bukkit/entity/Player`。
     * 这里**把"抛了这个错"当作"确实进了用法提示"的证据**，同时用 `incorrectCommand`
     * 探针断言**没走** else 分支 —— 两者结合才能区分"进了 executor"和"判成未知命令"。
     *
     * 若将来把 folia-api 加进 testImplementation，这条推断不再成立，直接跳过。
     */
    @Test
    fun `真实 perm 树三层缺参都进入用法提示`() {
        val bukkitOnTestClasspath = runCatching { Class.forName("org.bukkit.entity.Player") }.isSuccess
        org.junit.jupiter.api.Assumptions.assumeFalse(
            bukkitOnTestClasspath,
            "test 类路径上已有 Bukkit，本测试的前提不成立",
        )

        val root = CommandBase()
        root.literal("perm") { CmdPerm.command.func(this) }
        root.incorrectCommand { _, _, _, _ -> incorrectCommandCalls++ }

        listOf(
            arrayOf("perm"),
            arrayOf("perm", "Bob"),
            arrayOf("perm", "Bob", "fly"),
        ).forEach { args ->
            incorrectCommandCalls = 0
            val context = CommandContext(
                sender,
                CommandStructure("trtown", emptyList(), "", "", "", "", PermissionDefault.TRUE, emptyMap(), false),
                "trtown",
                root,
                false,
                arrayOf(*args),
            )
            val error = runCatching { root.execute(context) }.exceptionOrNull()

            assertEquals(0, incorrectCommandCalls, "输入 ${args.joinToString(" ")} 被判成了未知命令（AC-41）")
            val frames = error?.stackTrace?.joinToString("\n") { "${it.className}.${it.methodName}" }.orEmpty()
            assertEquals(
                "java.lang.NoClassDefFoundError", error?.javaClass?.name,
                "输入 ${args.joinToString(" ")} 应进入 usagePerm（在 Cmd.reply 处因缺 Bukkit 抛出）；实际：$error",
            )
            assertEquals(
                true, frames.contains("cmds.usageperm") || frames.contains("Cmds.usagePerm"),
                "输入 ${args.joinToString(" ")} 的调用栈里应出现 Cmds.usagePerm；实际：\n$frames",
            )
        }
    }
}

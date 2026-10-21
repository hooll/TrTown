package cn.trtown.command

import org.junit.jupiter.api.Test
import taboolib.common.platform.command.SimpleCommandBody
import taboolib.common.platform.command.component.CommandBase
import taboolib.common.platform.command.component.CommandComponent
import taboolib.common.platform.command.component.CommandComponentLiteral
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AC-42 / AC-46：命令树静态断言（PLAN §5.6.3「测试配方 B」）。
 *
 * 不启动服务器：直接读各 `CmdXxx.command`（`SimpleCommandBody`），
 * 按框架同款语义挂成 literal，再遍历整棵树。
 */
class CommandTreeTest {

    private val expected = setOf(
        "version", "help", "create", "invite", "accept", "deny", "leave", "kick", "transfer",
        "setrole", "setprefix", "prefix", "setterritory", "unsetterritory", "info", "members",
        "list", "perm", "perms", "disband", "admin",
    )

    /** 字段名 → SimpleCommandBody，与 `MainCommand` 的 `@CommandBody` 一一对应 */
    private val bodies: Map<String, SimpleCommandBody> = mapOf(
        "version" to CmdVersion.command,
        "help" to CmdHelp.command,
        "create" to CmdCreate.command,
        "invite" to CmdInvite.command,
        "accept" to CmdAccept.command,
        "deny" to CmdDeny.command,
        "leave" to CmdLeave.command,
        "kick" to CmdKick.command,
        "transfer" to CmdTransfer.command,
        "setrole" to CmdSetRole.command,
        "setprefix" to CmdSetPrefix.command,
        "prefix" to CmdPrefix.command,
        "setterritory" to CmdSetTerritory.command,
        "unsetterritory" to CmdUnsetTerritory.command,
        "info" to CmdInfo.command,
        "members" to CmdMembers.command,
        "list" to CmdList.command,
        "perm" to CmdPerm.command,
        "perms" to CmdPerms.command,
        "disband" to CmdDisband.command,
        "admin" to CmdAdmin.command,
    )

    /** 按 `SimpleCommand.kt:146-152` 的方式把 body 挂成 literal */
    private fun buildTree(): CommandBase {
        val root = CommandBase()
        bodies.forEach { (name, body) ->
            root.literal(name) { body.func(this) }
        }
        return root
    }

    @Test
    fun `AC-42 子命令集合精确为 21 个`() {
        assertEquals(21, bodies.size)
        assertEquals(expected, bodies.keys)
    }

    @Test
    fun `AC-42 含 optional 子节点的组件必须有 executor`() {
        val root = buildTree()
        val offenders = mutableListOf<String>()
        walk(root, "trtown", offenders)
        assertTrue(offenders.isEmpty(), "以下组件有 optional 子节点却没有 executor：$offenders")
    }

    @Test
    fun `AC-46 15 个节点必须挂补全且 uncheck=true`() {
        val root = buildTree()
        val required = listOf(
            "invite/player", "kick/player", "transfer/player", "perm/player", "perms/player",
            "setrole/player", "setrole/player/role", "perm/player/flag", "perm/player/flag/value",
            "accept/town", "deny/town", "info/town", "setterritory/name",
            "admin/verify/town", "admin/force-disband/town",
        )
        required.forEach { path ->
            val component = resolve(root, path)
            assertNotNull(component, "找不到节点 $path")
            assertTrue(hasSuggestion(component!!), "$path 缺少补全")
            assertEquals(true, suggestionUncheck(component), "$path 的补全必须是 uncheck=true")
        }
    }

    @Test
    fun `AC-46 自由文本节点不得有补全`() {
        val root = buildTree()
        listOf("create/name", "setprefix/text").forEach { path ->
            val component = assertNotNull(resolve(root, path), "找不到节点 $path")
            assertFalse(hasSuggestion(component!!), "$path 是自由文本，不该有补全")
        }
    }

    // ---- 树遍历工具（反射读带后缀的 internal 成员，见配方 B） ----

    private fun walk(component: CommandComponent, path: String, offenders: MutableList<String>) {
        if (component.children.isEmpty()) return
        val hasOptional = component.children.any { it.optional }
        if (hasOptional && !hasExecutor(component)) offenders += path
        component.children.forEach { child ->
            val label = when (child) {
                is CommandComponentLiteral -> child.aliases.firstOrNull() ?: "?"
                else -> (child as? taboolib.common.platform.command.component.CommandComponentDynamic)?.comment ?: "?"
            }
            walk(child, "$path/$label", offenders)
        }
    }

    private fun resolve(root: CommandBase, path: String): CommandComponent? {
        var current: CommandComponent? = root
        path.split('/').forEach { name ->
            val parent = current ?: return null
            current = parent.children.firstOrNull {
                when (it) {
                    is CommandComponentLiteral -> it.aliases.contains(name)
                    is taboolib.common.platform.command.component.CommandComponentDynamic -> it.comment == name
                    else -> false
                }
            } ?: return null
        }
        return current
    }

    private fun hasExecutor(component: CommandComponent): Boolean =
        readInternal(component, "getCommandExecutor\$common_platform_api") != null

    private fun hasSuggestion(component: CommandComponent): Boolean =
        readInternal(component, "getCommandSuggestion\$common_platform_api") != null

    private fun suggestionUncheck(component: CommandComponent): Boolean? {
        val suggestion = readInternal(component, "getCommandSuggestion\$common_platform_api") ?: return null
        return suggestion.javaClass.getMethod("getUncheck").invoke(suggestion) as? Boolean
    }

    private fun readInternal(component: CommandComponent, name: String): Any? =
        runCatching { component.javaClass.getMethod(name).invoke(component) }.getOrNull()
}

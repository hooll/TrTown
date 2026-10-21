package cn.trtown.command

import cn.trtown.service.SuggestHooks
import cn.trtown.store.ThreadGuard
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import taboolib.common.platform.ProxyCommandSender
import taboolib.common.platform.command.CommandContext
import taboolib.common.platform.command.CommandStructure
import taboolib.common.platform.command.PermissionDefault
import taboolib.common.platform.command.component.CommandBase
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * AC-47：补全结果正确且**零 DB**（PLAN §5.6.3「测试配方 A」）。
 *
 * 不启动服务器：`CommandBase` + 手搓 `CommandContext` 调 `base.suggest(...)`。
 */
class SuggestionResultTest {

    /** 极简 sender：只需要 name / origin，补全路径不会发消息 */
    private class FakeSender(override val name: String, val uniqueId: UUID) : ProxyCommandSender {
        override val origin: Any = this
        override var isOp: Boolean = false
        override fun isOnline(): Boolean = true
        override fun sendMessage(message: String) = Unit
        override fun performCommand(command: String): Boolean = false
        override fun hasPermission(permission: String): Boolean = true
    }

    private lateinit var root: CommandBase
    private val alice = FakeSender("Alice", UUID.randomUUID())

    @BeforeEach
    fun setUp() {
        root = CommandBase()
        listOf(
            "invite" to CmdInvite.command,
            "accept" to CmdAccept.command,
            "setrole" to CmdSetRole.command,
            "create" to CmdCreate.command,
            "info" to CmdInfo.command,
        ).forEach { (name, body) -> root.literal(name) { body.func(this) } }

        SuggestHooks.onlineNames = { listOf("Alice", "Bob") }
        SuggestCache.clear()
        ThreadGuard.reset()
    }

    @AfterEach
    fun tearDown() {
        SuggestHooks.onlineNames = { emptyList() }
        SuggestCache.clear()
        ThreadGuard.reset()
    }

    private fun suggest(vararg args: String): List<String>? {
        val context = CommandContext(
            alice,
            CommandStructure("trtown", emptyList(), "", "", "", "", PermissionDefault.TRUE, emptyMap(), false),
            "trtown",
            root,
            false,
            arrayOf(*args),
        )
        return root.suggest(context)
    }

    @Test
    fun `在线玩家补全排除自己`() {
        assertEquals(listOf("Bob"), suggest("invite", ""))
    }

    @Test
    fun `前缀过滤后为空时返回 null 而不是空列表`() {
        // "Al" 只命中 Alice（自己），框架过滤后为空 → null
        assertNull(suggest("invite", "Al"))
    }

    @Test
    fun `setrole 只补 manager 与 member`() {
        assertEquals(listOf("manager", "member"), suggest("setrole", "Bob", ""))
    }

    @Test
    fun `accept 用待处理邀请缓存 空缓存返回 null`() {
        assertNull(suggest("accept", ""), "缓存为空时框架应返回 null")

        SuggestCache.putPendingForTest(alice.uniqueId, listOf("青云镇"))
        assertEquals(listOf("青云镇"), suggest("accept", ""))
    }

    @Test
    fun `info 用镇名缓存`() {
        SuggestCache.putTownsForTest(listOf("青云镇", "白鹿镇"))
        assertEquals(listOf("青云镇", "白鹿镇"), suggest("info", ""))
    }

    @Test
    fun `自由文本命令没有补全`() {
        assertNull(suggest("create", ""))
    }

    @Test
    fun `全部补全在主线程上下文执行且零 DB`() {
        SuggestHooks.onlineNames = { listOf("Alice", "Bob") }
        SuggestCache.putTownsForTest(listOf("青云镇"))
        SuggestCache.putPendingForTest(alice.uniqueId, listOf("青云镇"))
        SuggestCache.putTerritoriesForTest(alice.uniqueId, listOf("我的领地"))

        ThreadGuard.enterMain()
        try {
            listOf(
                arrayOf("invite", ""),
                arrayOf("accept", ""),
                arrayOf("setrole", "Bob", ""),
                arrayOf("info", ""),
                arrayOf("create", ""),
            ).forEach { args -> suggest(*args) }
        } finally {
            ThreadGuard.exitMain()
        }

        assertEquals(0, ThreadGuard.mainThreadDbCalls, "补全不允许访问数据库")
        assertEquals(0, ThreadGuard.asyncDbCalls, "补全不允许触发任何异步 DB 访问")
    }
}

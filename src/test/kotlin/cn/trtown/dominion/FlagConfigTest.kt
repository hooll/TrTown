package cn.trtown.dominion

import cn.trtown.model.TownRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M-26：启动校验必须与运行期用**同一个判据**（`Flags.getAllPriFlags()`）。
 *
 * 上一版把 `Flags` 类的静态常量反射进来取并集，于是 `container` 这种"有常量、没注册"
 * 的名字启动时放行、绑定时才炸（真服实测 `/dominion group_set_flag ... container true`
 * → `Unknown privilege flag: container.`）。
 *
 * 这些用例注入 `knownFlagNames`，所以不需要 Dominion 在测试类路径上。
 */
class FlagConfigTest {

    private val known = setOf("place", "break", "chest", "fly", "admin", "teleport")

    private fun roles(vararg pairs: Pair<TownRole, Map<String, Boolean>>): Map<TownRole, Map<String, Boolean>> {
        val base = mapOf(
            TownRole.MEMBER to emptyMap<String, Boolean>(),
            TownRole.MANAGER to emptyMap<String, Boolean>(),
            TownRole.MAYOR to emptyMap<String, Boolean>(),
        )
        return base + pairs.toMap()
    }

    @Test
    fun `M-26 roles 里出现未注册的 flag 名必须启动失败并指出具体项`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                roleFlags = roles(TownRole.MEMBER to mapOf("place" to true, "container" to true)),
                editableFlags = emptyList(),
                aliases = emptyMap(),
                knownFlagNames = known,
            )
        }
        val message = ex.message!!
        assertTrue(message.contains("dominion.roles.MEMBER.container"), "报错必须点名到具体项：$message")
        assertTrue(message.contains("container"), message)
    }

    @Test
    fun `M-26 editable-flags 与 aliases 里的未注册名同样启动失败`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                roleFlags = roles(TownRole.MEMBER to mapOf("place" to true)),
                editableFlags = listOf("fly", "container"),
                aliases = mapOf("容器" to "container"),
                knownFlagNames = known,
            )
        }
        val message = ex.message!!
        assertTrue(message.contains("permissions.editable-flags.container"), message)
        assertTrue(message.contains("permissions.aliases.容器"), message)
    }

    @Test
    fun `合法配置按阶级并集展开且 editable 只保留 roles 里出现过的项`() {
        val validated = FlagConfig.validate(
            roleFlags = roles(
                TownRole.MEMBER to mapOf("place" to true, "break" to true),
                TownRole.MANAGER to mapOf("fly" to true),
            ),
            editableFlags = listOf("fly", "teleport"),
            aliases = emptyMap(),
            knownFlagNames = known,
        )

        assertEquals(setOf("place", "break"), validated.effective.getValue(TownRole.MEMBER).keys)
        assertEquals(setOf("place", "break", "fly"), validated.effective.getValue(TownRole.MANAGER).keys)
        // MAYOR 空表 → 继承 MANAGER 全部
        assertEquals(setOf("place", "break", "fly"), validated.effective.getValue(TownRole.MAYOR).keys)
        // teleport 合法但没在任何阶级出现 → 丢弃 + WARN
        assertEquals(setOf("fly"), validated.editable)
        assertTrue(validated.warnings.any { it.contains("teleport") }, "丢弃要留痕：${validated.warnings}")
    }

    @Test
    fun `三档必须递进`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                roleFlags = roles(
                    TownRole.MEMBER to mapOf("fly" to true),
                    TownRole.MANAGER to mapOf("fly" to false),
                ),
                editableFlags = emptyList(),
                aliases = emptyMap(),
                knownFlagNames = known,
            )
        }
        assertTrue(ex.message!!.contains("三档必须递进"), ex.message!!)
    }
}

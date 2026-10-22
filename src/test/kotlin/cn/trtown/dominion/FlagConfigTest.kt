package cn.trtown.dominion

import cn.trtown.model.TownRole
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * M-26 / v1.25：启动校验必须与运行期用**同一个判据**（`Flags.getAllPriFlags()`），
 * 且 `dominion.roles.*.presets` 必须能从 Dominion 的 `flags.yml` 展开。
 *
 * 这些用例注入 `presetFlags` 与 `knownFlagNames`，所以不需要 Dominion 在测试类路径上。
 */
class FlagConfigTest {

    private val known = setOf("place", "break", "chest", "fly", "admin", "resize", "rename_dominion")

    private fun presets(
        member: List<String> = emptyList(),
        manager: List<String> = listOf("administration"),
        mayor: List<String> = emptyList(),
    ): Map<TownRole, List<String>> = mapOf(
        TownRole.MEMBER to member,
        TownRole.MANAGER to manager,
        TownRole.MAYOR to mayor,
    )

    private val presetFlags = mapOf(
        "administration" to listOf("admin", "resize", "rename_dominion"),
        "movement" to listOf("fly"),
        "broken" to emptyList<String>(),
    )

    @Test
    fun `M-26 preset 展开出未注册的 flag 必须启动失败并指出具体项`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                rolePresets = presets(manager = listOf("administration")),
                presetFlags = presetFlags + ("administration" to listOf("admin", "container")),
                knownFlagNames = known,
            )
        }
        val message = ex.message!!
        assertTrue(message.contains("dominion.roles.MANAGER.presets.administration.container"), message)
        assertTrue(message.contains("container"), message)
    }

    @Test
    fun `preset 名不存在时启动失败并列出可用预设`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                rolePresets = presets(manager = listOf("no_such_preset")),
                presetFlags = presetFlags,
                knownFlagNames = known,
            )
        }
        assertTrue(ex.message!!.contains("no_such_preset"), ex.message!!)
        assertTrue(ex.message!!.contains("administration"), "报错要列出可用预设：${ex.message}")
    }

    @Test
    fun `非空 presets 展开为空时启动失败`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                rolePresets = presets(manager = listOf("broken")),
                presetFlags = presetFlags,
                knownFlagNames = known,
            )
        }
        assertTrue(ex.message!!.contains("展开出 0 个 flag"), ex.message!!)
    }

    @Test
    fun `空 presets 合法且按阶级并集展开`() {
        val validated = FlagConfig.validate(
            rolePresets = presets(),
            presetFlags = presetFlags,
            knownFlagNames = known,
        )

        assertEquals(emptySet(), validated.effective.getValue(TownRole.MEMBER).keys)
        assertEquals(setOf("admin", "resize", "rename_dominion"), validated.effective.getValue(TownRole.MANAGER).keys)
        // MAYOR presets 为空 → 继承 MANAGER
        assertEquals(setOf("admin", "resize", "rename_dominion"), validated.effective.getValue(TownRole.MAYOR).keys)
        assertEquals(setOf("admin", "resize", "rename_dominion"), validated.managed)
    }

    @Test
    fun `MEMBER 预设非空时也会被展开`() {
        val validated = FlagConfig.validate(
            rolePresets = presets(member = listOf("movement")),
            presetFlags = presetFlags,
            knownFlagNames = known,
        )

        assertEquals(setOf("fly"), validated.effective.getValue(TownRole.MEMBER).keys)
        assertEquals(setOf("fly", "admin", "resize", "rename_dominion"), validated.effective.getValue(TownRole.MANAGER).keys)
    }

    @Test
    fun `M-44 presets 里直接写单个 flag 名也能展开（teleport 不是预设组）`() {
        val validated = FlagConfig.validate(
            rolePresets = presets(member = listOf("teleport"), manager = listOf("administration")),
            presetFlags = presetFlags + ("movement" to listOf("move", "teleport", "fly")),
            knownFlagNames = known + "teleport" + "move",
        )

        // MEMBER 只拿到 teleport 这一项，不该顺带拿到 movement 组里的 fly/move
        assertEquals(setOf("teleport"), validated.effective.getValue(TownRole.MEMBER).keys)
        assertEquals(
            setOf("teleport", "admin", "resize", "rename_dominion"),
            validated.effective.getValue(TownRole.MANAGER).keys,
        )
        // 启动日志用的是原始 presets，必须还是 `teleport`（AUDIT §24.10 闸门 ③）
        assertEquals(listOf("teleport"), validated.presets.getValue(TownRole.MEMBER))
    }

    @Test
    fun `M-44 既不是组名也不是 flag 名时仍然启动失败`() {
        val ex = assertFailsWith<ConfigException> {
            FlagConfig.validate(
                rolePresets = presets(member = listOf("teleprot")),
                presetFlags = presetFlags,
                knownFlagNames = known,
            )
        }
        assertTrue(ex.message!!.contains("teleprot"), ex.message!!)
    }
}

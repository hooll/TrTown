package cn.trtown.service

import cn.trtown.model.Member
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrefixServiceTest {

    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private var townId: Int = 0
    private val owner: UUID = UUID.randomUUID()
    private val manager: UUID = UUID.randomUUID()
    private val member: UUID = UUID.randomUUID()
    private val invalidated = mutableListOf<UUID>()

    private val rules = PrefixRules(
        enabled = true,
        maxLength = 16,
        allowColor = true,
        displayFormat = "&8[&b%s&8] &r",
        fallbackToName = true,
        denyChars = listOf("%", "{", "}", "[", "]", "§"),
    )

    @BeforeEach
    fun setUp() {
        repos = TestSupport.repos(TestSupport.store())
        towns = TownService(repos, TestSupport.rules)
        val town = towns.create(owner, "青云镇")
        townId = town.id
        repos.members.insert(Member(town.id, manager, TownRole.MANAGER, emptyMap(), 1_000L))
        repos.members.insert(Member(town.id, member, TownRole.MEMBER, emptyMap(), 1_000L))
        invalidated.clear()
        CacheHooks.invalidator = { invalidated += it }
        PrefixSaves.clearForTest()
    }

    @AfterEach
    fun tearDown() {
        CacheHooks.invalidator = {}
        PrefixSaves.clearForTest()
        TestSupport.cleanup()
    }

    private fun service(rules: PrefixRules = this.rules) = PrefixService(repos, rules)

    private fun auditCount(): Int = repos.audit.listByTown(townId).size

    @Test
    fun `普通成员不能修改前缀`() {
        assertFailsWith<TownException> { service().set(member, "&a测试") }
        assertNull(repos.towns.findById(townId)?.prefix)
    }

    @Test
    fun `管理可以修改且全镇缓存失效`() {
        service().set(manager, "&b青云")
        assertEquals("&b青云", repos.towns.findById(townId)?.prefix)
        assertTrue(invalidated.contains(owner))
        assertTrue(invalidated.contains(manager))
        assertTrue(invalidated.contains(member))
    }

    @Test
    fun `命令 reset 忽略大小写写 null`() {
        val prefix = service()
        prefix.set(owner, "&b青云")
        val before = auditCount()
        assertNull(prefix.set(owner, "ReSeT"))
        assertNull(repos.towns.findById(townId)?.prefix)
        assertEquals(before + 1, auditCount())
    }

    @Test
    fun `GUI 正文 reset 按普通文字保存`() {
        val prefix = service()
        val target = prefix.openTarget(owner)
        val result = prefix.saveDraft(
            owner, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "reset",
        )
        assertEquals(PrefixSaveResult.Saved("reset"), result)
        assertEquals("reset", repos.towns.findById(townId)?.prefix)
    }

    @Test
    fun `未改草稿保存无变化且零审计`() {
        val prefix = service()
        prefix.set(owner, "&b青云")
        val target = prefix.openTarget(owner)
        val beforeAudit = auditCount()
        val beforeInvalidated = invalidated.size

        val result = prefix.saveDraft(
            owner, townId, target.originalPrefix, reset = false, draftChanged = false, draftSource = target.draftRaw,
        )
        assertEquals(PrefixSaveResult.Unchanged, result)
        assertEquals(beforeAudit, auditCount())
        assertEquals(beforeInvalidated, invalidated.size)
    }

    @Test
    fun `其他管理先保存时过时草稿冲突且不覆盖`() {
        val prefix = service()
        prefix.set(owner, "&b初始")
        val target = prefix.openTarget(manager)
        val beforeAudit = auditCount()

        prefix.set(owner, "&c新值")
        val afterOtherSave = auditCount()
        val result = prefix.saveDraft(
            manager, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a过时",
        )

        assertEquals(PrefixSaveResult.Conflict, result)
        assertEquals("&c新值", repos.towns.findById(townId)?.prefix)
        assertEquals(afterOtherSave, auditCount())
        assertTrue(afterOtherSave > beforeAudit)
    }

    @Test
    fun `降级后不能保存`() {
        val prefix = service()
        val target = prefix.openTarget(manager)
        repos.members.updateRole(townId, manager, TownRole.MEMBER)
        assertFailsWith<TownException> {
            prefix.saveDraft(
                manager, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a新",
            )
        }
        assertNull(repos.towns.findById(townId)?.prefix)
    }

    @Test
    fun `解散后不能保存`() {
        val prefix = service()
        val target = prefix.openTarget(owner)
        repos.towns.updateState(townId, TownState.DISBANDED)
        assertFailsWith<TownException> {
            prefix.saveDraft(
                owner, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a新",
            )
        }
        assertNull(repos.towns.findById(townId)?.prefix)
    }

    @Test
    fun `被踢后不能保存`() {
        val prefix = service()
        val target = prefix.openTarget(manager)
        repos.members.delete(townId, manager)
        assertFailsWith<TownException> {
            prefix.saveDraft(
                manager, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a新",
            )
        }
        assertNull(repos.towns.findById(townId)?.prefix)
    }

    @Test
    fun `换到其他镇后不能写原镇`() {
        val prefix = service()
        val target = prefix.openTarget(manager)
        val other = UUID.randomUUID()
        val another = towns.create(other, "白云镇")
        repos.members.delete(townId, manager)
        repos.members.insert(Member(another.id, manager, TownRole.MANAGER, emptyMap(), 1_000L))

        assertFailsWith<TownException> {
            prefix.saveDraft(
                manager, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a新",
            )
        }
        assertNull(repos.towns.findById(townId)?.prefix)
        assertNull(repos.towns.findById(another.id)?.prefix)
    }

    @Test
    fun `关闭颜色时服务统一去色`() {
        val noColor = rules.copy(allowColor = false)
        val prefix = service(noColor)
        assertEquals("青云", prefix.set(owner, "&a&l青云"))
        assertEquals("青云", repos.towns.findById(townId)?.prefix)
        assertEquals("青云", prefix.rawOf(assertNotNull(repos.towns.findById(townId))))
    }

    @Test
    fun `可见长度按 code point 且颜色不计数`() {
        val short = rules.copy(maxLength = 4)
        val prefix = service(short)
        prefix.set(owner, "&a😀😀😀😀")
        assertFailsWith<TownException> { prefix.set(owner, "&a😀😀😀😀😀") }
    }

    @Test
    fun `禁用字符与百分号拒绝`() {
        val prefix = service()
        assertFailsWith<TownException> { prefix.set(owner, "青%云") }
        assertFailsWith<TownException> { prefix.set(owner, "青[云") }
    }

    @Test
    fun `raw 硬上限拒绝`() {
        val prefix = service()
        assertFailsWith<TownException> { prefix.set(owner, "a".repeat(PrefixRules.MAX_RAW_UNITS + 1)) }
    }

    @Test
    fun `16 字逐字渐变 raw 超过 64 可保存`() {
        val prefix = service()
        val raw = buildString {
            repeat(16) { index ->
                append("&#FF0000")
                append(('a'.code + index).toChar())
            }
        }
        assertTrue(raw.length > 64)
        prefix.set(owner, raw)
        val stored = repos.towns.findById(townId)?.prefix
        assertNotNull(stored)
        assertTrue(stored.length > 64)
        assertEquals(raw.uppercase(), stored.uppercase())
    }

    @Test
    fun `单飞时第二次保存返回 Busy`() {
        val prefix = service()
        val target = prefix.openTarget(owner)
        assertTrue(PrefixSaves.begin(owner))
        try {
            val result = prefix.saveDraft(
                owner, townId, target.originalPrefix, reset = false, draftChanged = true, draftSource = "&a新",
            )
            assertEquals(PrefixSaveResult.Busy, result)
        } finally {
            PrefixSaves.end(owner)
        }
    }

    @Test
    fun `预览与回退开关一致`() {
        val fallbackTrue = rules
        val fallbackFalse = rules.copy(fallbackToName = false)
        assertEquals("&8[&b青云镇&8] &r", PrefixService.previewOf(null, "青云镇", fallbackTrue))
        assertEquals("", PrefixService.previewOf(null, "青云镇", fallbackFalse))
        assertEquals("&8[&b&a自定义&8] &r", PrefixService.previewOf("&a自定义", "青云镇", fallbackTrue))
    }

    @Test
    fun `关闭颜色时预览与真实渲染一致`() {
        val noColor = rules.copy(allowColor = false)
        val prefix = service(noColor)
        repos.towns.updatePrefix(townId, "&cA")
        val town = assertNotNull(repos.towns.findById(townId))
        val rendered = prefix.render(town)
        assertEquals("&8[&bA&8] &r", rendered)
        assertEquals(rendered, PrefixService.previewOf("&cA", "青云镇", noColor))
        // 数据库原值不被预览改写；重新打开颜色后仍能读到原保存内容
        assertEquals("&cA", repos.towns.findById(townId)?.prefix)
        assertEquals("&8[&b&cA&8] &r", service(rules).render(assertNotNull(repos.towns.findById(townId))))
    }

    @Test
    fun `外壳颜色取 display-format 中占位符前最后一个颜色码`() {
        assertEquals("&b", PrefixValidator.shellColor(rules))
        assertEquals("&f", PrefixValidator.shellColor(rules.copy(displayFormat = "%s")))
        assertEquals("&#FF0000", PrefixValidator.shellColor(rules.copy(displayFormat = "&8[&#FF0000%s&8]")))
        assertEquals("&f", PrefixValidator.shellColor(rules.copy(displayFormat = "&8[&r%s&8]")))
    }

    @Test
    fun `恢复默认写 null 后 raw 回退镇名`() {
        val prefix = service()
        prefix.set(owner, "&b青云")
        prefix.set(owner, "reset")
        val town = assertNotNull(repos.towns.findById(townId))
        assertNull(town.prefix)
        assertEquals("青云镇", prefix.rawOf(town))
        assertEquals("&8[&b青云镇&8] &r", prefix.render(town))
    }
}

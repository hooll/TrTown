package cn.trtown.service

import cn.trtown.model.TownRole
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/** AC-4：建镇规则 */
class TownServiceTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var service: TownService
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        service = TownService(repos, TestSupport.rules) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    @Test
    fun `正常建镇后镇长是 MAYOR`() {
        val owner = UUID.randomUUID()
        val town = service.create(owner, "青云镇")
        assertEquals("青云镇", town.name)
        assertEquals(owner, town.ownerUuid)
        val member = assertNotNull(repos.members.find(town.id, owner))
        assertEquals(TownRole.MAYOR, member.role)
        assertEquals(1, repos.audit.listByTown(town.id).size)
    }

    @Test
    fun `非法镇名被拒`() {
        val owner = UUID.randomUUID()
        listOf("ab", "a b", "带 空格", "名字名字名字名字名字名字名字名字名字", "").forEach { bad ->
            val ex = assertFailsWith<TownException>("应拒绝非法名: $bad") { service.create(owner, bad) }
            assertEquals(TownError.NAME_INVALID, ex.error)
        }
    }

    @Test
    fun `重名（大小写不同）被拒`() {
        service.create(UUID.randomUUID(), "TestTown")
        val ex = assertFailsWith<TownException> { service.create(UUID.randomUUID(), "testtown") }
        assertEquals(TownError.NAME_TAKEN, ex.error)
    }

    @Test
    fun `已在镇内不能再建`() {
        val owner = UUID.randomUUID()
        service.create(owner, "TownOne")
        val ex = assertFailsWith<TownException> { service.create(owner, "TownTwo") }
        assertEquals(TownError.ALREADY_IN_TOWN, ex.error)
    }

    @Test
    fun `改角色只能镇长操作且不能设第二个镇长`() {
        val owner = UUID.randomUUID()
        val other = UUID.randomUUID()
        val town = service.create(owner, "青云镇")
        repos.members.insert(cn.trtown.model.Member(town.id, other, TownRole.MEMBER, emptyMap(), now))

        // 非镇长不能改角色
        val ex = assertFailsWith<TownException> { service.setrole(other, other, TownRole.MANAGER) }
        assertEquals(TownError.NOT_MAYOR, ex.error)

        // 不能把别人设成镇长
        val ex2 = assertFailsWith<TownException> { service.setrole(owner, other, TownRole.MAYOR) }
        assertEquals(TownError.ROLE_INVALID, ex2.error)

        service.setrole(owner, other, TownRole.MANAGER)
        assertEquals(TownRole.MANAGER, repos.members.find(town.id, other)?.role)
    }

    @Test
    fun `转让后原镇长降级、新镇长上位`() {
        val owner = UUID.randomUUID()
        val heir = UUID.randomUUID()
        val town = service.create(owner, "青云镇")
        repos.members.insert(cn.trtown.model.Member(town.id, heir, TownRole.MEMBER, emptyMap(), now))

        val updated = service.transfer(owner, heir)
        assertEquals(heir, updated.ownerUuid)
        assertEquals(TownRole.MAYOR, repos.members.find(town.id, heir)?.role)
        assertEquals(TownRole.MEMBER, repos.members.find(town.id, owner)?.role)
    }

    @Test
    fun `解散后小镇不再活跃且成员清空`() {
        val owner = UUID.randomUUID()
        val town = service.create(owner, "青云镇")
        service.disband(owner)
        assertEquals(cn.trtown.model.TownState.DISBANDED, repos.towns.findById(town.id)?.state)
        assertEquals(0, repos.members.countByTown(town.id))
    }
}

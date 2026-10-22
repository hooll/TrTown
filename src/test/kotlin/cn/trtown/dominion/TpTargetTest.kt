package cn.trtown.dominion

import cn.trtown.model.Member
import cn.trtown.model.TownRole
import cn.trtown.service.Repos
import cn.trtown.service.TerritoryService
import cn.trtown.service.TestSupport
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownService
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 小镇传送点（PLAN §5.17 / AC-58 / AC-59 / AC-61）。
 *
 * 只测服务层：传送点数据源唯一（Dominion 的 `tpLocation`），跨镇不写 TrTown 成员表。
 * 真机传送（`TeleportProvider`）由 §8.1 本地服手测覆盖。
 */
class TpTargetTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var fake: FakeDominionGateway

    private var now = 1_000L
    private var allowOthers = true

    private val dominionId = 42
    private val bindPos = BlockPosition(UUID.randomUUID(), 0.0, 64.0, 0.0)
    private val tpPos = BlockPosition(UUID.randomUUID(), 100.0, 70.0, 200.0, 90f, 0f)

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        fake = FakeDominionGateway()
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    private fun territory(): TerritoryService =
        TerritoryService(repos, fake, towns, clock = { now }, allowOthers = { allowOthers })

    /** 建一个已绑定领地的镇，镇长即 [mayor] */
    private fun boundTown(mayor: UUID, name: String): Int {
        val town = towns.create(mayor, name)
        val ref = DominionRef(dominionId, mayor, "TestLand")
        fake.addDominion(ref)
        fake.setAt(ref)
        territory().setterritory(mayor, null, bindPos)
        return town.id
    }

    private fun join(townId: Int, uuid: UUID, role: TownRole) {
        repos.members.insert(Member(townId, uuid, role, emptyMap(), now))
    }

    // ---- AC-58 / AC-59 ----

    @Test
    fun `AC-58 镇长 settp 后 Dominion 的传送点与入参一致`() {
        val mayor = UUID.randomUUID()
        boundTown(mayor, "青云镇")

        val returned = territory().settp(mayor, tpPos)

        assertEquals(tpPos, returned)
        assertEquals(tpPos, fake.tpLocationOf(dominionId), "传送点必须写进 Dominion，而不是 TrTown 本地")
    }

    @Test
    fun `AC-59 非镇长 settp 报 NOT_MAYOR`() {
        val mayor = UUID.randomUUID()
        val member = UUID.randomUUID()
        val townId = boundTown(mayor, "青云镇")
        join(townId, member, TownRole.MEMBER)

        val ex = assertFailsWith<TownException> { territory().settp(member, tpPos) }
        assertEquals(TownError.NOT_MAYOR, ex.error)
    }

    @Test
    fun `AC-59 管理员 settp 不受镇长限制`() {
        val mayor = UUID.randomUUID()
        val admin = UUID.randomUUID()
        val townId = boundTown(mayor, "青云镇")
        join(townId, admin, TownRole.MEMBER)

        territory().settp(admin, tpPos, actorIsAdmin = true)

        assertEquals(tpPos, fake.tpLocationOf(dominionId))
    }

    @Test
    fun `AC-59 未绑定领地 settp 报 TOWN_NOT_BOUND`() {
        val mayor = UUID.randomUUID()
        towns.create(mayor, "青云镇")

        val ex = assertFailsWith<TownException> { territory().settp(mayor, tpPos) }
        assertEquals(TownError.TOWN_NOT_BOUND, ex.error)
    }

    @Test
    fun `M-41 未绑定领地 tp（非跨镇）报 TOWN_NOT_BOUND 而不是 TP_NOT_SET`() {
        val mayor = UUID.randomUUID()
        towns.create(mayor, "青云镇")

        val ex = assertFailsWith<TownException> { territory().tpTarget(mayor, null) }
        assertEquals(
            TownError.TOWN_NOT_BOUND, ex.error,
            "没绑领地时应指引玩家去 setterritory，不能指引去 settp（AUDIT §24.7）",
        )
    }

    @Test
    fun `AC-59 未设传送点 tp 报 TP_NOT_SET`() {
        val mayor = UUID.randomUUID()
        boundTown(mayor, "青云镇")

        // 覆盖 M-42 的兜底分支：真实 Dominion 会把"未 settp"兜底成领地中心，
        // 这里的 fake 不模拟兜底，所以断言的是"绑了但读不到"这条兜底路径。
        val ex = assertFailsWith<TownException> { territory().tpTarget(mayor, null) }
        assertEquals(TownError.TP_NOT_SET, ex.error)
    }

    // ---- AC-61 ----

    @Test
    fun `AC-61 跨镇 tp 成功且不改变成员关系`() {
        val mayorA = UUID.randomUUID()
        val townA = boundTown(mayorA, "青云镇")
        territory().settp(mayorA, tpPos)

        // B 有自己的镇（tp 要求发起者在某个镇里），但不是 A 的成员
        val mayorB = UUID.randomUUID()
        towns.create(mayorB, "落霞镇")

        val target = territory().tpTarget(mayorB, "青云镇")

        assertEquals("青云镇", target.townName)
        assertEquals(tpPos, target.location)
        assertEquals(1, repos.members.countByTown(townA), "跨镇传送不得写入成员表")
        assertEquals(null, repos.members.find(townA, mayorB))
    }

    @Test
    fun `AC-61 跨镇目标未设传送点报 TP_TARGET_NOT_SET`() {
        val mayorA = UUID.randomUUID()
        boundTown(mayorA, "青云镇")
        val mayorB = UUID.randomUUID()
        towns.create(mayorB, "落霞镇")

        val ex = assertFailsWith<TownException> { territory().tpTarget(mayorB, "青云镇") }
        assertEquals(TownError.TP_TARGET_NOT_SET, ex.error)
    }

    @Test
    fun `AC-61 镇不存在报 TOWN_NOT_FOUND`() {
        val mayor = UUID.randomUUID()
        towns.create(mayor, "落霞镇")

        val ex = assertFailsWith<TownException> { territory().tpTarget(mayor, "根本没有这个镇") }
        assertEquals(TownError.TOWN_NOT_FOUND, ex.error)
    }

    @Test
    fun `AC-61 关闭跨镇开关后带镇名一律拒绝`() {
        allowOthers = false
        val mayorA = UUID.randomUUID()
        boundTown(mayorA, "青云镇")
        territory().settp(mayorA, tpPos)
        val mayorB = UUID.randomUUID()
        towns.create(mayorB, "落霞镇")

        val ex = assertFailsWith<TownException> { territory().tpTarget(mayorB, "青云镇") }
        assertEquals(TownError.TP_OTHERS_DISABLED, ex.error)
    }

    @Test
    fun `AC-61 关闭跨镇开关不影响回自己镇`() {
        allowOthers = false
        val mayor = UUID.randomUUID()
        boundTown(mayor, "青云镇")
        territory().settp(mayor, tpPos)

        val target = territory().tpTarget(mayor, null)

        assertEquals("青云镇", target.townName)
        assertNotNull(target.location)
    }
}

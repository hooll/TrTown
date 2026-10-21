package cn.trtown.dominion

import cn.trtown.model.SyncAction
import cn.trtown.model.TownRole
import cn.trtown.service.InviteService
import cn.trtown.service.MembershipService
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-7 / AC-51 / AC-54：绑定（无参取脚下领地）+ 原生成员模型 */
class BindingAndMembershipTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var membership: MembershipService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private lateinit var executor: SyncExecutor
    private var now = 1_000L

    private val pos = BlockPosition(UUID.randomUUID(), 0.0, 64.0, 0.0)

    private val writeCalls = setOf(
        "ensureMember", "removeMemberFromDominion", "setMemberFlag", "transferOwnership",
    )

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        membership = MembershipService(repos) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns) { now }
        executor = SyncExecutor(repos, fake, SyncSettings(5, listOf(5L, 30L)), towns) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    // ---- AC-7 ----

    @Test
    fun `AC-7 非领地 owner 无参绑定被拒且网关零写调用`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.setAt(DominionRef(42, UUID.randomUUID(), "别人的领地"))

        val ex = assertFailsWith<TownException> { territory.setterritory(owner, null, pos) }
        assertEquals(TownError.DOMINION_NOT_OWNER, ex.error)
        assertTrue(fake.callNames().none { it in writeCalls }, "校验失败时不允许任何写调用：${fake.callNames()}")
    }

    @Test
    fun `AC-7 领地已被别的镇占用时拒绝`() {
        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        towns.create(ownerA, "镇子甲")
        towns.create(ownerB, "镇子乙")
        fake.addDominion(DominionRef(42, ownerA, "甲的地"))
        fake.setAt(DominionRef(42, ownerA, "甲的地"))
        territory.setterritory(ownerA, null, pos)

        // B 试图绑同一块地（即使 B 现在是 fake 里的 owner 也不行）
        fake.addDominion(DominionRef(42, ownerB, "甲的地"))
        fake.setAt(DominionRef(42, ownerB, "甲的地"))
        val ex = assertFailsWith<TownException> { territory.setterritory(ownerB, null, pos) }
        assertEquals(TownError.DOMINION_IN_USE, ex.error)
    }

    // ---- AC-51 ----

    @Test
    fun `AC-51 站在自己领地内无参绑定成功并回报领地 id`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))

        val bound = territory.setterritory(owner, null, pos)

        assertEquals(42, bound.dominionId)
        assertEquals(42, repos.towns.findById(town.id)?.dominionId)
        assertTrue(fake.callNames().contains("resolveAt"))
        assertTrue(fake.callNames().none { it in writeCalls }, "只有镇长一人时不需要任何成员写调用：${fake.callNames()}")
    }

    @Test
    fun `AC-51 不在任何领地内时提示脚下没有领地`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.setAt(null)

        val ex = assertFailsWith<TownException> { territory.setterritory(owner, null, pos) }
        assertEquals(TownError.DOMINION_NOT_FOUND, ex.error)
        assertTrue(ex.message!!.contains("你脚下没有领地"), ex.message!!)
        assertTrue(fake.callNames().none { it in writeCalls })
    }

    @Test
    fun `AC-51 站在别人领地内拒绝且零写调用`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.setAt(DominionRef(42, UUID.randomUUID(), "别人的领地"))

        val ex = assertFailsWith<TownException> { territory.setterritory(owner, null, pos) }
        assertEquals(TownError.DOMINION_NOT_OWNER, ex.error)
        assertTrue(fake.callNames().none { it in writeCalls }, fake.callNames().toString())
    }

    // ---- AC-54 ----

    @Test
    fun `AC-54 绑定时已有成员只排 MEMBER_ADD 且不改任何 flag`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))

        territory.setterritory(owner, null, pos)

        val tasks = repos.tasks.listByTown(townId)
        assertEquals(1, tasks.count { it.action == SyncAction.MEMBER_ADD && it.targetUuid == guest })
        assertEquals(0, tasks.count { it.action == SyncAction.MEMBER_FLAGS })
        assertTrue(tasks.none { it.targetUuid == owner }, "镇长是 owner，不该有成员任务：$tasks")

        executor.runOnce()

        assertTrue(fake.isMemberDirect(42, guest))
        assertTrue(fake.flagsOfMember(42, guest).isEmpty(), "MEMBER 预设为空时 TrTown 不许改任何 flag")
        assertEquals(0, fake.countOf("setMemberFlag"))
    }

    @Test
    fun `AC-54 升管理设 administration 七项，降回成员清掉`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()

        towns.setrole(owner, guest, TownRole.MANAGER)
        executor.runOnce()
        val managerFlags = fake.flagsOfMember(42, guest)
        TestSupport.administrationFlags.keys.forEach { flag ->
            assertEquals(true, managerFlags[flag], "MANAGER 的 $flag 必须为 true：$managerFlags")
        }

        towns.setrole(owner, guest, TownRole.MEMBER)
        executor.runOnce()
        val memberFlags = fake.flagsOfMember(42, guest)
        TestSupport.administrationFlags.keys.forEach { flag ->
            assertEquals(false, memberFlags[flag], "降回 MEMBER 后 $flag 必须为 false：$memberFlags")
        }
    }

    @Test
    fun `AC-54 退镇后 Dominion 成员表里没有他`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()
        assertTrue(fake.isMemberDirect(42, guest))

        membership.leave(guest)
        executor.runOnce()

        assertTrue(!fake.isMemberDirect(42, guest), "退镇后必须 removeMember")
        assertEquals(0, repos.tasks.listByTown(townId).count { it.action == SyncAction.MEMBER_REMOVE && it.state != cn.trtown.model.SyncState.DONE })
    }

    // ---- M-38：解绑 / 换绑必须真的删旧领地成员 ----

    @Test
    fun `M-38 解绑后旧领地的成员必须被移除`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()
        assertTrue(fake.isMemberDirect(42, guest))

        territory.unsetterritory(owner)
        assertNull(repos.towns.findById(townId)?.dominionId)
        executor.runOnce()

        assertFalse(fake.isMemberDirect(42, guest), "解绑后旧领地成员必须被 removeMember")
        assertTrue(
            fake.calls.any { it.name == "removeMemberFromDominion" && it.args == listOf(42, guest) },
            "必须对旧领地真的调用 removeMember：${fake.calls}",
        )
        assertTrue(
            repos.tasks.listByTown(townId)
                .filter { it.action == SyncAction.MEMBER_REMOVE }
                .all { it.state == cn.trtown.model.SyncState.DONE },
            "移除任务必须跑完，而不是被保护条件直接收尾",
        )
    }

    @Test
    fun `M-38 换绑后旧领地清空、新领地有成员`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "旧领地"))
        fake.setAt(DominionRef(42, owner, "旧领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()
        assertTrue(fake.isMemberDirect(42, guest))

        fake.addDominion(DominionRef(43, owner, "新领地"))
        fake.setAt(DominionRef(43, owner, "新领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()

        assertEquals(43, repos.towns.findById(townId)?.dominionId)
        assertFalse(fake.isMemberDirect(42, guest), "换绑后旧领地不能再留成员")
        assertTrue(fake.isMemberDirect(43, guest), "换绑后新领地必须有成员")
    }

    @Test
    fun `M-38 同领地退镇后立刻重入不会被旧移除任务误删`() {
        val (townId, owner, guest) = townWithMember()
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()
        assertTrue(fake.isMemberDirect(42, guest))

        membership.leave(guest)
        invites.invite(owner, guest)
        invites.accept(guest, townId)
        executor.runOnce()

        assertTrue(fake.isMemberDirect(42, guest), "同一领地内重新入镇，旧 MEMBER_REMOVE 不能把新身份删掉")
    }

    // ---- helpers ----

    private fun townWithMember(): Triple<Int, UUID, UUID> {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        return Triple(town.id, owner, guest)
    }
}

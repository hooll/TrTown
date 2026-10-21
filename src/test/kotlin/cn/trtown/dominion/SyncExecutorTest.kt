package cn.trtown.dominion

import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.TownRole
import cn.trtown.service.InviteService
import cn.trtown.service.MembershipService
import cn.trtown.service.Repos
import cn.trtown.service.TestSupport
import cn.trtown.service.TownService
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-8 / AC-9 / AC-15 / AC-54：执行器与 Fake 网关（v1.25 原生成员模型） */
class SyncExecutorTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var membership: MembershipService
    private lateinit var fake: FakeDominionGateway
    private lateinit var executor: SyncExecutor
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        membership = MembershipService(repos) { now }
        fake = FakeDominionGateway()
        executor = SyncExecutor(repos, fake, SyncSettings(5, listOf(5L, 30L)), towns) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    private fun boundTown(): Triple<Int, UUID, UUID> {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 42)
        fake.addDominion(DominionRef(42, owner, "测试领地"))
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        return Triple(town.id, owner, guest)
    }

    @Test
    fun `AC-8 失败两次后成功，成员只加一次`() {
        val (townId, _, guest) = boundTown()
        repos.tasks.deleteByTown(townId)
        repos.tasks.enqueue(townId, 42, SyncAction.MEMBER_ADD, guest, null, now)

        fake.failTimes("ensureMember", 2)
        repeat(3) {
            executor.runOnce()
            now += 60_000
        }

        val tasks = repos.tasks.listByTown(townId).filter { it.targetUuid == guest }
        assertEquals(1, tasks.size)
        assertEquals(SyncState.DONE, tasks[0].state)
        assertEquals(3, fake.countOf("ensureMember"), "前两次失败、第三次成功")
        assertTrue(fake.isMemberDirect(42, guest))
    }

    @Test
    fun `AC-9 崩溃恢复：预置 PENDING 任务由新执行器跑完`() {
        val (townId, _, guest) = boundTown()
        repos.tasks.deleteByTown(townId)
        repos.tasks.enqueue(townId, 42, SyncAction.MEMBER_ADD, guest, null, now)

        val fresh = SyncExecutor(repos, fake, SyncSettings(5, listOf(5L)), towns) { now }
        fresh.runOnce()

        assertEquals(SyncState.DONE, repos.tasks.listByTown(townId).filter { it.targetUuid == guest }.single().state)
        assertTrue(fake.isMemberDirect(42, guest))
    }

    @Test
    fun `M-32 轮询会自愈已解散但 dominion_id 未清的镇`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 42)
        towns.disband(owner)

        // 模拟历史遗留：任务重试耗尽变 DEAD，收尾从此再没有触发点
        repos.tasks.listByTown(town.id).forEach {
            repos.tasks.markAttempt(it.id, "boom", 5, now, dead = true, now = now)
        }
        assertNotNull(repos.towns.findById(town.id)?.dominionId, "前置条件：解散后 dominion_id 还在")

        SyncExecutor.resetSweepThrottleForTest() // M-33 的 30 s 节流是 JVM 级共享状态
        executor.runOnce()

        assertNull(repos.towns.findById(town.id)?.dominionId, "轮询必须自己把收尾补上，否则领地永久被占")
    }

    @Test
    fun `AC-15 改角色按 MANAGER 预设设 flag，降回成员清掉`() {
        val (townId, owner, guest) = boundTown()
        executor.runOnce() // 先消化 accept 的 MEMBER_ADD
        fake.calls.clear()

        towns.setrole(owner, guest, TownRole.MANAGER)
        executor.runOnce()
        val managerFlags = fake.flagsOfMember(42, guest)
        TestSupport.administrationFlags.keys.forEach { flag ->
            assertEquals(true, managerFlags[flag], "MANAGER 的 $flag 必须为 true：$managerFlags")
        }
        assertEquals(TownRole.MANAGER, repos.members.find(townId, guest)?.role)

        towns.setrole(owner, guest, TownRole.MEMBER)
        executor.runOnce()
        val memberFlags = fake.flagsOfMember(42, guest)
        TestSupport.administrationFlags.keys.forEach { flag ->
            assertEquals(false, memberFlags[flag], "降回 MEMBER 后 $flag 必须为 false：$memberFlags")
        }
    }

    @Test
    fun `MEMBER_REMOVE 幂等且退镇后成员消失`() {
        val (townId, _, guest) = boundTown()
        executor.runOnce()
        assertTrue(fake.isMemberDirect(42, guest))

        membership.leave(guest)
        executor.runOnce()
        assertFalse(fake.isMemberDirect(42, guest))

        // 再执行一次移除（模拟重跑）不应报错
        repos.tasks.enqueue(townId, 42, SyncAction.MEMBER_REMOVE, guest, null, now)
        executor.runOnce()
        assertFalse(fake.isMemberDirect(42, guest))
    }

    @Test
    fun `MEMBER_ADD 对已退出成员是空操作`() {
        val (townId, _, guest) = boundTown()
        membership.leave(guest)
        repos.tasks.deleteByTown(townId)
        repos.tasks.enqueue(townId, 42, SyncAction.MEMBER_ADD, guest, null, now)

        executor.runOnce()

        assertEquals(0, fake.countOf("ensureMember"))
        assertEquals(SyncState.DONE, repos.tasks.listByTown(townId).single().state)
    }
}

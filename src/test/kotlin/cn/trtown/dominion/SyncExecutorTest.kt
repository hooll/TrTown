package cn.trtown.dominion

import cn.trtown.model.Grant
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.TownRole
import cn.trtown.service.InviteService
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
import kotlin.test.assertTrue

/** AC-6 / AC-8 / AC-9 / AC-15：执行器与 Fake 网关 */
class SyncExecutorTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
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
        fake = FakeDominionGateway()
        executor = SyncExecutor(
            repos, fake,
            SyncSettings(maxAttempts = 5, backoffSeconds = listOf(5L, 30L), groupNameFormat = "TrTown_%s_%s"),
            towns,
        ) { now }
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
    fun `AC-8 失败两次后成功，ensureMember 只成功调用一次`() {
        val (townId, owner, _) = boundTown()
        repos.tasks.listByTown(townId).forEach { repos.tasks.markDone(it.id) } // 先清掉 accept 的 GRANT
        repos.tasks.enqueue(townId, 42, SyncAction.GRANT, owner, null, now)

        fake.failTimes("putInGroup", 2)
        repeat(3) {
            executor.runOnce()
            now += 60_000
        }

        val tasks = repos.tasks.listByTown(townId).filter { it.targetUuid == owner }
        assertEquals(1, tasks.size)
        assertEquals(SyncState.DONE, tasks[0].state)
        assertEquals(1, fake.countOf("ensureMember"), "ensureMember 只应成功调用一次")
        assertEquals(3, fake.countOf("putInGroup"))
    }

    @Test
    fun `AC-9 崩溃恢复：预置 PENDING 任务由新执行器跑完`() {
        val (townId, owner, _) = boundTown()
        repos.tasks.listByTown(townId).forEach { repos.tasks.markDone(it.id) }
        repos.tasks.enqueue(townId, 42, SyncAction.GRANT, owner, null, now)

        val fresh = SyncExecutor(
            repos, fake,
            SyncSettings(5, listOf(5L), "TrTown_%s_%s"), towns,
        ) { now }
        fresh.runOnce()

        assertEquals(SyncState.DONE, repos.tasks.listByTown(townId).filter { it.targetUuid == owner }.single().state)
    }

    @Test
    fun `AC-15 改角色只调一次 putInGroup 并更新账本`() {
        val (townId, owner, guest) = boundTown()
        executor.runOnce() // 先消化 accept 的 GRANT
        fake.calls.clear()

        towns.setrole(owner, guest, TownRole.MANAGER)
        executor.runOnce()

        assertEquals(1, fake.countOf("putInGroup"), "换组只应调一次 putInGroup")
        val managerGroup = assertNotNull(fake.groupByName(42, "TrTown_${townId}_MANAGER"))
        assertEquals(managerGroup.id, fake.groupIdOf(42, guest))
        val grant = assertNotNull(repos.grants.find(townId, 42, guest))
        assertEquals(TownRole.MANAGER, grant.appliedRole)
        assertEquals(managerGroup.id, grant.appliedGroupId)

        towns.setrole(owner, guest, TownRole.MEMBER)
        executor.runOnce()
        val back = assertNotNull(repos.grants.find(townId, 42, guest))
        assertEquals(TownRole.MEMBER, back.appliedRole)
    }

    @Test
    fun `AC-6 pre_member=true 的成员退出时不调 removeMemberFromDominion`() {
        val (townId, _, guest) = boundTown()
        repos.tasks.deleteByTown(townId)
        val group = fake.ensureGroup(DominionRef(42, UUID.randomUUID(), "测试领地"), TownRole.MEMBER, "TrTown_${townId}_MEMBER", emptyMap())
        repos.grants.upsert(
            Grant(townId, 42, guest, preMember = true, preGroupId = 0, appliedGroupId = group.id, appliedRole = TownRole.MEMBER, memberAdded = false, groupAdded = true, appliedAt = now),
        )
        repos.tasks.enqueue(townId, 42, SyncAction.REVOKE, guest, null, now)

        executor.runOnce()

        assertTrue(fake.callNames().contains("removeFromGroup"))
        assertFalse(fake.callNames().contains("removeMemberFromDominion"), "只加不减：不得移除原有成员")
        assertEquals(0, repos.grants.listByTown(townId).size)
    }

    @Test
    fun `AC-6 pre_member=false 的成员退出时移除领地成员并恢复原组`() {
        val (townId, _, guest) = boundTown()
        repos.tasks.deleteByTown(townId)
        val group = fake.ensureGroup(DominionRef(42, UUID.randomUUID(), "测试领地"), TownRole.MEMBER, "TrTown_${townId}_MEMBER", emptyMap())
        // 模拟"进来前就在别的组 555"
        fake.ensureMember(DominionRef(42, UUID.randomUUID(), "测试领地"), guest)
        fake.putInGroup(DominionRef(42, UUID.randomUUID(), "测试领地"), 555, guest)
        fake.calls.clear()

        repos.grants.upsert(
            Grant(townId, 42, guest, preMember = false, preGroupId = 555, appliedGroupId = group.id, appliedRole = TownRole.MEMBER, memberAdded = true, groupAdded = true, appliedAt = now),
        )
        repos.tasks.enqueue(townId, 42, SyncAction.REVOKE, guest, null, now)

        executor.runOnce()

        assertTrue(fake.callNames().contains("removeMemberFromDominion"))
        val restore = fake.calls.filter { it.name == "putInGroup" }.last()
        assertEquals(555, restore.args[1])
    }

    @Test
    fun `GRANT 对已在本镇阶级组的成员是幂等的`() {
        val (townId, _, guest) = boundTown()
        executor.runOnce()
        val groupId = fake.groupIdOf(42, guest)

        repos.tasks.enqueue(townId, 42, SyncAction.GRANT, guest, null, now)
        executor.runOnce()

        assertEquals(groupId, fake.groupIdOf(42, guest))
        assertEquals(1, repos.grants.listByTown(townId).count { it.uuid == guest })
    }
}

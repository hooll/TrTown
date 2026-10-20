package cn.trtown.service

import cn.trtown.model.Grant
import cn.trtown.model.Member
import cn.trtown.model.SyncAction
import cn.trtown.model.TownGroup
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** W1：M-5 解绑闭环 / M-6 名字墓碑 / M-7 错误码 / M-9 组任务 payload；AC-31/32/33/34 */
class DisbandTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    private fun markAllDone(townId: Int) {
        repos.tasks.listByTown(townId).forEach { repos.tasks.markDone(it.id, now) }
    }

    @Test
    fun `AC-32 解散后同名新镇可以立刻创建`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        towns.disband(owner)

        val newOwner = UUID.randomUUID()
        val newTown = towns.create(newOwner, "青云镇")
        assertTrue(newTown.id != town.id)
        // 旧镇仍可按 id 查到，name 保留
        val old = assertNotNull(repos.towns.findById(town.id))
        assertEquals("青云镇", old.name)
        assertEquals(TownState.DISBANDED, old.state)
        assertTrue(repos.towns.findByNameLower("青云镇")!!.id == newTown.id)
    }

    @Test
    fun `AC-31 解散收尾后领地可以重新绑定`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 42)
        repos.grants.upsert(Grant(town.id, 42, owner, false, 0, 7, TownRole.MAYOR, true, true, now))

        towns.disband(owner)
        // 任务未完成时不能收尾
        assertEquals(false, towns.finalizeDisband(town.id))
        assertEquals(42, repos.towns.findById(town.id)?.dominionId)

        markAllDone(town.id)
        assertEquals(true, towns.finalizeDisband(town.id))
        assertNull(repos.towns.findById(town.id)?.dominionId)
        assertEquals(0, repos.grants.listByTown(town.id).size)

        // 同一块领地能绑到新镇
        val newOwner = UUID.randomUUID()
        val newTown = towns.create(newOwner, "新镇子")
        repos.towns.updateDominion(newTown.id, 42)
        assertEquals(42, repos.towns.findById(newTown.id)?.dominionId)
    }

    @Test
    fun `AC-31 收尾可重复执行`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 43)
        towns.disband(owner)
        markAllDone(town.id)
        assertTrue(towns.finalizeDisband(town.id))
        assertTrue(towns.finalizeDisband(town.id))
        assertNull(repos.towns.findById(town.id)?.dominionId)
    }

    @Test
    fun `AC-34 DELETE_GROUP 任务带 group_id 且组行在收尾后才删`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 44)
        repos.groups.upsert(TownGroup(town.id, TownRole.MAYOR, 71, "TrTown_${town.id}_MAYOR", true))
        repos.groups.upsert(TownGroup(town.id, TownRole.MEMBER, 73, "TrTown_${town.id}_MEMBER", false))

        towns.disband(owner)

        val groupTasks = repos.tasks.listByTown(town.id).filter { it.action == SyncAction.DELETE_GROUP }
        assertEquals(1, groupTasks.size, "只应为 owned=1 的组入队")
        val payload = assertNotNull(groupTasks[0].payload)
        assertTrue(payload.contains("\"group_id\":71"), "payload 必须带 group_id: $payload")
        assertTrue(payload.contains("TrTown_${town.id}_MAYOR"))

        // 组行还在，等执行器删完再收尾
        assertEquals(2, repos.groups.listByTown(town.id).size)
        markAllDone(town.id)
        assertTrue(towns.finalizeDisband(town.id))
        assertEquals(0, repos.groups.listByTown(town.id).size)
    }

    @Test
    fun `AC-33 人数上限含镇长`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        // rules.maxMembers = 3 → 镇长 + 2 人正好满
        val second = UUID.randomUUID()
        invites.invite(owner, second)
        invites.accept(second, town.id)
        val third = UUID.randomUUID()
        invites.invite(owner, third)
        invites.accept(third, town.id)

        val fourth = UUID.randomUUID()
        invites.invite(owner, fourth)
        val ex = assertFailsWith<TownException> { invites.accept(fourth, town.id) }
        assertEquals(TownError.TOWN_FULL, ex.error)
    }

    @Test
    fun `M-7 对镇长使用 setrole 报专用错误码`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val fakeMayor = UUID.randomUUID()
        // 直接造一个"第二镇长"的脏状态，验证错误码语义
        repos.members.insert(Member(town.id, fakeMayor, TownRole.MAYOR, emptyMap(), now))

        val ex = assertFailsWith<TownException> { towns.setrole(owner, fakeMayor, TownRole.MEMBER) }
        assertEquals(TownError.CANNOT_TARGET_MAYOR, ex.error)
    }

    @Test
    fun `M-10 DEAD 任务阻塞解散收尾`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 45)
        towns.disband(owner)

        val task = repos.tasks.listByTown(town.id).first()
        repos.tasks.markAttempt(task.id, "boom", 5, now, dead = true, now = now)

        assertEquals(false, towns.finalizeDisband(town.id), "DEAD 任务未清时不得收尾")
        assertEquals(45, repos.towns.findById(town.id)?.dominionId)

        // admin retry 复活后完成，才允许收尾
        repos.tasks.resetDead(now)
        repos.tasks.listByTown(town.id).forEach { repos.tasks.markDone(it.id, now) }
        assertEquals(true, towns.finalizeDisband(town.id))
        assertNull(repos.towns.findById(town.id)?.dominionId)
    }

    @Test
    fun `未绑定领地的镇解散后立即收尾且没有同步任务`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        towns.disband(owner)
        assertEquals(0, repos.tasks.listByTown(town.id).size)
        assertEquals(TownState.DISBANDED, repos.towns.findById(town.id)?.state)
    }
}

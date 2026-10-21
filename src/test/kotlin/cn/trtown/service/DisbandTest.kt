package cn.trtown.service

import cn.trtown.model.Grant
import cn.trtown.model.Member
import cn.trtown.model.SyncAction
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
    fun `AC-34 解散为每个成员入队 MEMBER_REMOVE，且任务完成前不收尾`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        repos.towns.updateDominion(town.id, 44)

        towns.disband(owner)

        val removes = repos.tasks.listByTown(town.id).filter { it.action == SyncAction.MEMBER_REMOVE }
        assertEquals(2, removes.size, "镇长与成员各一条（owner 那条在 Dominion 侧是空操作）")
        assertEquals(setOf(owner, guest), removes.mapNotNull { it.targetUuid }.toSet())
        assertEquals(false, towns.finalizeDisband(town.id), "PENDING 任务未跑完不能收尾")
        assertNotNull(repos.towns.findById(town.id)?.dominionId)

        markAllDone(town.id)
        assertTrue(towns.finalizeDisband(town.id))
        assertNull(repos.towns.findById(town.id)?.dominionId)
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

    /**
     * M-32（第 15 轮审计）：`DEAD` 是终态，**不再阻塞**解散收尾。
     *
     * 这条用例原本叫「M-10 DEAD 任务阻塞解散收尾」并断言 `false` —— 那个语义已被证伪：
     * `DEAD` 永远不会自己变回 DONE，让它阻塞就等于那块领地永远绑不到任何新镇、且不可自愈
     * （真服表现：`该领地已被其他小镇绑定（旧镇解散尚未完成，请稍后再试）`，等一辈子也不会变）。
     *
     * 新语义：收尾照做，但必须**留痕**（warning + `disband-finalized-degraded` 审计），
     * 任务行保留当证据，可见的残留优于不可见的锁死。
     */
    @Test
    fun `M-32 DEAD 任务不再阻塞解散收尾但必须留痕`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 45)
        towns.disband(owner)

        val task = repos.tasks.listByTown(town.id).first()
        repos.tasks.markAttempt(task.id, "boom", 5, now, dead = true, now = now)

        assertEquals(true, towns.finalizeDisband(town.id), "DEAD 是终态，不得永久阻塞收尾")
        assertNull(repos.towns.findById(town.id)?.dominionId, "dominion_id 必须清空，否则领地永远绑不回来")

        // 留痕：一条 degraded 审计，带上 DEAD 数量
        val degraded = repos.audit.listByTown(town.id).filter { it.action == "disband-finalized-degraded" }
        assertEquals(1, degraded.size, "跳过失败的同步必须留痕：${degraded.map { it.action }}")
        assertTrue(degraded.single().detail!!.contains("dead=1"), "审计要带数量：${degraded.single().detail}")

        // 任务行保留当证据（不能被静默删掉）
        assertEquals(1, repos.tasks.listByTown(town.id).count { it.state == cn.trtown.model.SyncState.DEAD })
    }

    @Test
    fun `M-32 PENDING 任务仍然阻塞解散收尾`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 46)
        towns.disband(owner)

        // 还有任务在跑（PENDING）→ 必须等
        assertEquals(false, towns.finalizeDisband(town.id), "PENDING 还没跑完，不能收尾")
        assertEquals(46, repos.towns.findById(town.id)?.dominionId)

        repos.tasks.listByTown(town.id).forEach { repos.tasks.markDone(it.id, now) }
        assertEquals(true, towns.finalizeDisband(town.id))
        assertNull(repos.towns.findById(town.id)?.dominionId)
    }

    /**
     * M-35：收尾必须把成员行也清掉。
     *
     * 留着的话玩家会变成"已解散小镇的镇长"：`/tt leave` 要求先转让（没有第二个人可转）、
     * `/tt create` 说"你已经在某个小镇里"、`/tt setterritory` 说"小镇已解散" —— 四条路全堵，
     * 只能管理员改库。正常 `disband()` 会删成员，但被打断或历史脏数据不会。
     */
    @Test
    fun `M-35 收尾会删掉残留的成员行`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        towns.disband(owner)
        // 模拟"删成员与改状态之间被打断"留下的脏数据
        repos.members.insert(Member(town.id, owner, TownRole.MAYOR, emptyMap(), now))

        assertEquals(true, towns.finalizeDisband(town.id))
        assertEquals(0, repos.members.countByTown(town.id), "收尾后不该还有人挂在这个镇上")
        assertNull(repos.members.findByUuid(owner), "玩家必须能重新建镇")
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

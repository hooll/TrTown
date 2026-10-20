package cn.trtown.service

import cn.trtown.model.InviteStatus
import cn.trtown.model.SyncAction
import cn.trtown.model.TownRole
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-5：邀请 / 接受 / 退出 / 踢人；以及 S2 可见的 REVOKE 入队行为 */
class InviteMembershipTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var membership: MembershipService
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        membership = MembershipService(repos) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    /** 建镇 + 拉一个人进来 */
    private fun setupTown(): Triple<Int, UUID, UUID> {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        return Triple(town.id, owner, guest)
    }

    @Test
    fun `重复邀请只保留一条 PENDING`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        now += 10_000
        val second = invites.invite(owner, guest)
        assertEquals(1, repos.invites.countPending(town.id))
        assertEquals(now + 300_000, second.expiresAt)
    }

    @Test
    fun `过期邀请不能接受`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)

        now += 301_000
        val ex = assertFailsWith<TownException> { invites.accept(guest, town.id) }
        assertEquals(TownError.INVITE_EXPIRED, ex.error)
        assertNull(repos.invites.findPending(town.id, guest))
    }

    @Test
    fun `已在其他镇的人不能再接受邀请`() {
        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        val townA = towns.create(ownerA, "镇子甲")
        val townB = towns.create(ownerB, "镇子乙")
        val guest = UUID.randomUUID()

        invites.invite(ownerA, guest)
        invites.invite(ownerB, guest)
        invites.accept(guest, townB.id)

        val ex = assertFailsWith<TownException> { invites.accept(guest, townA.id) }
        assertEquals(TownError.ALREADY_IN_TOWN, ex.error)
    }

    @Test
    fun `管理不能踢镇长`() {
        val (townId, owner, guest) = setupTown()
        towns.setrole(owner, guest, TownRole.MANAGER)

        val ex = assertFailsWith<TownException> { membership.kick(guest, owner) }
        assertEquals(TownError.NO_PERMISSION, ex.error)
        assertTrue(repos.members.find(townId, owner) != null)
    }

    @Test
    fun `管理不能踢另一个管理`() {
        val (townId, owner, first) = setupTown()
        val second = UUID.randomUUID()
        invites.invite(owner, second)
        invites.accept(second, townId)
        towns.setrole(owner, first, TownRole.MANAGER)
        towns.setrole(owner, second, TownRole.MANAGER)

        val ex = assertFailsWith<TownException> { membership.kick(first, second) }
        assertEquals(TownError.MANAGER_CANNOT_TOUCH_MANAGER, ex.error)
    }

    @Test
    fun `管理可以踢普通成员`() {
        val (townId, owner, first) = setupTown()
        val second = UUID.randomUUID()
        invites.invite(owner, second)
        invites.accept(second, townId)
        towns.setrole(owner, first, TownRole.MANAGER)

        membership.kick(first, second)
        assertNull(repos.members.find(townId, second))
    }

    @Test
    fun `镇长不能直接退出`() {
        val (_, owner, _) = setupTown()
        val ex = assertFailsWith<TownException> { membership.leave(owner) }
        assertEquals(TownError.MAYOR_MUST_TRANSFER, ex.error)
    }

    @Test
    fun `绑定领地后退出会入队 REVOKE`() {
        val (townId, _, guest) = setupTown()
        repos.towns.updateDominion(townId, 77)

        membership.leave(guest)

        val tasks = repos.tasks.listByTown(townId)
        assertEquals(1, tasks.size)
        assertEquals(SyncAction.REVOKE, tasks[0].action)
        assertEquals(guest, tasks[0].targetUuid)
        assertEquals(77, tasks[0].dominionId)
    }

    @Test
    fun `未绑定领地时不产生同步任务`() {
        val (townId, _, guest) = setupTown()
        membership.leave(guest)
        assertEquals(0, repos.tasks.listByTown(townId).size)
    }

    @Test
    fun `拒绝邀请后不再是 PENDING`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        val invite = invites.invite(owner, guest)
        invites.deny(guest, town.id)
        assertNull(repos.invites.findPending(town.id, guest))
        assertEquals(0, repos.invites.countPending(town.id))
        assertEquals(invite.id, invite.id)
    }

    @Test
    fun `改角色会入队 MOVE_GROUP（绑定时）`() {
        val (townId, owner, guest) = setupTown()
        repos.towns.updateDominion(townId, 88)
        towns.setrole(owner, guest, TownRole.MANAGER)

        val tasks = repos.tasks.listByTown(townId)
        assertTrue(tasks.any { it.action == SyncAction.MOVE_GROUP && it.targetUuid == guest })
    }
}

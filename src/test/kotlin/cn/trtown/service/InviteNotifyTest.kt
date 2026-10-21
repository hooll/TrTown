package cn.trtown.service

import cn.trtown.model.InviteStatus
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-50 ① ②：邀请通知 + `pendingOf` 三分支（M-25 的闸门）。
 *
 * 判据是"钩子被调用了且参数对"，不是"代码里有那一行"——
 * 服务层永远不碰消息通道，能证明它真的通知了被邀请人的唯一办法就是替换钩子。
 */
class InviteNotifyTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private var now = 1_000L

    /** (目标, 镇名, 邀请人) */
    private val notified = mutableListOf<Triple<UUID, String, UUID>>()

    @BeforeEach
    fun setUp() {
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        notified.clear()
        NotifyHooks.onInvited = { target, townName, inviter -> notified += Triple(target, townName, inviter) }
    }

    @AfterEach
    fun tearDown() {
        // 还原成默认空实现，避免污染同批其它测试类
        NotifyHooks.onInvited = { _, _, _ -> }
        TestSupport.cleanup()
    }

    @Test
    fun `邀请成功后通知恰好一次且参数正确`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()

        invites.invite(owner, guest)

        assertEquals(1, notified.size, "被邀请人必须收到提示，且只提示一次")
        assertEquals(Triple(guest, "青云镇", owner), notified.single())
    }

    @Test
    fun `重复邀请会再次通知`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()

        invites.invite(owner, guest)
        now += 10_000
        invites.invite(owner, guest)

        // 第二次是"刷新过期"，对方很可能没看到上一次 → 必须再提示
        assertEquals(2, notified.size)
        assertTrue(notified.all { it.first == guest && it.second == "青云镇" })
    }

    @Test
    fun `钩子未注入时不抛异常`() {
        NotifyHooks.onInvited = { _, _, _ -> }
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()

        invites.invite(owner, guest)
        assertEquals(0, notified.size)
    }

    @Test
    fun `pendingOf 没有邀请时返回空`() {
        assertTrue(invites.pendingOf(UUID.randomUUID()).isEmpty())
    }

    @Test
    fun `pendingOf 一条未过期时带镇名`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)

        val pending = invites.pendingOf(guest)
        assertEquals(1, pending.size)
        assertEquals(town.id, pending.single().townId)
        assertEquals("青云镇", pending.single().townName)
        assertEquals(owner, pending.single().inviterUuid)
    }

    @Test
    fun `pendingOf 已过期时返回空且库里置为 EXPIRED`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        val invite = invites.invite(owner, guest)

        now += 301_000
        val pending = invites.pendingOf(guest)
        assertTrue(pending.isEmpty(), "过期邀请不能算待处理，否则会出现『提示 1 条 → 接受时说已过期』")

        // 关键：查库断言，不是只看返回值 —— 返回值空也可能是别的原因
        assertEquals(InviteStatus.EXPIRED.name, statusOf(invite.id))
        assertEquals(0, repos.invites.countPending(town.id))
    }

    /** 直接读表拿 status：仓储层只暴露 PENDING 查询，验证 EXPIRED 只能看原始行 */
    private fun statusOf(inviteId: Int): String? =
        store.tables.invites.select(store.dataSource) { where("id" eq inviteId) }
            .firstOrNull { getString("status") }
}

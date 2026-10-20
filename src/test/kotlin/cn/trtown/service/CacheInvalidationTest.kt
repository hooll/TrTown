package cn.trtown.service

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertTrue

/** AC-37（M-15）：服务层变更必须触发缓存失效钩子 */
class CacheInvalidationTest {

    private val invalidated = mutableListOf<UUID>()
    private lateinit var repos: Repos
    private var now = 1_000L

    private val towns by lazy { TownService(repos, rules) { now } }
    private val invites by lazy { InviteService(repos, rules) { now } }
    private val membership by lazy { MembershipService(repos) { now } }
    private val prefix by lazy { PrefixService(repos, testPrefixRules) { now } }

    private val testPrefixRules = PrefixRules(
        enabled = true,
        maxLength = 16,
        allowColor = true,
        displayFormat = "&8[&b%s&8] &r",
        fallbackToName = true,
        denyChars = listOf("%", "{", "}", "[", "]", "§"),
    )

    private val rules = TownRules(
        nameRegex = "^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,16}$",
        maxMembers = 20,
        maxTownsPerPlayer = 1,
        inviteExpireSeconds = 300,
        maxPendingInvites = 50,
        transferDemoteTo = cn.trtown.model.TownRole.MEMBER,
        groupNameFormat = "TrTown_%s_%s",
    )

    @BeforeEach
    fun setUp() {
        repos = TestSupport.repos(TestSupport.store())
        invalidated.clear()
        CacheHooks.invalidator = { invalidated += it }
    }

    @AfterEach
    fun tearDown() {
        CacheHooks.invalidator = {}
        TestSupport.cleanup()
    }

    @Test
    fun `建镇使镇长缓存失效`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        assertTrue(invalidated.contains(owner), "建镇后必须失效镇长缓存")
    }

    @Test
    fun `接受邀请使新成员缓存失效`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invalidated.clear()

        invites.accept(guest, town.id)
        assertTrue(invalidated.contains(guest), "入镇后必须失效新成员缓存")
    }

    @Test
    fun `退出与踢人都使目标缓存失效`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        invalidated.clear()

        membership.leave(guest)
        assertTrue(invalidated.contains(guest), "退出后必须失效")

        val kicked = UUID.randomUUID()
        invites.invite(owner, kicked)
        invites.accept(kicked, town.id)
        invalidated.clear()

        membership.kick(owner, kicked)
        assertTrue(invalidated.contains(kicked), "被踢后必须失效")
    }

    @Test
    fun `改角色与转让使相关玩家缓存失效`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        invalidated.clear()

        towns.setrole(owner, guest, cn.trtown.model.TownRole.MANAGER)
        assertTrue(invalidated.contains(guest), "改角色后必须失效")

        invalidated.clear()
        towns.transfer(owner, guest)
        assertTrue(invalidated.contains(guest) && invalidated.contains(owner), "转让双方都要失效")
    }

    @Test
    fun `改前缀使全镇成员缓存失效`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        invalidated.clear()

        prefix.set(owner, "&b青云")
        assertTrue(invalidated.contains(owner) && invalidated.contains(guest), "前缀改动要让全镇失效")
    }

    @Test
    fun `解散使所有原成员缓存失效`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        invalidated.clear()

        towns.disband(owner)
        assertTrue(invalidated.contains(owner) && invalidated.contains(guest), "解散后所有成员都要失效")
    }
}

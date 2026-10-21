package cn.trtown.command

import cn.trtown.dominion.BlockPosition
import cn.trtown.dominion.DominionRef
import cn.trtown.dominion.FakeDominionGateway
import cn.trtown.dominion.SyncExecutor
import cn.trtown.dominion.SyncSettings
import cn.trtown.service.InviteService
import cn.trtown.service.Repos
import cn.trtown.service.TerritoryService
import cn.trtown.service.TestSupport
import cn.trtown.service.TownService
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertTrue

/** AC-52 / M-36：`admin verify` 必须排除 owner，并报出成员与 flag 差异 */
class AdminVerifyTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private lateinit var executor: SyncExecutor
    private var now = 1_000L

    private val pos = BlockPosition(UUID.randomUUID(), 0.0, 64.0, 0.0)

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns) { now }
        executor = SyncExecutor(repos, fake, SyncSettings(5, listOf(5L)), towns) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    @Test
    fun `AC-52 owner 在 trtown_members 但不在 dominion_member 时报 0 差异`() {
        val (townId, owner, guest) = boundTown()

        val diffs = AdminTasks.verify("青云镇", repos, fake)

        assertTrue(diffs.isEmpty(), "生产现状（owner 不在成员表）必须判为一致：$diffs")
        // 前置条件确认：owner 确实在 trtown_members，但不在 Dominion 成员表
        assertTrue(repos.members.find(townId, owner) != null)
        assertTrue(!fake.isMemberDirect(42, owner))
    }

    @Test
    fun `AC-52 多余成员与 flag 不符都会被报出`() {
        val (_, _, guest) = boundTown()
        val stranger = UUID.randomUUID()
        fake.addMemberDirect(fake.resolveById(42)!!, stranger)
        fake.setMemberFlag(fake.resolveById(42)!!, guest, "admin", true)

        val diffs = AdminTasks.verify("青云镇", repos, fake)

        assertTrue(diffs.any { it.contains("多余成员") && it.contains(stranger.toString()) }, diffs.toString())
        assertTrue(diffs.any { it.contains("flag admin 实际=true 期望=false") }, diffs.toString())
    }

    @Test
    fun `AC-52 owner 即使出现在 Dominion 成员表也不算多余`() {
        val (_, owner, _) = boundTown()
        fake.addMemberDirect(fake.resolveById(42)!!, owner)

        val diffs = AdminTasks.verify("青云镇", repos, fake)

        assertTrue(diffs.none { it.contains("多余成员") }, "owner 必须从反向比对里排除：$diffs")
    }

    @Test
    fun `AC-52 fix 按同一判据补齐与剔除`() {
        val (_, _, guest) = boundTown()
        val ref = fake.resolveById(42)!!
        // 制造差异：guest 被移出成员表、多一个陌生人、owner 被写错
        fake.removeMemberFromDominion(ref, guest)
        val stranger = UUID.randomUUID()
        fake.addMemberDirect(ref, stranger)
        fake.transferOwnership(ref, UUID.randomUUID())

        val fixed = AdminTasks.fix("青云镇", repos, fake)

        assertTrue(fixed.isNotEmpty(), "应当报告修复项")
        assertTrue(fake.isMemberDirect(42, guest), "缺的成员要补回来")
        assertTrue(!fake.isMemberDirect(42, stranger), "多余成员要剔除")
        assertTrue(AdminTasks.verify("青云镇", repos, fake).isEmpty(), "修复后 verify 必须为 0 差异")
    }

    private fun boundTown(): Triple<Int, UUID, UUID> {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        fake.setAt(DominionRef(42, owner, "我的领地"))
        territory.setterritory(owner, null, pos)
        executor.runOnce()
        return Triple(town.id, owner, guest)
    }
}

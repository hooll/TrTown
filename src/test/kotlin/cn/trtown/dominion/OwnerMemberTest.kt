package cn.trtown.dominion

import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.TownRole
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M-30：Dominion 的 **owner 不是 member**，也不能被加进组。
 *
 * 真服日志：`[Dominion] Failed to add member, reason: You can't add dominion owner as a member.`
 * → `member.addMember 返回 null` → 任务以 `attempts=3` 仍 `PENDING`，**永远不可能成功**。
 *
 * 三个层面都要拦住：
 * 1. 绑定时不排（`TerritoryService`）；
 * 2. 已经排了的老任务要能收尾，不能永久重试（`SyncExecutor.grant`）；
 * 3. 转让镇长后原镇长（仍是领地 owner）降级产生的 MOVE_GROUP 同理（`SyncExecutor.moveGroup`）。
 */
class OwnerMemberTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private lateinit var executor: SyncExecutor
    private var now = 1_000L

    private val dominionId = 42

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        invites = InviteService(repos, TestSupport.rules) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns, TestSupport.rules.groupNameFormat) { now }
        executor = SyncExecutor(
            repos, fake,
            SyncSettings(maxAttempts = 5, backoffSeconds = listOf(5L, 30L), groupNameFormat = "TrTown_%s_%s"),
            towns,
        ) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    @Test
    fun `M-30 绑定领地时不为 owner 排 GRANT 任务`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(dominionId, owner, "TestLand"))

        territory.setterritory(owner, "TestLand")

        val grants = repos.tasks.listByTown(town.id).filter { it.action == SyncAction.GRANT }
        assertTrue(grants.none { it.targetUuid == owner }, "镇长就是领地 owner，不该给他排 GRANT：$grants")
    }

    @Test
    fun `M-30 已存在的 owner GRANT 任务会被收尾而不是永久重试`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, dominionId)
        fake.addDominion(DominionRef(dominionId, owner, "TestLand"))

        // 模拟升级前留下的坏任务
        repos.tasks.enqueue(town.id, dominionId, SyncAction.GRANT, owner, null, now)
        executor.runOnce()

        val task = assertNotNull(repos.tasks.listByTown(town.id).singleOrNull())
        assertEquals(SyncState.DONE, task.state, "指向 owner 的 GRANT 必须一次收尾，不能留在 PENDING：${task.lastError}")
        assertEquals(0, fake.countOf("ensureMember"), "根本不该去调 Dominion 的 addMember")
        assertEquals(0, fake.countOf("putInGroup"))

        // 留一条零副作用账本，避免 selfHeal 每次入服都重新排这条任务
        val ledger = repos.grants.find(town.id, dominionId, owner)
        assertNotNull(ledger, "应记录一条『什么都没做』的账本行")
        assertEquals(false, ledger.memberAdded)
        assertEquals(false, ledger.groupAdded)
    }

    @Test
    fun `M-30 转让镇长后原镇长（领地 owner）的 MOVE_GROUP 不会永久重试`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, dominionId)
        fake.addDominion(DominionRef(dominionId, owner, "TestLand"))

        val heir = UUID.randomUUID()
        invites.invite(owner, heir)
        invites.accept(heir, town.id)
        towns.setrole(owner, heir, TownRole.MANAGER)

        // 清掉之前所有任务，只留转让产生的那批
        repos.tasks.listByTown(town.id).forEach { repos.tasks.markDone(it.id, now) }
        towns.transfer(owner, heir)

        executor.runOnce()

        val unfinished = repos.tasks.listByTown(town.id).filter { it.state == SyncState.PENDING }
        assertTrue(
            unfinished.none { it.targetUuid == owner },
            "原镇长仍是领地 owner，指向他的任务必须一次收尾：$unfinished",
        )
    }
}

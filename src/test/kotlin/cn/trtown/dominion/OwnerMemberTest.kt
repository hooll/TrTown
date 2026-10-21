package cn.trtown.dominion

import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
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
 * M-30：Dominion 的 **owner 不是 member**，也不能被加进成员表。
 *
 * 真服日志：`[Dominion] Failed to add member, reason: You can't add dominion owner as a member.`
 * → 任务以 `attempts=3` 仍 `PENDING`，**永远不可能成功**。
 *
 * v1.25 后新任务叫 `MEMBER_ADD`，语义不变：镇长（owner）永远不排成员任务。
 */
class OwnerMemberTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private lateinit var executor: SyncExecutor
    private var now = 1_000L

    private val dominionId = 42
    private val pos = BlockPosition(UUID.randomUUID(), 0.0, 64.0, 0.0)

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns) { now }
        executor = SyncExecutor(repos, fake, SyncSettings(5, listOf(5L, 30L)), towns) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    @Test
    fun `M-30 绑定领地时不为 owner 排 MEMBER_ADD 任务`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(dominionId, owner, "TestLand"))
        fake.setAt(DominionRef(dominionId, owner, "TestLand"))

        territory.setterritory(owner, null, pos)

        val tasks = repos.tasks.listByTown(town.id)
        assertTrue(tasks.none { it.targetUuid == owner }, "镇长就是领地 owner，不该给他排任务：$tasks")
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
        assertEquals(0, fake.countOf("setMemberFlag"))
    }
}

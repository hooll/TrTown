package cn.trtown.dominion

import cn.trtown.model.TownRole
import cn.trtown.platform.DominionTransferListener
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-53：转让联动（预览零写入、确认后 TrTown 与 Dominion 同时换 owner、原生转让被拦截） */
class TransferTest {

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
    fun `AC-53 预览只校验不写入`() {
        val (townId, owner, guest) = boundTown()
        val tasksBefore = repos.tasks.listByTown(townId).size

        val preview = towns.previewTransfer(owner, guest)

        assertEquals(42, preview.dominionId)
        assertEquals(owner, repos.towns.findById(townId)?.ownerUuid, "预览不许改 owner")
        assertEquals(tasksBefore, repos.tasks.listByTown(townId).size, "预览不许入队任务")
    }

    @Test
    fun `AC-53 确认后 towns_owner_uuid 与 dom_owner 同时变成 B`() {
        val (townId, owner, guest) = boundTown()

        towns.transfer(owner, guest)
        assertEquals(guest, repos.towns.findById(townId)?.ownerUuid, "TrTown 先落库")
        assertEquals(owner, fake.resolveById(42)?.ownerUuid, "Dominion 由任务后调")

        executor.runOnce()

        assertEquals(guest, fake.resolveById(42)?.ownerUuid, "任务跑完后领地 owner 必须变 B")
        assertEquals(TownRole.MEMBER, repos.members.find(townId, owner)?.role, "原镇长降为 MEMBER")
        assertFalse(fake.isMemberDirect(42, guest), "新 owner 不该留在成员表")
        assertTrue(fake.isMemberDirect(42, owner), "原镇长降级后应成为普通成员")
    }

    @Test
    fun `AC-53 未绑定领地时转让保持旧行为且不产生任务`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        val guest = UUID.randomUUID()
        invites.invite(owner, guest)
        invites.accept(guest, town.id)

        val preview = towns.previewTransfer(owner, guest)
        assertNull(preview.dominionId)

        towns.transfer(owner, guest)
        assertEquals(guest, repos.towns.findById(town.id)?.ownerUuid)
        assertTrue(repos.tasks.listByTown(town.id).isEmpty())
    }

    @Test
    fun `AC-53 主领地转让事件会被拦截，本插件自己的转让放行`() {
        val (_, owner, _) = boundTown()

        assertTrue(DominionTransferListener.shouldBlock(42, owner), "玩家在 Dominion 里转主领地必须拦")

        DominionBindings.beginInternalTransfer(42)
        try {
            assertFalse(DominionTransferListener.shouldBlock(42, owner), "本插件 setOwner 不能被自己拦掉")
        } finally {
            DominionBindings.endInternalTransfer(42)
        }

        assertFalse(DominionTransferListener.shouldBlock(42, UUID.randomUUID()), "oldOwner 与账本不一致时只告警、不拦截")
        assertFalse(DominionTransferListener.shouldBlock(999, owner), "不是任何镇主领地时不管")
    }

    @Test
    fun `AC-53 转让任务失败会重试而不是回滚 TrTown`() {
        val (townId, owner, guest) = boundTown()
        towns.transfer(owner, guest)

        fake.failTimes("transferOwnership", 1)
        executor.runOnce()
        assertEquals(owner, fake.resolveById(42)?.ownerUuid, "第一次失败，Dominion 仍是旧 owner")
        assertEquals(guest, repos.towns.findById(townId)?.ownerUuid, "TrTown 不回滚")

        now += 60_000
        executor.runOnce()
        assertEquals(guest, fake.resolveById(42)?.ownerUuid, "重试成功后领地跟着换 owner")
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

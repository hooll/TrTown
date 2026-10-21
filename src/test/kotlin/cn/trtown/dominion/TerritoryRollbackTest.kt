package cn.trtown.dominion

import cn.trtown.service.Repos
import cn.trtown.service.TerritoryService
import cn.trtown.service.TestSupport
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.service.TownService
import cn.trtown.store.Store
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M-27：绑定失败必须把**两侧**都收干净 —— Dominion 侧已建的组 + TrTown 自己的账本行。
 *
 * 真服残留过 `dominion_group` 里的 `TrTown_6_MAYOR`（六次失败痕迹），
 * 而玩家已经看到"绑定失败，已回滚"。残留的后果不只是脏数据：下次绑定时
 * `ensureGroup` 的 `existing` 分支会命中这个半成品组，后续 flag 写到一个当初没配好的组上。
 */
class TerritoryRollbackTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private var now = 1_000L

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, TestSupport.rules) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns, TestSupport.rules.groupNameFormat) { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    @Test
    fun `M-27 建组中途失败时已建的组与账本行都要删掉`() {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(9, owner, "TestLand"))

        // 三档按 MAYOR → MANAGER → MEMBER 顺序建，第 3 次失败 → 前两组必须回滚
        fake.failOnCall("ensureGroup", 3)

        val ex = assertFailsWith<TownException> { territory.setterritory(owner, "TestLand") }
        assertEquals(TownError.DOMINION_BIND_FAILED, ex.error)

        val mayor = "TrTown_${town.id}_MAYOR"
        val manager = "TrTown_${town.id}_MANAGER"
        assertNull(fake.groupByName(9, mayor), "回滚后 Dominion 侧不许残留 $mayor")
        assertNull(fake.groupByName(9, manager), "回滚后 Dominion 侧不许残留 $manager")
        assertEquals(2, fake.countOf("deleteGroupIfEmpty"), "两个已建的组都要走删除")

        // TrTown 侧：账本行与 dominion_id 都不许留
        assertTrue(repos.groups.listByTown(town.id).isEmpty(), "账本残留：${repos.groups.listByTown(town.id)}")
        assertNull(repos.towns.findById(town.id)?.dominionId)
        assertEquals(0, repos.tasks.listByTown(town.id).size, "失败时不该入队任何同步任务")
    }

    @Test
    fun `M-27 预检能在碰 Dominion 之前拒掉未注册的 flag`() {
        // 这条测的是"建组之前就失败"，即真服那次的真实路径：
        // container 非法 → 一个组都不该被建出来
        val illegal = FlagNames.illegal(mapOf("place" to true, "container" to true), setOf("place", "break"))
        assertEquals(listOf("container"), illegal)

        assertTrue(FlagNames.illegal(mapOf("place" to true), setOf("place", "break")).isEmpty())
        assertTrue(FlagNames.illegal(emptyMap(), setOf("place")).isEmpty())
    }
}

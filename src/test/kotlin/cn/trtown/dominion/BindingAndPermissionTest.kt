package cn.trtown.dominion

import cn.trtown.model.Grant
import cn.trtown.model.PermsJson
import cn.trtown.model.SyncAction
import cn.trtown.model.TownRole
import cn.trtown.service.InviteService
import cn.trtown.service.PermissionService
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** AC-7 / AC-14 / AC-21 / AC-22 */
class BindingAndPermissionTest {

    private lateinit var store: Store
    private lateinit var repos: Repos
    private lateinit var towns: TownService
    private lateinit var invites: InviteService
    private lateinit var fake: FakeDominionGateway
    private lateinit var territory: TerritoryService
    private var now = 1_000L
    private val rules = TestSupport.rules.copy(maxMembers = 10)

    private val writeCalls = setOf(
        "ensureGroup", "ensureMember", "putInGroup", "removeFromGroup",
        "removeMemberFromDominion", "deleteGroupIfEmpty", "setMemberFlag",
    )

    @BeforeEach
    fun setUp() {
        TestSupport.installDominionConfig()
        store = TestSupport.store()
        repos = TestSupport.repos(store)
        towns = TownService(repos, rules) { now }
        invites = InviteService(repos, rules) { now }
        fake = FakeDominionGateway()
        territory = TerritoryService(repos, fake, towns, "TrTown_%s_%s") { now }
    }

    @AfterEach
    fun tearDown() = TestSupport.cleanup()

    // ---- AC-7 ----

    @Test
    fun `AC-7 非领地 owner 绑定被拒且网关零写调用`() {
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(42, UUID.randomUUID(), "别人的领地"))

        val ex = assertFailsWith<TownException> { territory.setterritory(owner, "别人的领地") }
        assertEquals(TownError.DOMINION_NOT_OWNER, ex.error)
        assertTrue(fake.callNames().none { it in writeCalls }, "校验失败时不允许任何写调用：${fake.callNames()}")
    }

    @Test
    fun `AC-7 领地已被别的镇占用时拒绝`() {
        val ownerA = UUID.randomUUID()
        val ownerB = UUID.randomUUID()
        towns.create(ownerA, "镇子甲")
        towns.create(ownerB, "镇子乙")
        fake.addDominion(DominionRef(42, ownerA, "甲的地"))
        // A 先绑
        territory.setterritory(ownerA, "甲的地")

        // B 试图绑同一块地（即使 B 是 owner 也不行）
        fake.addDominion(DominionRef(42, ownerB, "甲的地"))
        val ex = assertFailsWith<TownException> { territory.setterritory(ownerB, "甲的地") }
        assertEquals(TownError.DOMINION_IN_USE, ex.error)
    }

    // ---- AC-14 ----

    @Test
    fun `AC-14 三档组 flag 递进且管理含 admin 与六项本体权限`() {
        installRealisticConfig()
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(42, owner, "我的领地"))

        territory.setterritory(owner, "我的领地")

        val groupCalls = fake.calls.filter { it.name == "ensureGroup" }
        assertEquals(3, groupCalls.size, "应建立三个阶级组")
        val memberFlags = flagsOf(groupCalls, TownRole.MEMBER)
        val managerFlags = flagsOf(groupCalls, TownRole.MANAGER)
        val mayorFlags = flagsOf(groupCalls, TownRole.MAYOR)

        // MEMBER 不含这些
        listOf("teleport", "fly", "admin", "resize").forEach {
            assertTrue(memberFlags[it] != true, "MEMBER 不应拥有 $it")
        }
        // MANAGER 必须含 admin + 六项本体
        assertTrue(managerFlags["admin"] == true, "MANAGER 必须含 admin")
        listOf("resize", "rename_dominion", "create_sub", "delete_sub", "resize_sub", "rename_sub").forEach {
            assertTrue(managerFlags[it] == true, "MANAGER 必须含 $it")
        }
        // 递进：MEMBER true 的项 MANAGER 也为 true；MANAGER true 的项 MAYOR 也为 true
        memberFlags.filter { it.value }.keys.forEach { assertTrue(managerFlags[it] == true, "递进断裂：$it") }
        managerFlags.filter { it.value }.keys.forEach { assertTrue(mayorFlags[it] == true, "递进断裂：$it") }
    }

    // ---- M-11 ----

    @Test
    fun `M-11 换绑会为旧领地的组入队 DELETE_GROUP`() {
        installRealisticConfig()
        val owner = UUID.randomUUID()
        towns.create(owner, "青云镇")
        fake.addDominion(DominionRef(42, owner, "旧地"))
        territory.setterritory(owner, "旧地")
        fake.addDominion(DominionRef(43, owner, "新地"))

        territory.setterritory(owner, "新地")

        val deletes = repos.tasks.listByTown(repos.towns.findByNameLower("青云镇")!!.id)
            .filter { it.action == SyncAction.DELETE_GROUP }
        assertEquals(3, deletes.size, "旧领地的三个 owned 组都要入队删除")
        assertTrue(deletes.all { it.dominionId == 42 }, "DELETE_GROUP 必须指向旧领地")
        assertTrue(deletes.all { it.payload?.contains("\"group_id\"") == true })
    }

    // ---- AC-21 / AC-22 ----

    @Test
    fun `AC-21 管理给普通成员开飞行会入队 SET_MEMBER_FLAG`() {
        val (townId, owner, member) = boundWithMember()
        val service = permissionService()

        service.set(owner, member, "飞行", true)

        val task = repos.tasks.listByTown(townId).single { it.action == SyncAction.SET_MEMBER_FLAG }
        assertEquals("""{"flag":"fly","value":true}""", task.payload)
        assertEquals(mapOf("fly" to true), repos.members.find(townId, member)?.perms)
        assertTrue(service.describe(member).any { it.first == "fly" && it.third })
    }

    @Test
    fun `AC-21 管理不能给管理或镇长设置、普通成员不能设置自己`() {
        val (townId, owner, member) = boundWithMember()
        val manager = UUID.randomUUID()
        invites.invite(owner, manager)
        invites.accept(manager, townId)
        towns.setrole(owner, manager, TownRole.MANAGER)
        // 第二个管理，用来验证"管理之间不能互设"
        val manager2 = UUID.randomUUID()
        invites.invite(owner, manager2)
        invites.accept(manager2, townId)
        towns.setrole(owner, manager2, TownRole.MANAGER)
        val service = permissionService()

        // 管理 → 管理
        assertEquals(
            TownError.MANAGER_CANNOT_TOUCH_MANAGER,
            assertFailsWith<TownException> { service.set(manager, manager2, "fly", true) }.error,
        )
        // 管理 → 镇长
        assertEquals(
            TownError.CANNOT_TARGET_MAYOR,
            assertFailsWith<TownException> { service.set(manager, owner, "fly", true) }.error,
        )
        // 普通成员 → 任何人
        assertEquals(
            TownError.NO_PERMISSION,
            assertFailsWith<TownException> { service.set(member, owner, "fly", true) }.error,
        )
        // 自己 → 自己
        assertFailsWith<TownException> { service.set(owner, owner, "fly", true) }
        // 不在白名单
        assertEquals(
            TownError.FLAG_NOT_EDITABLE,
            assertFailsWith<TownException> { service.set(owner, member, "admin", true) }.error,
        )
    }

    @Test
    fun `AC-21 对原本就在领地里的成员拒绝覆写`() {
        val (townId, owner, member) = boundWithMember()
        repos.grants.upsert(
            Grant(townId, 42, member, preMember = true, preGroupId = 9, appliedGroupId = 1, appliedRole = TownRole.MEMBER, memberAdded = false, groupAdded = true, appliedAt = now),
        )
        val ex = assertFailsWith<TownException> { permissionService().set(owner, member, "fly", true) }
        assertEquals(TownError.TARGET_NOT_OVERRIDABLE, ex.error)
    }

    @Test
    fun `AC-22 reset 回阶级默认并入队一次 SET_MEMBER_FLAG false`() {
        val (townId, owner, member) = boundWithMember()
        val service = permissionService()
        service.set(owner, member, "fly", true)

        service.reset(owner, member)

        assertEquals(emptyMap(), repos.members.find(townId, member)?.perms)
        val flags = repos.tasks.listByTown(townId).filter { it.action == SyncAction.SET_MEMBER_FLAG }
        assertEquals(2, flags.size)
        val resetPayload = flags.last().payload
        assertEquals("""{"flag":"fly","value":false}""", resetPayload)
        assertEquals(false, PermsJson.decodeFlagPayload(resetPayload)?.second)
    }

    @Test
    fun `覆写功能关闭时直接拒绝`() {
        val (townId, owner, member) = boundWithMember()
        val service = PermissionService(repos, allowOverride = false, editable = setOf("fly"), aliases = emptyMap()) { now }
        val ex = assertFailsWith<TownException> { service.set(owner, member, "fly", true) }
        assertEquals(TownError.OVERRIDE_DISABLED, ex.error)
    }

    // ---- helpers ----

    private fun permissionService() = PermissionService(
        repos, allowOverride = true, editable = setOf("fly", "teleport"), aliases = mapOf("飞行" to "fly"), clock = { now },
    )

    private fun boundWithMember(): Triple<Int, UUID, UUID> {
        val owner = UUID.randomUUID()
        val town = towns.create(owner, "青云镇")
        repos.towns.updateDominion(town.id, 42)
        fake.addDominion(DominionRef(42, owner, "我的领地"))
        val member = UUID.randomUUID()
        invites.invite(owner, member)
        invites.accept(member, town.id)
        repos.grants.upsert(
            Grant(town.id, 42, member, preMember = false, preGroupId = 0, appliedGroupId = 1, appliedRole = TownRole.MEMBER, memberAdded = true, groupAdded = true, appliedAt = now),
        )
        return Triple(town.id, owner, member)
    }

    private fun flagsOf(calls: List<FakeDominionGateway.Call>, role: TownRole): Map<String, Boolean> {
        val call = calls.first { it.args[1] == role }
        @Suppress("UNCHECKED_CAST")
        return call.args[3] as Map<String, Boolean>
    }

    /** 与默认 config.yml 同构的生效 flag（MEMBER 20 / MANAGER 39 / MAYOR 39） */
    private fun installRealisticConfig() {
        val member = mapOf(
            "place" to true, "break" to true, "container" to true, "chest" to true, "door" to true,
            "trapdoor" to true, "fence_gate" to true, "button" to true, "lever" to true, "craft" to true,
            "anvil" to true, "enchant" to true, "furnace" to true, "bed" to true, "lectern" to true,
            "pick_up" to true, "drop_item" to true, "harvest" to true, "sowing" to true, "fertilizer" to true,
        )
        val manager = member + mapOf(
            "teleport" to true, "fly" to true, "pvp" to true, "ignite" to true, "hopper" to true,
            "dropper" to true, "dispenser" to true, "brew" to true, "beacon" to true, "trade" to true,
            "shear" to true, "feed" to true, "admin" to true, "resize" to true, "rename_dominion" to true,
            "create_sub" to true, "delete_sub" to true, "resize_sub" to true, "rename_sub" to true,
        )
        DominionConfig.install(
            FlagConfig.Validated(
                effective = mapOf(TownRole.MEMBER to member, TownRole.MANAGER to manager, TownRole.MAYOR to manager),
                editable = setOf("teleport", "fly"),
                warnings = emptyList(),
            ),
        )
        assertNotNull(DominionConfig.current)
    }
}

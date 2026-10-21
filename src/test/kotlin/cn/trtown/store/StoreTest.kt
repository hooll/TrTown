package cn.trtown.store

import cn.trtown.model.Grant
import cn.trtown.model.InviteStatus
import cn.trtown.model.Member
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import org.sqlite.SQLiteDataSource
import taboolib.module.database.Host
import taboolib.module.database.HostSQLite
import taboolib.module.database.SQL
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * S1 存储层测试。
 *
 * AC-10 要求"sqlite 与 mysql 两种配置都能构建仓储，且领域用例在两者上行为一致"。
 * 这里没有真实 MySQL 实例，用 H2 的 MySQL 兼容模式 + MySQL 方言（`SQL` 列构建器）
 * 跑同一套用例；真实 MySQL 未连接这一限制写进 EXEC。
 */
class StoreTest {

    @Test
    fun `sqlite 方言 - 全部仓储往返`() {
        val dir = createTempDirectory("trtown-sqlite").toFile()
        try {
            val file = File(dir, "test.db")
            val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }
            val store = StoreFactory.fromSqliteHost(HostSQLite(file), dataSource)
            runScenario(store)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `mysql 方言 - 全部仓储往返（H2 MySQL 模式替代）`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:trtown_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val store = StoreFactory.fromSqlHost(TestSqlHost(), dataSource)
        runScenario(store)
    }

    /** 同一套断言跑在两个方言上 */
    private fun runScenario(store: Store) {
        store.initSchema()
        assertEquals(7, store.tables.all.size)

        if (store.dialect == SqlDialect.SQLITE) {
            store.dataSource.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("PRAGMA journal_mode").use { rs ->
                        rs.next()
                        assertEquals("wal", rs.getString(1).lowercase())
                    }
                }
            }
        }

        val towns = TownRepository(store)
        val members = MemberRepository(store)
        val invites = InviteRepository(store)
        val grants = GrantRepository(store)
        val tasks = SyncTaskRepository(store)
        val audit = AuditRepository(store)

        val owner = UUID.randomUUID()
        val town = towns.insert("青云镇", owner, 1_000L)
        assertTrue(town.id > 0, "建镇应拿到自增 id")

        // 大小写不敏感的唯一名
        assertEquals(town.id, towns.findByNameLower("青云镇")?.id)
        assertFailsWith<Exception> { towns.insert("青云镇", UUID.randomUUID(), 2_000L) }

        // 更新
        towns.updatePrefix(town.id, "&b青云")
        towns.updateDescription(town.id, "测试简介")
        towns.updateDominion(town.id, 42)
        val reloaded = assertNotNull(towns.findById(town.id))
        assertEquals("&b青云", reloaded.prefix)
        assertEquals("测试简介", reloaded.description)
        assertEquals(42, reloaded.dominionId)
        assertEquals(town.id, towns.findByDominionId(42)?.id)
        assertEquals(1, towns.countActiveByOwner(owner))
        assertEquals(1, towns.listActive().size)

        towns.updateState(town.id, TownState.DISBANDED)
        assertEquals(0, towns.listActive().size)
        towns.updateState(town.id, TownState.ACTIVE)

        // 成员 + 个人权限覆写
        val memberUuid = UUID.randomUUID()
        members.insert(Member(town.id, memberUuid, TownRole.MEMBER, mapOf("fly" to true, "chest" to false), 1_500L))
        val member = assertNotNull(members.find(town.id, memberUuid))
        assertEquals(TownRole.MEMBER, member.role)
        assertEquals(mapOf("fly" to true, "chest" to false), member.perms)
        assertEquals(town.id, members.findByUuid(memberUuid)?.townId)
        assertEquals(1, members.countByTown(town.id))

        members.updateRole(town.id, memberUuid, TownRole.MANAGER)
        assertEquals(TownRole.MANAGER, members.find(town.id, memberUuid)?.role)
        assertEquals(1, members.listByRole(town.id, TownRole.MANAGER).size)

        members.updatePerms(town.id, memberUuid, emptyMap())
        assertEquals(emptyMap(), members.find(town.id, memberUuid)?.perms)

        // 邀请 + 过期清理
        val target = UUID.randomUUID()
        val invite = invites.insert(town.id, target, owner, 1_000L, 2_000L)
        assertTrue(invite.id > 0)
        assertEquals(invite.id, invites.findPending(town.id, target)?.id)
        assertEquals(1, invites.countPending(town.id))
        assertEquals(0, invites.expireOverdue(1_500L))
        assertEquals(1, invites.expireOverdue(3_000L))
        assertNull(invites.findPending(town.id, target))
        invites.updateStatus(invite.id, InviteStatus.ACCEPTED)

        // 账本 upsert
        val grant = Grant(town.id, 42, memberUuid, false, 0, 8, TownRole.MANAGER, true, true, 1_000L)
        grants.upsert(grant)
        grants.upsert(grant.copy(appliedGroupId = 9, appliedRole = TownRole.MEMBER))
        val stored = assertNotNull(grants.find(town.id, 42, memberUuid))
        assertEquals(9, stored.appliedGroupId)
        assertEquals(TownRole.MEMBER, stored.appliedRole)
        assertEquals(1, grants.listByTown(town.id).size)

        // 同步任务
        val taskId = tasks.enqueue(town.id, 42, SyncAction.GRANT, memberUuid, null, 1_000L)
        assertTrue(taskId > 0)
        assertEquals(1, tasks.claimReady(1_000L, 10).size)
        assertEquals(0, tasks.claimReady(999L, 10).size)
        tasks.markDone(taskId)
        assertEquals(SyncState.DONE, tasks.findById(taskId)?.state)

        val task2 = tasks.enqueue(town.id, 42, SyncAction.REVOKE, memberUuid, null, 2_000L)
        tasks.markAttempt(task2, "boom", 5, 9_000L, dead = true, now = 2_100L)
        assertEquals(SyncState.DEAD, tasks.findById(task2)?.state)
        assertEquals(1, tasks.resetDead(3_000L))
        assertEquals(SyncState.PENDING, tasks.findById(task2)?.state)

        // 审计
        audit.append(owner, town.id, "create", "青云镇", 1_000L)
        audit.append(owner, town.id, "setrole", "member", 1_100L)
        assertEquals(2, audit.listByTown(town.id).size)
    }

    /** 测试专用：SQL 方言 host，不触达 TabooLib 的 datasource.yml */
    private class TestSqlHost : Host<SQL>() {
        override val columnBuilder: SQL = SQL()
        override val connectionUrl: String = ""
        override val connectionUrlSimple: String = ""
        override val driverClass: String = "org.h2.Driver"
    }
}

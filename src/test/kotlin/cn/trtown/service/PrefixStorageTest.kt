package cn.trtown.service

import cn.trtown.store.Schema
import cn.trtown.store.SqlDialect
import cn.trtown.store.Store
import cn.trtown.store.StoreFactory
import cn.trtown.store.TownRepository
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PrefixStorageTest {

    @Test
    fun `SQLite 旧库长前缀重启后无损读取`() {
        val dir = createTempDirectory("trtown-prefix").toFile()
        try {
            val file = File(dir, "test.db")
            val longPrefix = buildString {
                repeat(16) { index ->
                    append("&#")
                    append(String.format("%06X", 0x100000 + index))
                    append(('a'.code + index).toChar())
                }
            }
            assertTrue(longPrefix.length > 64)

            val first = openSqlite(file)
            val town = TownRepository(first).insert("青云镇", UUID.randomUUID(), 1_000L)
            TownRepository(first).updatePrefix(town.id, longPrefix)

            val second = openSqlite(file)
            val reloaded = assertNotNull(TownRepository(second).findById(town.id))
            assertEquals(longPrefix, reloaded.prefix)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `H2 MySQL 模式旧 VARCHAR 幂等扩为 TEXT 且值保留`() {
        val dataSource = JdbcDataSource().apply {
            setURL("jdbc:h2:mem:trtown_prefix_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1")
            user = "sa"
            password = ""
        }
        val store = StoreFactory.fromSqlHost(TestSqlHost(), dataSource)
        store.initSchema()
        assertEquals(SqlDialect.MYSQL, store.dialect)

        // 模拟旧库：把 prefix 改回 VARCHAR(64)
        store.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("ALTER TABLE ${Schema.TOWNS} ALTER COLUMN prefix SET DATA TYPE VARCHAR(64)")
            }
        }
        val towns = TownRepository(store)
        val town = towns.insert("青云镇", UUID.randomUUID(), 1_000L)
        towns.updatePrefix(town.id, "&b短值")

        store.ensurePrefixTextColumn()
        assertEquals("&b短值", towns.findById(town.id)?.prefix)
        val (migratedType, maxLength) = columnInfo(store)
        assertTrue(
            migratedType.uppercase().let { it.contains("CLOB") || it.contains("TEXT") || it.contains("LARGE OBJECT") } ||
                maxLength == null || maxLength > 65535,
            "迁移后列类型仍为 $migratedType（最大长度 $maxLength）",
        )

        // 幂等：再调用一次不报错、不改值
        store.ensurePrefixTextColumn()
        assertEquals("&b短值", towns.findById(town.id)?.prefix)

        // 扩容后可存超过 64 字符
        val longPrefix = "&#FF0000" + "a".repeat(200)
        towns.updatePrefix(town.id, longPrefix)
        assertEquals(longPrefix, towns.findById(town.id)?.prefix)
    }

    @Test
    fun `扩容只看当前 schema 的同名表`() {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:trtown_schema_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1;" +
                    "INIT=CREATE SCHEMA IF NOT EXISTS ONE\\;CREATE SCHEMA IF NOT EXISTS TWO\\;SET SCHEMA TWO",
            )
            user = "sa"
            password = ""
        }
        val store = StoreFactory.fromSqlHost(TestSqlHost(), dataSource)
        store.initSchema()

        // 另一 schema 的同名表已经是 TEXT；当前 schema 仍是 VARCHAR(64)
        store.dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE ONE.${Schema.TOWNS} (id INT, prefix TEXT)")
                st.execute("ALTER TABLE ${Schema.TOWNS} ALTER COLUMN prefix SET DATA TYPE VARCHAR(64)")
            }
        }
        val towns = TownRepository(store)
        val town = towns.insert("青云镇", UUID.randomUUID(), 1_000L)
        towns.updatePrefix(town.id, "&b短值")

        store.ensurePrefixTextColumn()

        assertEquals("&b短值", towns.findById(town.id)?.prefix)
        val (twoType, twoLength) = columnInfoIn(store, "TWO")
        val (oneType, oneLength) = columnInfoIn(store, "ONE")
        assertTrue(twoType.contains("TEXT") || twoType.contains("CLOB") || twoType.contains("LARGE OBJECT") ||
            (twoLength ?: 0) > 65535, "当前 schema 未扩容：$twoType/$twoLength")
        assertTrue(oneType.contains("TEXT") || oneType.contains("CLOB") || oneType.contains("LARGE OBJECT") ||
            (oneLength ?: 0) > 65535, "另一 schema 被误改：$oneType/$oneLength")
    }

    private fun openSqlite(file: File): Store {
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }
        val store = StoreFactory.fromSqliteHost(HostSQLite(file), dataSource)
        store.initSchema()
        return store
    }

    private fun columnInfoIn(store: Store, schema: String): Pair<String, Long?> {
        store.dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data_type, character_maximum_length FROM information_schema.columns " +
                    "WHERE table_schema = ? AND UPPER(table_name) = UPPER(?) AND UPPER(column_name) = 'PREFIX'",
            ).use { statement ->
                statement.setString(1, schema)
                statement.setString(2, Schema.TOWNS)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        val length = rs.getLong(2).takeUnless { rs.wasNull() }
                        return rs.getString(1) to length
                    }
                }
            }
        }
        return "" to null
    }

    private fun columnInfo(store: Store): Pair<String, Long?> {
        store.dataSource.connection.use { conn ->
            conn.prepareStatement(
                "SELECT data_type, character_maximum_length FROM information_schema.columns " +
                    "WHERE UPPER(table_name) = UPPER(?) AND UPPER(column_name) = 'PREFIX'",
            ).use { statement ->
                statement.setString(1, Schema.TOWNS)
                statement.executeQuery().use { rs ->
                    if (rs.next()) {
                        val length = rs.getLong(2).takeUnless { rs.wasNull() }
                        return rs.getString(1) to length
                    }
                }
            }
        }
        return "" to null
    }

    private class TestSqlHost : Host<SQL>() {
        override val columnBuilder: SQL = SQL()
        override val connectionUrl: String = ""
        override val connectionUrlSimple: String = ""
        override val driverClass: String = "org.h2.Driver"
    }
}

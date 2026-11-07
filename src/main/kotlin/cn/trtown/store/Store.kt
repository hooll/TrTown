package cn.trtown.store

import cn.trtown.Settings
import taboolib.module.database.Database
import taboolib.module.database.Host
import taboolib.module.database.HostSQL
import taboolib.module.database.HostSQLite
import taboolib.module.database.SQL
import taboolib.module.database.SQLite
import taboolib.module.database.Table
import java.io.File
import java.sql.Connection
import javax.sql.DataSource

/**
 * 存储入口：持有连接池与全部表对象。
 *
 * 所有方法都是同步阻塞的，调用方必须保证在异步线程执行（PLAN §5.5 / AC-24）。
 * 这里不做线程断言，断言由 S2 的调度层统一负责。
 */
class Store(
    val dialect: SqlDialect,
    rawDataSource: DataSource,
    val tables: Tables,
) {

    /** 所有连接都要过主线程守卫（AC-24） */
    val dataSource: DataSource = GuardedDataSource(rawDataSource)

    /** 建表 + 建索引（幂等：`CREATE TABLE/INDEX IF NOT EXISTS`） */
    fun initSchema() {
        if (dialect == SqlDialect.SQLITE) {
            // SQLite 并发读写依赖 WAL；显式写死，不依赖驱动的默认行为（AUDIT §7.3）
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            }
        }
        tables.all.forEach { table: Table<*, *> -> table.createTable(dataSource) }
        // 旧 MySQL 库的 prefix 是 VARCHAR(64)，新前缀可能超过 64 字符；在发布 ready 前幂等扩容。
        // 失败直接抛出，Storage.init 不会把 current 置上，调用方据此提示存储不可用。
        if (dialect == SqlDialect.MYSQL) ensurePrefixTextColumn()
    }

    /**
     * 旧 MySQL 的 `trtown_towns.prefix` 扩容为 `TEXT NULL`（幂等）。
     *
     * 只改列类型，不删表、不重建、不动索引与其它列，保留全部旧值（含 null）。
     * 已经是 TEXT/CLOB 时直接返回，不每次无条件 ALTER。
     */
    fun ensurePrefixTextColumn() {
        val (type, maxLength) = prefixColumnInfo() ?: return
        val upper = type.uppercase()
        if (upper.contains("TEXT") || upper.contains("CLOB") || upper.contains("LARGE OBJECT")) return
        if (!upper.contains("CHAR")) return
        // H2 的 TEXT 别名在 information_schema 里仍显示 CHARACTER VARYING，但长度是 1e9；
        // 旧 VARCHAR(64) 的长度是 64。只对仍然受限的 CHAR 列执行扩容。
        if (maxLength != null && maxLength > 65535) return
        dataSource.connection.use { conn ->
            val product = runCatching { conn.metaData.databaseProductName }.getOrDefault("")
            val statements = if (product.contains("H2", ignoreCase = true)) {
                listOf(
                    "ALTER TABLE ${Schema.TOWNS} MODIFY COLUMN prefix TEXT NULL",
                    "ALTER TABLE ${Schema.TOWNS} ALTER COLUMN prefix SET DATA TYPE TEXT",
                )
            } else {
                listOf("ALTER TABLE ${Schema.TOWNS} MODIFY COLUMN prefix TEXT NULL")
            }
            var lastError: Throwable? = null
            for (sql in statements) {
                try {
                    conn.createStatement().use { it.execute(sql) }
                    return
                } catch (ex: Throwable) {
                    lastError = ex
                }
            }
            throw IllegalStateException("prefix 列扩容为 TEXT 失败（原类型 $type）", lastError)
        }
    }

    /** 读取 prefix 列类型与最大长度；表或列不存在时返回 null。 */
    private fun prefixColumnInfo(): Pair<String, Long?>? {
        dataSource.connection.use { conn ->
            // information_schema 在 MySQL 与 H2 的 MySQL 模式都可用；大小写不敏感，避免驱动差异
            runCatching {
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
            // 兜底：JDBC 元数据（不同驱动的目录/模式名差异较大，两种大小写都试）
            val meta = conn.metaData
            for (table in listOf(Schema.TOWNS, Schema.TOWNS.uppercase(), Schema.TOWNS.lowercase())) {
                for (column in listOf("prefix", "PREFIX")) {
                    meta.getColumns(null, null, table, column).use { rs ->
                        if (rs.next()) {
                            val length = rs.getLong("COLUMN_SIZE").takeUnless { rs.wasNull() }
                            return rs.getString("TYPE_NAME") to length
                        }
                    }
                }
            }
        }
        return null
    }

    /** 单连接事务；异常回滚，成功提交。 */
    fun <T> transaction(block: (Connection) -> T): T {
        dataSource.connection.use { conn ->
            val previousAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                return result
            } catch (ex: Throwable) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = previousAutoCommit }
            }
        }
    }

    /**
     * 连接池由 TabooLib 的 `Host` 统一托管（插件 DISABLE 时关闭），
     * 因此这里不需要显式关闭；保留方法是为了测试里能表达意图。
     */
    fun close() {
        // no-op：见 Host.companion 的 @Awake(LifeCycle.DISABLE)
    }
}

object StoreFactory {

    /** 按 config.yml 的 `storage.*` 构建存储 */
    fun create(dataFolder: File): Store {
        return when (val type = Settings.storageType) {
            "sqlite" -> {
                val host = HostSQLite(File(dataFolder, "trtown.db"))
                tunePool(Settings.sqlitePoolSize)
                Store(SqlDialect.SQLITE, host.createDataSource(), buildTablesSqlite(host))
            }

            "mysql" -> {
                val host = HostSQL(
                    host = Settings.mysqlHost,
                    port = Settings.mysqlPort,
                    user = Settings.mysqlUser,
                    password = Settings.mysqlPassword,
                    database = Settings.mysqlDatabase,
                )
                tunePool(Settings.mysqlPoolSize)
                Store(SqlDialect.MYSQL, host.createDataSource(), buildTablesSql(host))
            }

            else -> error("不支持的 storage.type: $type（只允许 sqlite / mysql）")
        }
    }

    /** 测试入口：用外部 DataSource（H2 等）构建同构的 Store */
    fun fromSqliteHost(host: Host<SQLite>, dataSource: DataSource): Store = Store(SqlDialect.SQLITE, dataSource, buildTablesSqlite(host))

    /** 测试入口：SQL/MySQL 方言 */
    fun fromSqlHost(host: Host<SQL>, dataSource: DataSource): Store = Store(SqlDialect.MYSQL, dataSource, buildTablesSql(host))

    /**
     * 调整连接池大小（PLAN §5.13 反模式：SQLite 上开大池会锁库）。
     *
     * TabooLib 的池参数来自它自己生成的 `datasource.yml`，这里在内存里覆盖，
     * 不改写那个文件（避免与 TabooLib 的默认值同步逻辑打架）。
     */
    private fun tunePool(size: Int) {
        val conf = runCatching { Database.settingsFile }.getOrNull() ?: return
        conf.set("DefaultSettings.MaximumPoolSize", size)
        conf.set("DefaultSettings.MinimumIdle", if (size <= 2) 1 else size)
    }
}

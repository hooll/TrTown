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
import javax.sql.DataSource

/**
 * 存储入口：持有连接池与全部表对象。
 *
 * 所有方法都是同步阻塞的，调用方必须保证在异步线程执行（PLAN §5.5 / AC-24）。
 * 这里不做线程断言，断言由 S2 的调度层统一负责。
 */
class Store(
    val dialect: SqlDialect,
    val dataSource: DataSource,
    val tables: Tables,
) {

    /** 建表 + 建索引（幂等：`CREATE TABLE/INDEX IF NOT EXISTS`） */
    fun initSchema() {
        if (dialect == SqlDialect.SQLITE) {
            // SQLite 并发读写依赖 WAL；显式写死，不依赖驱动的默认行为（AUDIT §7.3）
            dataSource.connection.use { conn ->
                conn.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            }
        }
        tables.all.forEach { table: Table<*, *> -> table.createTable(dataSource) }
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

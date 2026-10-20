package cn.trtown.store

import taboolib.module.database.ColumnBuilder
import taboolib.module.database.ColumnOptionSQL
import taboolib.module.database.ColumnOptionSQLite
import taboolib.module.database.ColumnTypeSQL
import taboolib.module.database.ColumnTypeSQLite
import taboolib.module.database.Host
import taboolib.module.database.SQL
import taboolib.module.database.SQLite
import taboolib.module.database.Table

/** 数据库方言 */
enum class SqlDialect { SQLITE, MYSQL }

/** 与方言无关的列类型（PLAN §5.2：只用 VARCHAR/BIGINT/TINYINT/TEXT/INT） */
enum class ColType { INT, BIGINT, TINYINT, VARCHAR, TEXT }

/**
 * 与方言无关的列定义。
 *
 * TabooLib 的 `SQLite.type(...)` 与 `SQL.type(...)` 接收的是两个互不相干的枚举
 * （`ColumnTypeSQLite` / `ColumnTypeSQL`），无法写成同一个泛型调用，
 * 因此这里把列定义抽成纯数据，再分别映射到两种 builder。
 */
data class ColumnDef(
    val name: String,
    val type: ColType,
    val size: Int = 0,
    val notNull: Boolean = false,
    val primaryKey: Boolean = false,
    val autoIncrement: Boolean = false,
)

data class IndexDef(val name: String, val columns: List<String>, val unique: Boolean = false)

data class TableDef(val name: String, val columns: List<ColumnDef>, val indices: List<IndexDef>)

/**
 * 表结构（PLAN §5.2）。全部字段名与类型一次到位，S2 起不再改表。
 *
 * 复合主键（members / grants / groups）用唯一索引表达：
 * TabooLib `Table` 没有复合主键 API，唯一索引在语义上等价于"这几列唯一"，
 * 且 SQLite / MySQL 行为一致。
 */
object Schema {

    const val TOWNS = "trtown_towns"
    const val MEMBERS = "trtown_members"
    const val GROUPS = "trtown_groups"
    const val INVITES = "trtown_invites"
    const val GRANTS = "trtown_grants"
    const val SYNC_TASKS = "trtown_sync_tasks"
    const val AUDIT = "trtown_audit"

    val towns = TableDef(
        TOWNS,
        listOf(
            ColumnDef("id", ColType.INT, primaryKey = true, autoIncrement = true, notNull = true),
            ColumnDef("name", ColType.VARCHAR, 32, notNull = true),
            ColumnDef("name_lower", ColType.VARCHAR, 32, notNull = true),
            ColumnDef("owner_uuid", ColType.VARCHAR, 36, notNull = true),
            ColumnDef("dominion_id", ColType.INT),
            ColumnDef("state", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("description", ColType.VARCHAR, 255),
            ColumnDef("prefix", ColType.VARCHAR, 64),
            ColumnDef("created_at", ColType.BIGINT, notNull = true),
        ),
        listOf(
            IndexDef("uk_towns_name_lower", listOf("name_lower"), unique = true),
            IndexDef("uk_towns_dominion_id", listOf("dominion_id"), unique = true),
            IndexDef("idx_towns_owner", listOf("owner_uuid")),
        ),
    )

    val members = TableDef(
        MEMBERS,
        listOf(
            ColumnDef("town_id", ColType.INT, notNull = true),
            ColumnDef("uuid", ColType.VARCHAR, 36, notNull = true),
            ColumnDef("role", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("perms", ColType.TEXT),
            ColumnDef("joined_at", ColType.BIGINT, notNull = true),
        ),
        listOf(
            IndexDef("uk_members_town_uuid", listOf("town_id", "uuid"), unique = true),
            IndexDef("idx_members_uuid", listOf("uuid")),
        ),
    )

    val groups = TableDef(
        GROUPS,
        listOf(
            ColumnDef("town_id", ColType.INT, notNull = true),
            ColumnDef("role", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("group_id", ColType.INT, notNull = true),
            ColumnDef("group_name", ColType.VARCHAR, 64, notNull = true),
            ColumnDef("owned", ColType.TINYINT, notNull = true),
        ),
        listOf(IndexDef("uk_groups_town_role", listOf("town_id", "role"), unique = true)),
    )

    val invites = TableDef(
        INVITES,
        listOf(
            ColumnDef("id", ColType.INT, primaryKey = true, autoIncrement = true, notNull = true),
            ColumnDef("town_id", ColType.INT, notNull = true),
            ColumnDef("target_uuid", ColType.VARCHAR, 36, notNull = true),
            ColumnDef("inviter_uuid", ColType.VARCHAR, 36, notNull = true),
            ColumnDef("status", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("created_at", ColType.BIGINT, notNull = true),
            ColumnDef("expires_at", ColType.BIGINT, notNull = true),
        ),
        listOf(
            IndexDef("idx_invites_target_status", listOf("target_uuid", "status")),
            IndexDef("idx_invites_town_status", listOf("town_id", "status")),
        ),
    )

    val grants = TableDef(
        GRANTS,
        listOf(
            ColumnDef("town_id", ColType.INT, notNull = true),
            ColumnDef("dominion_id", ColType.INT, notNull = true),
            ColumnDef("uuid", ColType.VARCHAR, 36, notNull = true),
            ColumnDef("pre_member", ColType.TINYINT, notNull = true),
            ColumnDef("pre_group_id", ColType.INT, notNull = true),
            ColumnDef("applied_group_id", ColType.INT, notNull = true),
            ColumnDef("applied_role", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("member_added", ColType.TINYINT, notNull = true),
            ColumnDef("group_added", ColType.TINYINT, notNull = true),
            ColumnDef("applied_at", ColType.BIGINT, notNull = true),
        ),
        listOf(IndexDef("uk_grants_town_dom_uuid", listOf("town_id", "dominion_id", "uuid"), unique = true)),
    )

    val syncTasks = TableDef(
        SYNC_TASKS,
        listOf(
            ColumnDef("id", ColType.BIGINT, primaryKey = true, autoIncrement = true, notNull = true),
            ColumnDef("town_id", ColType.INT, notNull = true),
            ColumnDef("dominion_id", ColType.INT, notNull = true),
            ColumnDef("action", ColType.VARCHAR, 24, notNull = true),
            ColumnDef("target_uuid", ColType.VARCHAR, 36),
            ColumnDef("payload", ColType.TEXT),
            ColumnDef("state", ColType.VARCHAR, 16, notNull = true),
            ColumnDef("attempts", ColType.INT, notNull = true),
            ColumnDef("last_error", ColType.TEXT),
            ColumnDef("next_retry_at", ColType.BIGINT, notNull = true),
            ColumnDef("created_at", ColType.BIGINT, notNull = true),
            ColumnDef("updated_at", ColType.BIGINT, notNull = true),
        ),
        listOf(IndexDef("idx_sync_state_retry", listOf("state", "next_retry_at"))),
    )

    val audit = TableDef(
        AUDIT,
        listOf(
            ColumnDef("id", ColType.BIGINT, primaryKey = true, autoIncrement = true, notNull = true),
            ColumnDef("ts", ColType.BIGINT, notNull = true),
            ColumnDef("actor_uuid", ColType.VARCHAR, 36),
            ColumnDef("town_id", ColType.INT),
            ColumnDef("action", ColType.VARCHAR, 32, notNull = true),
            ColumnDef("detail", ColType.TEXT),
        ),
        listOf(IndexDef("idx_audit_town_ts", listOf("town_id", "ts"))),
    )

    val all = listOf(towns, members, groups, invites, grants, syncTasks, audit)
}

/** 建好的表对象集合（与方言无关，统一以 `Table<*, *>` 暴露） */
class Tables(
    val towns: Table<*, *>,
    val members: Table<*, *>,
    val groups: Table<*, *>,
    val invites: Table<*, *>,
    val grants: Table<*, *>,
    val syncTasks: Table<*, *>,
    val audit: Table<*, *>,
) {
    val all = listOf(towns, members, groups, invites, grants, syncTasks, audit)
}

/** 按 host 的方言构建全部表 */
fun buildTablesSqlite(host: Host<SQLite>): Tables {
    val t = { def: TableDef -> def.build(host) }
    return Tables(
        towns = t(Schema.towns),
        members = t(Schema.members),
        groups = t(Schema.groups),
        invites = t(Schema.invites),
        grants = t(Schema.grants),
        syncTasks = t(Schema.syncTasks),
        audit = t(Schema.audit),
    )
}

fun buildTablesSql(host: Host<SQL>): Tables {
    val t = { def: TableDef -> def.build(host) }
    return Tables(
        towns = t(Schema.towns),
        members = t(Schema.members),
        groups = t(Schema.groups),
        invites = t(Schema.invites),
        grants = t(Schema.grants),
        syncTasks = t(Schema.syncTasks),
        audit = t(Schema.audit),
    )
}

private fun <T : Host<E>, E : ColumnBuilder> TableDef.build(host: T): Table<T, E> {
    val table = Table<T, E>(name, host)
    columns.forEach { def ->
        table.add(def.name) {
            when (this) {
                is SQLite -> applySQLite(def)
                is SQL -> applySQL(def)
                else -> error("不支持的列构建器: ${this::class.java.name}")
            }
        }
    }
    indices.forEach { table.index(it.name, it.columns, it.unique) }
    return table
}

private fun SQLite.applySQLite(def: ColumnDef) {
    val columnType = when (def.type) {
        ColType.INT, ColType.BIGINT, ColType.TINYINT -> ColumnTypeSQLite.INTEGER
        ColType.VARCHAR, ColType.TEXT -> ColumnTypeSQLite.TEXT
    }
    val size = if (def.type == ColType.VARCHAR) def.size else 0
    type(columnType, size) {
        val opts = ArrayList<ColumnOptionSQLite>(2)
        if (def.notNull) opts += ColumnOptionSQLite.NOTNULL
        if (def.primaryKey) opts += ColumnOptionSQLite.PRIMARY_KEY
        if (opts.isNotEmpty()) options(*opts.toTypedArray())
    }
}

private fun SQL.applySQL(def: ColumnDef) {
    val columnType = when (def.type) {
        ColType.INT -> ColumnTypeSQL.INT
        ColType.BIGINT -> ColumnTypeSQL.BIGINT
        ColType.TINYINT -> ColumnTypeSQL.TINYINT
        ColType.VARCHAR -> ColumnTypeSQL.VARCHAR
        ColType.TEXT -> ColumnTypeSQL.TEXT
    }
    val size = if (def.type == ColType.VARCHAR || def.type == ColType.TEXT) def.size else 0
    type(columnType, size) {
        val opts = ArrayList<ColumnOptionSQL>(3)
        if (def.notNull || def.primaryKey || def.autoIncrement) opts += ColumnOptionSQL.NOTNULL
        if (def.autoIncrement) opts += ColumnOptionSQL.AUTO_INCREMENT
        if (def.primaryKey) opts += ColumnOptionSQL.PRIMARY_KEY
        if (opts.isNotEmpty()) options(*opts.toTypedArray())
    }
}

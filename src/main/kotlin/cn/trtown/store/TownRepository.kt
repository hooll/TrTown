package cn.trtown.store

import cn.trtown.model.Member
import cn.trtown.model.PermsJson
import cn.trtown.model.Town
import cn.trtown.model.TownGroup
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import java.sql.ResultSet
import java.util.UUID

/**
 * 小镇仓储。全部方法同步阻塞，必须在异步线程调用。
 */
class TownRepository(private val store: Store) {

    private val table get() = store.tables.towns
    private val ds get() = store.dataSource

    fun insert(name: String, ownerUuid: UUID, createdAt: Long): Town {
        var id = 0
        table.insert(
            ds,
            "name", "name_lower", "owner_uuid", "dominion_id", "state", "description", "prefix", "created_at",
        ) {
            value(name, name.lowercase(), ownerUuid.toString(), null, TownState.ACTIVE.name, null, null, createdAt)
            onFinally {
                generatedKeys.use { keys -> if (keys.next()) id = keys.getInt(1) }
            }
        }
        if (id > 0) {
            return Town(id, name, ownerUuid, null, TownState.ACTIVE, null, null, createdAt)
        }
        // 少数驱动不返回自增键，退回按唯一键查一次
        return findByNameLower(name) ?: error("建镇后无法回读: $name")
    }

    fun findById(id: Int): Town? =
        table.select(ds) { where("id" eq id) }.firstOrNull { toTown() }

    fun findByNameLower(name: String): Town? =
        table.select(ds) { where("name_lower" eq name.lowercase()) }.firstOrNull { toTown() }

    fun findByDominionId(dominionId: Int): Town? =
        table.select(ds) { where("dominion_id" eq dominionId) }.firstOrNull { toTown() }

    fun listActive(): List<Town> =
        table.select(ds) { where("state" eq TownState.ACTIVE.name) }.map { toTown() }

    /**
     * 已解散但还没收尾干净的镇（M-32 的自愈入口）。
     *
     * 收尾（清 `dominion_id`）原本只在"该镇最后一条同步任务跑完"时触发。
     * 一旦那条任务变成 `DEAD`（放弃），就再没有事件会触发它 —— 于是这块领地永久被占。
     * 启动与每轮同步循环扫一遍这个列表，就能把历史遗留的脏数据自己收拾掉。
     */
    fun listDisbandedUnfinished(): List<Town> =
        table.select(ds) { where("state" eq TownState.DISBANDED.name) }
            .map { toTown() }
            .filter { it.dominionId != null }

    fun countActiveByOwner(ownerUuid: UUID): Int {
        var count = 0
        table.select(ds) {
            where { ("owner_uuid" eq ownerUuid.toString()) and ("state" eq TownState.ACTIVE.name) }
        }.forEach { count++ }
        return count
    }

    fun updateState(id: Int, state: TownState) {
        table.update(ds) {
            set("state", state.name)
            where("id" eq id)
        }
    }

    fun updateOwner(id: Int, ownerUuid: UUID) {
        table.update(ds) {
            set("owner_uuid", ownerUuid.toString())
            where("id" eq id)
        }
    }

    fun updateDominion(id: Int, dominionId: Int?) {
        table.update(ds) {
            set("dominion_id", dominionId)
            where("id" eq id)
        }
    }

    fun updatePrefix(id: Int, prefix: String?) {
        table.update(ds) {
            set("prefix", prefix)
            where("id" eq id)
        }
    }

    fun updateDescription(id: Int, description: String?) {
        table.update(ds) {
            set("description", description)
            where("id" eq id)
        }
    }

    /** 物理删除（仅测试与 `admin force-disband` 之后的清理用） */
    fun delete(id: Int) {
        table.delete(ds) { where("id" eq id) }
    }

    /**
     * 镇名墓碑（M-6）：解散后把 `name_lower` 改成 `!<id>:<原名小写>`，
     * 让同名新镇能立刻创建，同时保留 `name` 供审计/历史查询。幂等。
     */
    fun tombstoneName(id: Int, name: String) {
        val tombstone = "!$id:${name.lowercase()}"
        table.update(ds) {
            set("name_lower", tombstone)
            where("id" eq id)
        }
    }

    private fun ResultSet.toTown() = Town(
        id = getInt("id"),
        name = getString("name"),
        ownerUuid = uuid("owner_uuid"),
        dominionId = intOrNull("dominion_id"),
        state = TownState.valueOf(getString("state")),
        description = getString("description"),
        prefix = getString("prefix"),
        createdAt = getLong("created_at"),
    )
}

/**
 * 成员仓储。`perms` 列存 JSON 例外项，null = 跟随阶级默认。
 */
class MemberRepository(private val store: Store) {

    private val table get() = store.tables.members
    private val ds get() = store.dataSource

    fun insert(member: Member) {
        table.insert(ds, "town_id", "uuid", "role", "perms", "joined_at") {
            value(
                member.townId,
                member.uuid.toString(),
                member.role.name,
                PermsJson.encode(member.perms),
                member.joinedAt,
            )
        }
    }

    fun find(townId: Int, uuid: UUID): Member? =
        table.select(ds) {
            where { ("town_id" eq townId) and ("uuid" eq uuid.toString()) }
        }.firstOrNull { toMember() }

    fun findByUuid(uuid: UUID): Member? =
        table.select(ds) { where("uuid" eq uuid.toString()) }.firstOrNull { toMember() }

    fun listByTown(townId: Int): List<Member> =
        table.select(ds) { where("town_id" eq townId) }.map { toMember() }

    fun listByRole(townId: Int, role: TownRole): List<Member> =
        table.select(ds) {
            where { ("town_id" eq townId) and ("role" eq role.name) }
        }.map { toMember() }

    fun countByTown(townId: Int): Int {
        var count = 0
        table.select(ds) { where("town_id" eq townId) }.forEach { count++ }
        return count
    }

    fun updateRole(townId: Int, uuid: UUID, role: TownRole) {
        table.update(ds) {
            set("role", role.name)
            where { ("town_id" eq townId) and ("uuid" eq uuid.toString()) }
        }
    }

    fun updatePerms(townId: Int, uuid: UUID, perms: Map<String, Boolean>) {
        table.update(ds) {
            set("perms", PermsJson.encode(perms))
            where { ("town_id" eq townId) and ("uuid" eq uuid.toString()) }
        }
    }

    fun delete(townId: Int, uuid: UUID) {
        table.delete(ds) { where { ("town_id" eq townId) and ("uuid" eq uuid.toString()) } }
    }

    fun deleteByTown(townId: Int) {
        table.delete(ds) { where("town_id" eq townId) }
    }

    private fun ResultSet.toMember() = Member(
        townId = getInt("town_id"),
        uuid = uuid("uuid"),
        role = TownRole.valueOf(getString("role")),
        perms = PermsJson.decode(getString("perms")),
        joinedAt = getLong("joined_at"),
    )
}

/**
 * 阶级组仓储。定位一律用 `group_id`，组名只作兜底展示（PLAN §5.7）。
 */
class GroupRepository(private val store: Store) {

    private val table get() = store.tables.groups
    private val ds get() = store.dataSource

    fun listByTown(townId: Int): List<TownGroup> =
        table.select(ds) { where("town_id" eq townId) }.map { toGroup() }

    fun find(townId: Int, role: TownRole): TownGroup? =
        table.select(ds) {
            where { ("town_id" eq townId) and ("role" eq role.name) }
        }.firstOrNull { toGroup() }

    fun findByGroupId(groupId: Int): TownGroup? =
        table.select(ds) { where("group_id" eq groupId) }.firstOrNull { toGroup() }

    /** 写入或覆盖本镇某阶级的组信息 */
    fun upsert(group: TownGroup) {
        val affected = table.update(ds) {
            set("group_id", group.groupId)
            set("group_name", group.groupName)
            set("owned", group.owned.asInt())
            where { ("town_id" eq group.townId) and ("role" eq group.role.name) }
        }
        if (affected == 0) {
            table.insert(ds, "town_id", "role", "group_id", "group_name", "owned") {
                value(group.townId, group.role.name, group.groupId, group.groupName, group.owned.asInt())
            }
        }
    }

    fun delete(townId: Int, role: TownRole) {
        table.delete(ds) { where { ("town_id" eq townId) and ("role" eq role.name) } }
    }

    fun deleteByTown(townId: Int) {
        table.delete(ds) { where("town_id" eq townId) }
    }

    private fun ResultSet.toGroup() = TownGroup(
        townId = getInt("town_id"),
        role = TownRole.valueOf(getString("role")),
        groupId = getInt("group_id"),
        groupName = getString("group_name"),
        owned = bool("owned"),
    )
}

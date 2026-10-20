package cn.trtown.store

import cn.trtown.model.AuditEntry
import cn.trtown.model.Grant
import cn.trtown.model.Invite
import cn.trtown.model.InviteStatus
import cn.trtown.model.SyncAction
import cn.trtown.model.SyncState
import cn.trtown.model.SyncTask
import cn.trtown.model.TownRole
import java.sql.ResultSet
import java.util.UUID

/** 邀请仓储 */
class InviteRepository(private val store: Store) {

    private val table get() = store.tables.invites
    private val ds get() = store.dataSource

    fun insert(townId: Int, targetUuid: UUID, inviterUuid: UUID, createdAt: Long, expiresAt: Long): Invite {
        var id = 0
        table.insert(ds, "town_id", "target_uuid", "inviter_uuid", "status", "created_at", "expires_at") {
            value(townId, targetUuid.toString(), inviterUuid.toString(), InviteStatus.PENDING.name, createdAt, expiresAt)
            onFinally {
                generatedKeys.use { keys -> if (keys.next()) id = keys.getInt(1) }
            }
        }
        return Invite(id, townId, targetUuid, inviterUuid, InviteStatus.PENDING, createdAt, expiresAt)
    }

    fun findPending(townId: Int, targetUuid: UUID): Invite? =
        table.select(ds) {
            where {
                ("town_id" eq townId) and ("target_uuid" eq targetUuid.toString()) and ("status" eq InviteStatus.PENDING.name)
            }
        }.firstOrNull { toInvite() }

    fun listPendingByTarget(targetUuid: UUID): List<Invite> =
        table.select(ds) {
            where { ("target_uuid" eq targetUuid.toString()) and ("status" eq InviteStatus.PENDING.name) }
        }.map { toInvite() }

    fun countPending(townId: Int): Int {
        var count = 0
        table.select(ds) {
            where { ("town_id" eq townId) and ("status" eq InviteStatus.PENDING.name) }
        }.forEach { count++ }
        return count
    }

    fun updateStatus(id: Int, status: InviteStatus) {
        table.update(ds) {
            set("status", status.name)
            where("id" eq id)
        }
    }

    fun refreshExpiry(id: Int, expiresAt: Long) {
        table.update(ds) {
            set("expires_at", expiresAt)
            where("id" eq id)
        }
    }

    /** 把过期的 PENDING 全部置为 EXPIRED，返回受影响行数 */
    fun expireOverdue(now: Long): Int =
        table.update(ds) {
            set("status", InviteStatus.EXPIRED.name)
            where { ("status" eq InviteStatus.PENDING.name) and ("expires_at" lt now) }
        }

    fun deleteByTown(townId: Int) {
        table.delete(ds) { where("town_id" eq townId) }
    }

    private fun ResultSet.toInvite() = Invite(
        id = getInt("id"),
        townId = getInt("town_id"),
        targetUuid = uuid("target_uuid"),
        inviterUuid = uuid("inviter_uuid"),
        status = InviteStatus.valueOf(getString("status")),
        createdAt = getLong("created_at"),
        expiresAt = getLong("expires_at"),
    )
}

/** 回滚账本仓储（I4：只撤销有账本记录的授权） */
class GrantRepository(private val store: Store) {

    private val table get() = store.tables.grants
    private val ds get() = store.dataSource

    fun upsert(grant: Grant) {
        val affected = table.update(ds) {
            set("pre_member", grant.preMember.asInt())
            set("pre_group_id", grant.preGroupId)
            set("applied_group_id", grant.appliedGroupId)
            set("applied_role", grant.appliedRole.name)
            set("member_added", grant.memberAdded.asInt())
            set("group_added", grant.groupAdded.asInt())
            set("applied_at", grant.appliedAt)
            where {
                ("town_id" eq grant.townId) and ("dominion_id" eq grant.dominionId) and ("uuid" eq grant.uuid.toString())
            }
        }
        if (affected == 0) {
            table.insert(
                ds,
                "town_id", "dominion_id", "uuid", "pre_member", "pre_group_id",
                "applied_group_id", "applied_role", "member_added", "group_added", "applied_at",
            ) {
                value(
                    grant.townId, grant.dominionId, grant.uuid.toString(), grant.preMember.asInt(), grant.preGroupId,
                    grant.appliedGroupId, grant.appliedRole.name, grant.memberAdded.asInt(), grant.groupAdded.asInt(),
                    grant.appliedAt,
                )
            }
        }
    }

    fun find(townId: Int, dominionId: Int, uuid: UUID): Grant? =
        table.select(ds) {
            where {
                ("town_id" eq townId) and ("dominion_id" eq dominionId) and ("uuid" eq uuid.toString())
            }
        }.firstOrNull { toGrant() }

    fun listByTown(townId: Int): List<Grant> =
        table.select(ds) { where("town_id" eq townId) }.map { toGrant() }

    fun listByDominion(dominionId: Int): List<Grant> =
        table.select(ds) { where("dominion_id" eq dominionId) }.map { toGrant() }

    fun updateApplied(townId: Int, dominionId: Int, uuid: UUID, appliedGroupId: Int, appliedRole: TownRole, appliedAt: Long) {
        table.update(ds) {
            set("applied_group_id", appliedGroupId)
            set("applied_role", appliedRole.name)
            set("applied_at", appliedAt)
            where {
                ("town_id" eq townId) and ("dominion_id" eq dominionId) and ("uuid" eq uuid.toString())
            }
        }
    }

    fun delete(townId: Int, dominionId: Int, uuid: UUID) {
        table.delete(ds) {
            where {
                ("town_id" eq townId) and ("dominion_id" eq dominionId) and ("uuid" eq uuid.toString())
            }
        }
    }

    fun deleteByTown(townId: Int) {
        table.delete(ds) { where("town_id" eq townId) }
    }

    private fun ResultSet.toGrant() = Grant(
        townId = getInt("town_id"),
        dominionId = getInt("dominion_id"),
        uuid = uuid("uuid"),
        preMember = bool("pre_member"),
        preGroupId = getInt("pre_group_id"),
        appliedGroupId = getInt("applied_group_id"),
        appliedRole = TownRole.valueOf(getString("applied_role")),
        memberAdded = bool("member_added"),
        groupAdded = bool("group_added"),
        appliedAt = getLong("applied_at"),
    )
}

/** 同步任务仓储（I5：任何 Dominion 写操作之前必须先有 PENDING 行） */
class SyncTaskRepository(private val store: Store) {

    private val table get() = store.tables.syncTasks
    private val ds get() = store.dataSource

    fun enqueue(
        townId: Int,
        dominionId: Int,
        action: SyncAction,
        targetUuid: UUID? = null,
        payload: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Long {
        var id = 0L
        table.insert(
            ds,
            "town_id", "dominion_id", "action", "target_uuid", "payload",
            "state", "attempts", "last_error", "next_retry_at", "created_at", "updated_at",
        ) {
            value(
                townId, dominionId, action.name, targetUuid?.toString(), payload,
                SyncState.PENDING.name, 0, null, now, now, now,
            )
            onFinally {
                generatedKeys.use { keys -> if (keys.next()) id = keys.getLong(1) }
            }
        }
        return id
    }

    fun findById(id: Long): SyncTask? =
        table.select(ds) { where("id" eq id) }.firstOrNull { toTask() }

    /** 取出到点可执行的任务（PENDING 且 next_retry_at <= now） */
    fun claimReady(now: Long, limit: Int): List<SyncTask> =
        table.select(ds) {
            where { ("state" eq SyncState.PENDING.name) and ("next_retry_at" lte now) }
            orderBy("id")
            limit(limit)
        }.map { toTask() }

    fun markDone(id: Long, now: Long = System.currentTimeMillis()) {
        table.update(ds) {
            set("state", SyncState.DONE.name)
            set("updated_at", now)
            where("id" eq id)
        }
    }

    /** 记一次失败：未超限 → PENDING + 退避；超限 → DEAD */
    fun markAttempt(
        id: Long,
        error: String?,
        attempts: Int,
        nextRetryAt: Long,
        dead: Boolean,
        now: Long = System.currentTimeMillis(),
    ) {
        table.update(ds) {
            set("state", if (dead) SyncState.DEAD.name else SyncState.PENDING.name)
            set("attempts", attempts)
            set("last_error", error)
            set("next_retry_at", nextRetryAt)
            set("updated_at", now)
            where("id" eq id)
        }
    }

    /** 把所有 DEAD 重置为 PENDING（`admin retry`） */
    fun resetDead(now: Long = System.currentTimeMillis()): Int =
        table.update(ds) {
            set("state", SyncState.PENDING.name)
            set("attempts", 0)
            set("next_retry_at", now)
            set("updated_at", now)
            where("state" eq SyncState.DEAD.name)
        }

    /** 启动恢复：把所有 PENDING 的 next_retry_at 拉到现在 */
    fun wakeAllPending(now: Long = System.currentTimeMillis()): Int =
        table.update(ds) {
            set("next_retry_at", now)
            set("updated_at", now)
            where("state" eq SyncState.PENDING.name)
        }

    fun countByState(state: SyncState): Int {
        var count = 0
        table.select(ds) { where("state" eq state.name) }.forEach { count++ }
        return count
    }

    fun listByTown(townId: Int): List<SyncTask> =
        table.select(ds) { where("town_id" eq townId) }.map { toTask() }

    fun deleteByTown(townId: Int) {
        table.delete(ds) { where("town_id" eq townId) }
    }

    private fun ResultSet.toTask() = SyncTask(
        id = getLong("id"),
        townId = getInt("town_id"),
        dominionId = getInt("dominion_id"),
        action = SyncAction.valueOf(getString("action")),
        targetUuid = uuidOrNull("target_uuid"),
        payload = getString("payload"),
        state = SyncState.valueOf(getString("state")),
        attempts = getInt("attempts"),
        lastError = getString("last_error"),
        nextRetryAt = getLong("next_retry_at"),
        createdAt = getLong("created_at"),
        updatedAt = getLong("updated_at"),
    )
}

/** 审计仓储 */
class AuditRepository(private val store: Store) {

    private val table get() = store.tables.audit
    private val ds get() = store.dataSource

    fun append(
        actorUuid: UUID?,
        townId: Int?,
        action: String,
        detail: String? = null,
        ts: Long = System.currentTimeMillis(),
    ) {
        table.insert(ds, "ts", "actor_uuid", "town_id", "action", "detail") {
            value(ts, actorUuid?.toString(), townId, action, detail)
        }
    }

    fun listByTown(townId: Int, limit: Int = 50): List<AuditEntry> =
        table.select(ds) {
            where("town_id" eq townId)
            orderBy("id")
            limit(limit)
        }.map {
            AuditEntry(
                id = getLong("id"),
                ts = getLong("ts"),
                actorUuid = uuidOrNull("actor_uuid"),
                townId = intOrNull("town_id"),
                action = getString("action"),
                detail = getString("detail"),
            )
        }
}

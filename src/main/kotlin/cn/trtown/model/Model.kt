package cn.trtown.model

import java.util.UUID

/** 小镇阶级。顺序即权限高低：MAYOR > MANAGER > MEMBER */
enum class TownRole {
    MAYOR, MANAGER, MEMBER;

    /** 是否为管理及以上（镇长或管理） */
    val isManagerOrAbove: Boolean
        get() = this == MAYOR || this == MANAGER
}

/** 小镇状态 */
enum class TownState { ACTIVE, DISBANDED }

/** 邀请状态 */
enum class InviteStatus { PENDING, ACCEPTED, DECLINED, EXPIRED, REVOKED }

/** 同步任务动作 */
enum class SyncAction { GRANT, REVOKE, MOVE_GROUP, ENSURE_GROUP, DELETE_GROUP, SET_MEMBER_FLAG }

/** 同步任务状态 */
enum class SyncState { PENDING, DONE, DEAD }

/** 小镇 */
data class Town(
    val id: Int,
    val name: String,
    val ownerUuid: UUID,
    val dominionId: Int?,
    val state: TownState,
    val description: String?,
    /** 聊天前缀原文（可含 `&` 颜色码）；null = 用镇名 */
    val prefix: String?,
    val createdAt: Long,
) {
    val nameLower: String get() = name.lowercase()
}

/** 镇内成员。`perms` 只存"例外项"，null/空 = 完全跟随阶级组 */
data class Member(
    val townId: Int,
    val uuid: UUID,
    val role: TownRole,
    val perms: Map<String, Boolean>,
    val joinedAt: Long,
)

/** 本镇在 Dominion 里的阶级组（`owned=true` 表示由本插件创建、可删） */
data class TownGroup(
    val townId: Int,
    val role: TownRole,
    val groupId: Int,
    val groupName: String,
    val owned: Boolean,
)

/** 邀请 */
data class Invite(
    val id: Int,
    val townId: Int,
    val targetUuid: UUID,
    val inviterUuid: UUID,
    val status: InviteStatus,
    val createdAt: Long,
    val expiresAt: Long,
)

/** 回滚账本（只加不减的依据） */
data class Grant(
    val townId: Int,
    val dominionId: Int,
    val uuid: UUID,
    /** 施加前是否已是领地成员 */
    val preMember: Boolean,
    /** 施加前所在组 id；0 = 无 */
    val preGroupId: Int,
    /** 当前被我们放进哪个阶级组 */
    val appliedGroupId: Int,
    val appliedRole: TownRole,
    val memberAdded: Boolean,
    val groupAdded: Boolean,
    val appliedAt: Long,
)

/** 同步任务（先落库再执行，重启续跑） */
data class SyncTask(
    val id: Long,
    val townId: Int,
    val dominionId: Int,
    val action: SyncAction,
    val targetUuid: UUID?,
    val payload: String?,
    val state: SyncState,
    val attempts: Int,
    val lastError: String?,
    val nextRetryAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

/** 审计记录 */
data class AuditEntry(
    val id: Long,
    val ts: Long,
    val actorUuid: UUID?,
    val townId: Int?,
    val action: String,
    val detail: String?,
)

/**
 * `members.perms` 与 `sync_tasks.payload` 的极简 JSON 编解码。
 *
 * 只处理"扁平字符串键 + 布尔值"与"单层对象"两种形状，因此不引入 Gson
 * （避免与服务器自带的 gson 版本/重定位打架，见 EXEC S1 记录）。
 * 输出严格合法 JSON，键一律小写 flag_name。
 */
object PermsJson {

    /** `{"fly":true,"chest":false}`；空表返回 null（= 无覆写） */
    fun encode(perms: Map<String, Boolean>): String? {
        if (perms.isEmpty()) return null
        return perms.entries.joinToString(prefix = "{", postfix = "}", separator = ",") { (k, v) ->
            "\"${escape(k)}\":$v"
        }
    }

    /** 解析扁平布尔表；空串/null 返回空表，格式非法抛 [IllegalArgumentException] */
    fun decode(raw: String?): Map<String, Boolean> {
        if (raw.isNullOrBlank()) return emptyMap()
        val text = raw.trim()
        require(text.startsWith("{") && text.endsWith("}")) { "非法 perms JSON: $raw" }
        val body = text.substring(1, text.length - 1).trim()
        if (body.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        body.split(',').forEach { pair ->
            val idx = pair.lastIndexOf(':')
            require(idx > 0) { "非法 perms JSON: $raw" }
            val key = pair.substring(0, idx).trim().removeSurrounding("\"")
            val value = pair.substring(idx + 1).trim()
            result[key] = when (value) {
                "true" -> true
                "false" -> false
                else -> throw IllegalArgumentException("非法 perms 值: $raw")
            }
        }
        return result
    }

    /** 生成 `SET_MEMBER_FLAG` 的 payload：`{"flag":"fly","value":true}` */
    fun encodeFlagPayload(flag: String, value: Boolean): String = "{\"flag\":\"${escape(flag)}\",\"value\":$value}"

    /** 解析 `SET_MEMBER_FLAG` 的 payload */
    fun decodeFlagPayload(raw: String?): Pair<String, Boolean>? {
        if (raw.isNullOrBlank()) return null
        val flag = Regex("\"flag\"\\s*:\\s*\"([^\"]+)\"").find(raw)?.groupValues?.get(1) ?: return null
        val value = Regex("\"value\"\\s*:\\s*(true|false)").find(raw)?.groupValues?.get(1)?.toBoolean() ?: return null
        return flag to value
    }

    private fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")
}

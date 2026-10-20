package cn.trtown.service

import cn.trtown.dominion.DominionConfig
import cn.trtown.model.Member
import cn.trtown.model.PermsJson
import cn.trtown.model.SyncAction
import cn.trtown.model.TownRole
import java.util.UUID

/**
 * 个人权限覆写（PLAN §5.3 / R18）。
 *
 * **默认关闭**：R12 实测（反编译 Dominion 4.9.5 `Others#checkPrivilegeFlagSilence`）
 * 证明成员一旦在组里，Dominion 只看组 flag，成员个人 flag 被忽略。
 * `permissions.allow-override: false` 时所有入口直接拒绝，绝不"假装成功"。
 */
class PermissionService(
    private val repos: Repos,
    private val allowOverride: Boolean,
    private val editable: Set<String>,
    private val aliases: Map<String, String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun set(actor: UUID, target: UUID, rawFlag: String, value: Boolean): Map<String, Boolean> {
        val (townId, targetMember) = authorize(actor, target)
        val flag = resolveFlag(rawFlag)
        requireOverridable(townId, target, targetMember)

        val perms = targetMember.perms + (flag to value)
        repos.members.updatePerms(townId, target, perms)
        enqueueFlag(townId, target, flag, value)
        repos.audit.append(actor, townId, "perm", "$target $flag=$value", clock())
        return perms
    }

    fun reset(actor: UUID, target: UUID): Map<String, Boolean> {
        val (townId, targetMember) = authorize(actor, target)
        requireOverridable(townId, target, targetMember)
        val cleared = targetMember.perms
        if (cleared.isEmpty()) return emptyMap()

        val defaults = DominionConfig.flagsOf(targetMember.role)
        cleared.keys.forEach { flag ->
            enqueueFlag(townId, target, flag, defaults[flag] ?: false)
        }
        repos.members.updatePerms(townId, target, emptyMap())
        repos.audit.append(actor, townId, "perm-reset", "$target ${cleared.keys}", clock())
        return emptyMap()
    }

    /** 阶级默认 → 个人覆写 → 生效值 */
    fun describe(target: UUID): List<Triple<String, Boolean, Boolean>> {
        val member = repos.members.findByUuid(target) ?: throw TownException(TownError.NOT_IN_TOWN)
        val defaults = DominionConfig.flagsOf(member.role)
        return editable.map { flag ->
            val base = defaults[flag] ?: false
            val effective = member.perms[flag] ?: base
            Triple(flag, base, effective)
        }
    }

    // ---- 内部 ----

    private fun authorize(actor: UUID, target: UUID): Pair<Int, Member> {
        if (!allowOverride) throw TownException(TownError.OVERRIDE_DISABLED)
        val actorMember = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        if (!actorMember.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
        if (actor == target) throw TownException(TownError.CANNOT_TARGET_SELF)
        val targetMember = repos.members.find(actorMember.townId, target)
            ?: throw TownException(TownError.TARGET_NOT_MEMBER)
        when (targetMember.role) {
            TownRole.MAYOR -> throw TownException(TownError.CANNOT_TARGET_MAYOR)
            TownRole.MANAGER -> if (actorMember.role == TownRole.MANAGER) {
                throw TownException(TownError.MANAGER_CANNOT_TOUCH_MANAGER)
            }
            else -> Unit
        }
        return actorMember.townId to targetMember
    }

    private fun resolveFlag(raw: String): String {
        val key = aliases[raw] ?: aliases[raw.lowercase()] ?: raw.lowercase()
        if (key !in editable) throw TownException(TownError.FLAG_NOT_EDITABLE, raw)
        return key
    }

    /** 只对本插件加进领地的成员开放（pre_member=0），见 PLAN §5.4 备注 */
    private fun requireOverridable(townId: Int, target: UUID, member: Member) {
        val town = repos.towns.findById(townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        val dominionId = town.dominionId
        if (dominionId == null) {
            // 未绑定领地时没有"领地权限"可覆写
            throw TownException(TownError.TOWN_NOT_BOUND)
        }
        val grant = repos.grants.find(townId, dominionId, target)
        if (grant == null || grant.preMember) {
            throw TownException(TownError.TARGET_NOT_OVERRIDABLE)
        }
        // 避免未使用参数
        check(member.townId == townId)
    }

    private fun enqueueFlag(townId: Int, target: UUID, flag: String, value: Boolean) {
        val town = repos.towns.findById(townId) ?: return
        val dominionId = town.dominionId ?: return
        repos.tasks.enqueue(
            townId, dominionId, SyncAction.SET_MEMBER_FLAG, target,
            PermsJson.encodeFlagPayload(flag, value), clock(),
        )
    }
}

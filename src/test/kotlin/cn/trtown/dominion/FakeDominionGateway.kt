package cn.trtown.dominion

import cn.trtown.model.TownRole
import java.util.UUID

/**
 * Fake 网关：记录完整调用序列（方法名 + 实参），并可注入"前 N 次失败"。
 *
 * 与真实实现共用 [DominionGateway]，AC-6/AC-8/AC-15 靠它断言调用序列。
 */
class FakeDominionGateway : DominionGateway {

    data class Call(val name: String, val args: List<Any?>)

    val calls = mutableListOf<Call>()
    private val dominions = mutableMapOf<Int, DominionRef>()
    private val members = mutableMapOf<Int, MutableMap<UUID, Int>>()
    private val groups = mutableMapOf<Int, MutableMap<String, GroupRef>>()
    private val memberFlags = mutableMapOf<Pair<Int, UUID>, MutableMap<String, Boolean>>()
    private val failures = mutableMapOf<String, Int>()
    private var nextGroupId = 100

    fun addDominion(ref: DominionRef) {
        dominions[ref.id] = ref
    }

    /** 让某个方法前 [times] 次调用抛异常 */
    fun failTimes(method: String, times: Int) {
        failures[method] = times
    }

    fun callNames(): List<String> = calls.map { it.name }

    fun countOf(method: String): Int = calls.count { it.name == method }

    fun groupIdOf(dominionId: Int, uuid: UUID): Int? = members[dominionId]?.get(uuid)

    fun groupByName(dominionId: Int, name: String): GroupRef? = groups[dominionId]?.get(name)

    fun flagsOfMember(dominionId: Int, uuid: UUID): Map<String, Boolean> =
        memberFlags[dominionId to uuid] ?: emptyMap()

    private fun record(name: String, vararg args: Any?) {
        calls += Call(name, args.toList())
        val left = failures[name] ?: 0
        if (left > 0) {
            failures[name] = left - 1
            throw DominionOpException("fake failure: $name")
        }
    }

    override fun resolveByName(name: String): DominionRef? =
        dominions.values.firstOrNull { it.name == name }

    override fun resolveById(id: Int): DominionRef? = dominions[id]

    override fun isMember(ref: DominionRef, uuid: UUID): Boolean? {
        record("isMember", ref.id, uuid)
        return members[ref.id]?.containsKey(uuid) ?: false
    }

    override fun memberGroupId(ref: DominionRef, uuid: UUID): Int? {
        record("memberGroupId", ref.id, uuid)
        return members[ref.id]?.get(uuid)?.takeIf { it >= 0 }
    }

    override fun ensureGroup(ref: DominionRef, role: TownRole, name: String, flags: Map<String, Boolean>): GroupRef {
        record("ensureGroup", ref.id, role, name, flags)
        val map = groups.getOrPut(ref.id) { mutableMapOf() }
        return map[name] ?: GroupRef(nextGroupId++, name, true).also { map[name] = it }
    }

    override fun ensureMember(ref: DominionRef, uuid: UUID) {
        record("ensureMember", ref.id, uuid)
        val map = members.getOrPut(ref.id) { mutableMapOf() }
        map.putIfAbsent(uuid, -1)
    }

    override fun putInGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        record("putInGroup", ref.id, groupId, uuid)
        val map = members.getOrPut(ref.id) { mutableMapOf() }
        if (!map.containsKey(uuid)) throw RetryLaterException("fake: 玩家不是领地成员")
        map[uuid] = groupId
    }

    override fun removeFromGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        record("removeFromGroup", ref.id, groupId, uuid)
        val map = members[ref.id] ?: return
        if (map[uuid] == groupId) map[uuid] = -1
    }

    override fun removeMemberFromDominion(ref: DominionRef, uuid: UUID) {
        record("removeMemberFromDominion", ref.id, uuid)
        members[ref.id]?.remove(uuid)
    }

    override fun deleteGroupIfEmpty(ref: DominionRef, groupId: Int) {
        record("deleteGroupIfEmpty", ref.id, groupId)
        val occupied = members[ref.id]?.values?.any { it == groupId } ?: false
        if (occupied) return
        groups[ref.id]?.entries?.removeIf { it.value.id == groupId }
    }

    override fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean) {
        record("setMemberFlag", ref.id, uuid, flag, value)
        memberFlags.getOrPut(ref.id to uuid) { mutableMapOf() }[flag] = value
    }

    /** 测试里预置"某人名下的领地" */
    val ownedByName = mutableMapOf<UUID, MutableList<String>>()

    override fun ownedNames(uuid: UUID): List<String> = ownedByName[uuid] ?: emptyList()
}

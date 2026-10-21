package cn.trtown.dominion

import java.util.UUID

/**
 * Fake 网关：记录完整调用序列（方法名 + 实参），并可注入"前 N 次失败"。
 *
 * 忠实复刻两条 Dominion 语义（M-30 / v1.25）：
 * - owner **不是** member，`ensureMember(owner)` 会被拒绝；
 * - 成员 flag 只在成员存在时可写。
 */
class FakeDominionGateway : DominionGateway {

    data class Call(val name: String, val args: List<Any?>)

    val calls = mutableListOf<Call>()
    private val dominions = mutableMapOf<Int, DominionRef>()
    private val members = mutableMapOf<Int, MutableMap<UUID, MutableMap<String, Boolean>>>()
    private val failures = mutableMapOf<String, Int>()
    private val failAt = mutableMapOf<String, Int>()

    /** 脚下领地：null = 不在任何领地内 */
    private var at: DominionRef? = null

    fun addDominion(ref: DominionRef) {
        dominions[ref.id] = ref
    }

    fun setAt(ref: DominionRef?) {
        at = ref
    }

    /** 测试预置成员（不经过 ensureMember，模拟"本来就在领地里"） */
    fun addMemberDirect(ref: DominionRef, uuid: UUID, flags: Map<String, Boolean> = emptyMap()) {
        members.getOrPut(ref.id) { mutableMapOf() }[uuid] = LinkedHashMap(flags)
    }

    fun flagsOfMember(dominionId: Int, uuid: UUID): Map<String, Boolean> =
        members[dominionId]?.get(uuid) ?: emptyMap()

    fun isMemberDirect(dominionId: Int, uuid: UUID): Boolean = members[dominionId]?.containsKey(uuid) == true

    /** 让某个方法前 [times] 次调用抛异常 */
    fun failTimes(method: String, times: Int) {
        failures[method] = times
    }

    /** 让某个方法在第 [callIndex] 次（1 起算）调用时抛异常 */
    fun failOnCall(method: String, callIndex: Int) {
        failAt[method] = callIndex
    }

    fun callNames(): List<String> = calls.map { it.name }

    fun countOf(method: String): Int = calls.count { it.name == method }

    private fun record(name: String, vararg args: Any?) {
        calls += Call(name, args.toList())
        val callNo = calls.count { it.name == name }
        if (failAt[name] == callNo) {
            throw DominionOpException("fake failure: $name #$callNo")
        }
        val left = failures[name] ?: 0
        if (left > 0) {
            failures[name] = left - 1
            throw DominionOpException("fake failure: $name")
        }
    }

    override fun resolveByName(name: String): DominionRef? =
        dominions.values.firstOrNull { it.name == name }

    override fun resolveById(id: Int): DominionRef? = dominions[id]

    override fun resolveAt(pos: BlockPosition): DominionRef? {
        record("resolveAt", pos)
        return at
    }

    override fun isMember(ref: DominionRef, uuid: UUID): Boolean? {
        record("isMember", ref.id, uuid)
        return members[ref.id]?.containsKey(uuid) ?: false
    }

    override fun ensureMember(ref: DominionRef, uuid: UUID) {
        record("ensureMember", ref.id, uuid)
        if (ref.ownerUuid == uuid) {
            throw DominionOpException("fake: You can't add dominion owner as a member.")
        }
        members.getOrPut(ref.id) { mutableMapOf() }.putIfAbsent(uuid, LinkedHashMap())
    }

    override fun removeMemberFromDominion(ref: DominionRef, uuid: UUID) {
        record("removeMemberFromDominion", ref.id, uuid)
        members[ref.id]?.remove(uuid)
    }

    override fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean) {
        record("setMemberFlag", ref.id, uuid, flag, value)
        val flags = members[ref.id]?.get(uuid) ?: throw RetryLaterException("fake: 玩家不是领地成员")
        flags[flag] = value
    }

    override fun memberFlags(ref: DominionRef, uuid: UUID): Map<String, Boolean>? {
        record("memberFlags", ref.id, uuid)
        return members[ref.id]?.get(uuid)?.toMap()
    }

    override fun memberUuids(ref: DominionRef): List<UUID>? {
        record("memberUuids", ref.id)
        return members[ref.id]?.keys?.toList() ?: emptyList()
    }

    override fun transferOwnership(ref: DominionRef, newOwner: UUID) {
        record("transferOwnership", ref.id, newOwner)
        val current = dominions[ref.id] ?: throw DominionOpException("fake: 领地不存在")
        if (current.ownerUuid == newOwner) return
        dominions[ref.id] = current.copy(ownerUuid = newOwner)
    }

    /** 测试里预置"某人名下的领地" */
    val ownedByName = mutableMapOf<UUID, MutableList<String>>()

    override fun ownedNames(uuid: UUID): List<String> = ownedByName[uuid] ?: emptyList()
}

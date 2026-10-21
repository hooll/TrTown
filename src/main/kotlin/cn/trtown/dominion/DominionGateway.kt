package cn.trtown.dominion

import cn.trtown.model.TownRole
import java.util.UUID

/** 领地引用（只用 id 定位，名字仅作展示/兜底） */
data class DominionRef(val id: Int, val ownerUuid: UUID, val name: String)

/** 组引用 */
data class GroupRef(val id: Int, val name: String, val created: Boolean)

/** Dominion 操作失败（future 异常完成 / 返回 null） */
class DominionOpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 需要稍后重试（玩家不在 Dominion 缓存等） */
class RetryLaterException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Dominion 桥（PLAN §5.4）。
 *
 * 纯 UUID / 无 Bukkit 类型；实现内部一律用控制台作为 operator。
 * 所有方法同步阻塞，调用方必须在异步线程执行。
 */
interface DominionGateway {

    fun resolveByName(name: String): DominionRef?

    fun resolveById(id: Int): DominionRef?

    /** null = 玩家不在 Dominion 缓存（需要重试） */
    fun isMember(ref: DominionRef, uuid: UUID): Boolean?

    /** null = 无组；-1 或无成员同样返回 null */
    fun memberGroupId(ref: DominionRef, uuid: UUID): Int?

    /** 已存在同名组则复用（created=false），否则创建 */
    fun ensureGroup(ref: DominionRef, role: TownRole, name: String, flags: Map<String, Boolean>): GroupRef

    /** 已是成员则跳过 */
    fun ensureMember(ref: DominionRef, uuid: UUID)

    /** 幂等；Dominion 一个成员只能属于一个组，跨组即移动 */
    fun putInGroup(ref: DominionRef, groupId: Int, uuid: UUID)

    fun removeFromGroup(ref: DominionRef, groupId: Int, uuid: UUID)

    fun removeMemberFromDominion(ref: DominionRef, uuid: UUID)

    /** 组内还有人则不删（只加不减原则） */
    fun deleteGroupIfEmpty(ref: DominionRef, groupId: Int)

    /** 个人 flag（R12 结论：成员在组内时不生效，默认功能关闭） */
    fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean)

    /** 该玩家名下（owner）的领地名，用于 Tab 补全。只在异步线程调用。 */
    fun ownedNames(uuid: UUID): List<String>
}

package cn.trtown.dominion

import java.util.UUID

/** 领地引用（只用 id 定位，名字仅作展示/兜底） */
data class DominionRef(val id: Int, val ownerUuid: UUID, val name: String)

/**
 * 纯 Kotlin 的方块坐标（PLAN §5.16.2 的 `getDominion(Location)` 入参）。
 *
 * 存在的理由：`service/` 不允许出现 Bukkit 类型，而命令线程捕获位置后要把它交给异步服务。
 * 真实网关在内部把它还原成 `Location`，因此 `Location` 只出现在 [DominionApiGateway] 里。
 */
data class BlockPosition(
    val worldUid: UUID,
    val x: Double,
    val y: Double,
    val z: Double,
    /** 传送点朝向（PLAN §5.17.2：Dominion 存的是完整 Location，这里必须保住） */
    val yaw: Float = 0f,
    val pitch: Float = 0f,
)

/** Dominion 操作失败（future 异常完成 / 返回 null） */class DominionOpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** 需要稍后重试（玩家不在 Dominion 缓存等） */
class RetryLaterException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 领地范围（PLAN §5.18.4 领地菜单展示用）。
 *
 * 纯 Int/Long，**不带 Location / World**：`CuboidDTO` 本身不含世界引用，
 * 展示只需要"多少格 + 对角坐标"，不需要把 Bukkit 类型漏出网关。
 */
data class DominionBounds(
    val x1: Int,
    val y1: Int,
    val z1: Int,
    val x2: Int,
    val y2: Int,
    val z2: Int,
    val square: Long,
)

/**
 * Dominion 桥（PLAN §5.4 / §5.16）。
 *
 * 纯 UUID / 无 Bukkit 类型；实现内部一律用控制台作为 operator。
 * 所有方法同步阻塞，调用方必须在异步线程执行。
 *
 * v1.25 起**没有组模型**：成员只进出 Dominion 原生成员表，权限用成员 flag 表达。
 */
interface DominionGateway {

    fun resolveByName(name: String): DominionRef?

    fun resolveById(id: Int): DominionRef?

    /** 脚下领地（`DominionAPI.getDominion(Location)`）；不在任何领地内返回 null */
    fun resolveAt(pos: BlockPosition): DominionRef?

    /** null = 玩家不在 Dominion 缓存（需要重试） */
    fun isMember(ref: DominionRef, uuid: UUID): Boolean?

    /** 已是成员则跳过；owner 不是 member，实现必须直接返回 */
    fun ensureMember(ref: DominionRef, uuid: UUID)

    /** 幂等：本来不在成员表里也算成功 */
    fun removeMemberFromDominion(ref: DominionRef, uuid: UUID)

    /** 个人 flag（v1.25 的主链路：MANAGER 的 administration 就是逐项设 true） */
    fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean)

    /** 该成员的 flag 实况；不是成员 / 缓存缺失返回 null */
    fun memberFlags(ref: DominionRef, uuid: UUID): Map<String, Boolean>?

    /** 领地成员 UUID 列表（不含 owner）；领地不存在返回 null */
    fun memberUuids(ref: DominionRef): List<UUID>?

    /** 改领地 owner（同步签名抛 SQLException，必须在全局线程调用） */
    fun transferOwnership(ref: DominionRef, newOwner: UUID)

    /** 该玩家名下（owner）的领地名，用于 Tab 补全。只在异步线程调用。 */
    fun ownedNames(uuid: UUID): List<String>

    /**
     * 写领地传送点（PLAN §5.17.2）。
     *
     * `DominionDTO.setTpLocation(Location)` 是同步签名且抛 `SQLException`，实现必须整段进
     * [cn.trtown.platform.GlobalBridge]。**不校验玩家是否站在领地内**（与 Dominion GUI 行为一致）。
     */
    fun setTpLocation(ref: DominionRef, pos: BlockPosition)

    /**
     * 读领地传送点（PLAN §5.17.1 / §5.17.3）。
     *
     * 数据源只有 Dominion，TrTown 不存副本；未设置 / 领地不存在 / 世界已卸载 → null。
     */
    fun tpLocation(ref: DominionRef): BlockPosition?

    /**
     * 读领地范围（PLAN §5.18.4 领地菜单：领地名 / 大小 / 坐标）。
     *
     * 纯内存读，不进桥；领地不存在返回 null。
     */
    fun bounds(ref: DominionRef): DominionBounds?
}

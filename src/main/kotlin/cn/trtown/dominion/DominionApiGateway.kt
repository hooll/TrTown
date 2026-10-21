package cn.trtown.dominion

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.DominionDTO
import cn.lunadeer.dominion.api.dtos.MemberDTO
import cn.lunadeer.dominion.api.dtos.flag.Flags
import cn.lunadeer.dominion.providers.MemberProvider
import cn.trtown.platform.GlobalBridge
import cn.trtown.platform.Log
import cn.trtown.platform.PlatformScheduler
import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Dominion 4.9.5 真实实现（PLAN §5.4 / §5.16）。
 *
 * **全插件唯一 import `cn.lunadeer.dominion.providers.*` 的地方**；
 * operator 一律 `Bukkit.getConsoleSender()`。
 *
 * **线程契约（PLAN §5.14 / M-23）**：写方法内部会同步 `callEvent(...)`，而 Paper 的
 * `PaperEventManager` 只允许**全局线程**触发非 async 事件，所以每个写调用连同它返回 future 的
 * `get` 都必须整段跑在 [bridge] 里；读方法不 fire 事件，保持异步（不把 IO 压到全局线程）。
 *
 * v1.25 起不再碰 Dominion 的组（建组 / 组 flag / 成员入组全部移除），
 * 只操作成员表与成员 flag。
 */
class DominionApiGateway(
    private val bridge: GlobalBridge.Bridge = PlatformScheduler.dominionBridge(),
) : DominionGateway {

    private val api: DominionAPI get() = DominionAPI.getInstance()
    private val console get() = Bukkit.getConsoleSender()

    override fun resolveByName(name: String): DominionRef? =
        api.getDominion(name)?.toRef()

    override fun resolveById(id: Int): DominionRef? =
        api.getDominion(id)?.toRef()

    override fun resolveAt(pos: BlockPosition): DominionRef? {
        val world = Bukkit.getWorld(pos.worldUid) ?: return null
        val location = Location(world, pos.x, pos.y, pos.z)
        // 读接口不 fire 事件，但 `getDominion(Location)` 会走区块索引；按 PLAN §5.16.2 仍进桥，
        // 避免调用方（异步线程）与 Dominion 的缓存刷新并发。
        return bridge.run("dominion.getDominion(location)") { api.getDominion(location)?.toRef() }
    }

    override fun isMember(ref: DominionRef, uuid: UUID): Boolean? {
        val dom = dom(ref) ?: return null
        // 玩家从未进过服 / Dominion 缓存里没有 → 让任务稍后重试
        if (api.getPlayer(uuid) == null) return null
        return memberOf(dom, uuid) != null
    }

    override fun ensureMember(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        // M-30：owner 不是 member，Dominion 会拒绝 `You can't add dominion owner as a member.`。
        // 上层（SyncExecutor / TerritoryService）已经跳过，这里是兜底 —— 任何新调用方都不该踩第二次。
        if (dom.owner == uuid) return
        // 同时看 MemberCache 与 DTO 的成员列表：两者更新时机不同，只看一个会误判
        if (memberOf(dom, uuid) != null) return
        val player = api.getPlayer(uuid) ?: throw RetryLaterException("玩家 $uuid 不在 Dominion 缓存")
        // 写操作 1/4：MemberProvider.addMember
        try {
            bridge.run("member.addMember") {
                await(MemberProvider.getInstance().addMember(console, dom, player), "member.addMember")
            }
        } catch (ex: DominionOpException) {
            // 并发/缓存竞态：addMember 可能因"已经是成员"而完成 null。
            // 重新取一次 DTO 再查，已经是成员就视为成功（幂等），不要让它变成永久 PENDING。
            val fresh = api.getDominion(dom.id) ?: dom
            if (memberOf(fresh, uuid) != null) return
            throw ex
        }
    }

    override fun removeMemberFromDominion(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: return
        val member = memberOf(dom, uuid) ?: return
        // 写操作 2/4：MemberProvider.removeMember
        bridge.run("member.removeMember") {
            await(MemberProvider.getInstance().removeMember(console, dom, member), "member.removeMember")
        }
    }

    override fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        val member = memberOf(dom, uuid) ?: throw RetryLaterException("玩家 $uuid 不是领地成员")
        val pri = Flags.getPreFlag(flag) ?: throw DominionOpException("未知 privilege flag: $flag")
        if (member.getFlagValue(pri) == value) return
        // 写操作 3/4：MemberProvider.setMemberFlag
        bridge.run("member.setMemberFlag") {
            await(MemberProvider.getInstance().setMemberFlag(console, dom, member, pri, value), "member.setMemberFlag")
        }
    }

    override fun memberFlags(ref: DominionRef, uuid: UUID): Map<String, Boolean>? {
        val dom = dom(ref) ?: return null
        val member = memberOf(dom, uuid) ?: return null
        // 逐项 getFlagValue：`flagsValue` 是否包含"从未显式设置"的 flag 取决于 Dominion 实现，
        // verify/自愈需要的是"缺失按 false"的确定语义。
        return Flags.getAllPriFlags().associate { flag ->
            flag.flagName to (member.getFlagValue(flag) ?: false)
        }
    }

    override fun memberUuids(ref: DominionRef): List<UUID>? {
        val dom = dom(ref) ?: return null
        return dom.members.map { it.playerUUID }
    }

    override fun transferOwnership(ref: DominionRef, newOwner: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        if (dom.owner == newOwner) return
        // 写操作 4/4：DominionDTO.setOwner（同步签名，抛 SQLException）
        try {
            bridge.run("dominion.setOwner") { dom.setOwner(newOwner) }
        } catch (ex: java.sql.SQLException) {
            throw DominionOpException("dominion.setOwner 失败: ${ex.message}", ex)
        }
    }

    override fun ownedNames(uuid: UUID): List<String> =
        runCatching { api.getPlayerOwnDominionDTOs(uuid).map { it.name } }
            .getOrElse {
                // M-24：静默回退成空表会让"补全里没有我的领地"变成无解问题，必须留痕
                Log.failure("getPlayerOwnDominionDTOs($uuid)", it)
                emptyList()
            }

    // ---- 内部 ----

    private fun dom(ref: DominionRef): DominionDTO? = api.getDominion(ref.id)

    /**
     * MemberCache 与 `DominionDTO.getMembers()` 的刷新时机不同：
     * 刚 add/remove 时可能一个已更新、另一个还是旧快照。
     * 两个都查，避免把"已经是成员"误判成"不是成员"（addMember 会因此返回 null）。
     */
    private fun memberOf(dom: DominionDTO, uuid: UUID): MemberDTO? =
        api.getMember(dom, uuid) ?: dom.members.firstOrNull { it.playerUUID == uuid }

    private fun DominionDTO.toRef(): DominionRef = DominionRef(id, owner, name)

    private fun <T> await(future: CompletableFuture<T>, op: String): T {
        return try {
            // 必须**小于** GlobalBridge 的 10 s：写调用整段跑在全局线程任务里，
            // 内层先超时才能给出"是哪一步卡住"的文案；反过来只会看到一句笼统的桥超时。
            future.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS) ?: throw DominionOpException("$op 返回 null")
        } catch (ex: TimeoutException) {
            throw RetryLaterException("$op 超时", ex)
        } catch (ex: ExecutionException) {
            // M-24：Dominion 内部异常的真实堆栈只能从这里拿到（future 的 cause），必须打出来
            ex.cause?.let { Log.failure(op, it) }
            throw DominionOpException("$op 失败: ${ex.cause?.message ?: ex.message}", ex.cause)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RetryLaterException("$op 被中断", ex)
        }
    }

    private companion object {
        /** Dominion future 的等待上限；必须小于 `GlobalBridge.DEFAULT_TIMEOUT_MS`（10 s） */
        const val FUTURE_TIMEOUT_SECONDS = 8L
    }
}

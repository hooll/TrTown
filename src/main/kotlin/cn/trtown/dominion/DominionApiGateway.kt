package cn.trtown.dominion

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.DominionDTO
import cn.lunadeer.dominion.api.dtos.GroupDTO
import cn.lunadeer.dominion.api.dtos.flag.Flags
import cn.lunadeer.dominion.providers.GroupProvider
import cn.lunadeer.dominion.providers.MemberProvider
import cn.trtown.model.TownRole
import cn.trtown.platform.GlobalBridge
import cn.trtown.platform.Log
import cn.trtown.platform.PlatformScheduler
import org.bukkit.Bukkit
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Dominion 4.9.5 真实实现（PLAN §5.4）。
 *
 * **全插件唯一 import `cn.lunadeer.dominion.providers.*` 的地方**；
 * operator 一律 `Bukkit.getConsoleSender()`。
 *
 * **线程契约（PLAN §5.14 / M-23）**：8 个写方法内部会同步 `callEvent(...)`，而 Paper 的
 * `PaperEventManager` 只允许**全局线程**触发非 async 事件，所以每个写调用连同它返回 future 的
 * `get` 都必须整段跑在 [bridge] 里；读方法不 fire 事件，保持异步（不把 IO 压到全局线程）。
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

    override fun isMember(ref: DominionRef, uuid: UUID): Boolean? {
        val dom = dom(ref) ?: return null
        // 玩家从未进过服 / Dominion 缓存里没有 → 让任务稍后重试
        if (api.getPlayer(uuid) == null) return null
        return api.getMember(dom, uuid) != null
    }

    override fun memberGroupId(ref: DominionRef, uuid: UUID): Int? {
        val dom = dom(ref) ?: return null
        return api.getMember(dom, uuid)?.groupId?.takeIf { it >= 0 }
    }

    override fun ensureGroup(ref: DominionRef, role: TownRole, name: String, flags: Map<String, Boolean>): GroupRef {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        // M-27：**先**校验 flag 名，再 createGroup。
        // 顺序反过来就会留下孤儿组：createGroup 成功 → 某条 setGroupFlag 因非法名失败 →
        // ensureGroup 抛异常，调用方的"本次创建的组"列表里根本没有这个 id，回滚删不掉。
        val illegal = FlagNames.illegal(flags)
        if (illegal.isNotEmpty()) {
            throw DominionOpException("未知 privilege flag: ${illegal.joinToString("、")}")
        }
        val existing = dom.groups.firstOrNull { it.namePlain == name }
        // 写操作 1/8：createGroup（内部 fire GroupCreateEvent）
        val group = existing ?: bridge.run("createGroup($name)") {
            await(GroupProvider.getInstance().createGroup(console, dom, name), "createGroup($name)")
        }
        if (flags.isNotEmpty()) {
            try {
                // 写操作 2/8：setGroupFlag（内部 fire GroupFlagEvent）；同一组的 flag 只切一次全局线程
                bridge.run("setGroupFlag($name)") {
                    flags.forEach { (flagName, value) ->
                        val flag = Flags.getPreFlag(flagName)
                            ?: throw DominionOpException("未知 privilege flag: $flagName")
                        if (group.getFlagValue(flag) != value) {
                            await(
                                GroupProvider.getInstance().setGroupFlag(console, dom, group, flag, value),
                                "setGroupFlag($name,$flagName)",
                            )
                        }
                    }
                }
            } catch (ex: Throwable) {
                // 兜底：写 flag 阶段真失败了，把本次刚建的组收掉，别留半成品（M-27）
                if (existing == null) {
                    runCatching { deleteGroupIfEmpty(ref, group.id) }
                        .onFailure { Log.failure("回滚删除组 $name", it) }
                }
                throw ex
            }
        }
        return GroupRef(group.id, group.namePlain, existing == null)
    }

    override fun ensureMember(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        if (api.getMember(dom, uuid) != null) return
        val player = api.getPlayer(uuid) ?: throw RetryLaterException("玩家 $uuid 不在 Dominion 缓存")
        // 写操作 3/8：MemberProvider.addMember
        bridge.run("member.addMember") {
            await(MemberProvider.getInstance().addMember(console, dom, player), "member.addMember")
        }
    }

    override fun putInGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        val member = api.getMember(dom, uuid) ?: throw RetryLaterException("玩家 $uuid 不是领地成员")
        if (member.groupId == groupId) return
        val group = dom.groups.firstOrNull { it.id == groupId }
            ?: throw DominionOpException("组 $groupId 不存在于领地 ${ref.id}")
        // 写操作 4/8：GroupProvider.addMember
        bridge.run("group.addMember") {
            await(GroupProvider.getInstance().addMember(console, dom, group, member), "group.addMember")
        }
    }

    override fun removeFromGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        val dom = dom(ref) ?: return
        val member = api.getMember(dom, uuid) ?: return
        if (member.groupId != groupId) return
        val group = dom.groups.firstOrNull { it.id == groupId } ?: return
        // 写操作 5/8：GroupProvider.removeMember
        bridge.run("group.removeMember") {
            await(GroupProvider.getInstance().removeMember(console, dom, group, member), "group.removeMember")
        }
    }

    override fun removeMemberFromDominion(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: return
        val member = api.getMember(dom, uuid) ?: return
        // 写操作 6/8：MemberProvider.removeMember
        bridge.run("member.removeMember") {
            await(MemberProvider.getInstance().removeMember(console, dom, member), "member.removeMember")
        }
    }

    override fun deleteGroupIfEmpty(ref: DominionRef, groupId: Int) {
        val dom = dom(ref) ?: return
        val group = dom.groups.firstOrNull { it.id == groupId } ?: return
        val members = runCatching { group.members }.getOrDefault(emptyList())
        if (members.isNotEmpty()) return
        // 写操作 7/8：deleteGroup
        bridge.run("deleteGroup($groupId)") {
            await(GroupProvider.getInstance().deleteGroup(console, dom, group), "deleteGroup($groupId)")
        }
    }

    override fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        val member = api.getMember(dom, uuid) ?: throw RetryLaterException("玩家 $uuid 不是领地成员")
        val pri = Flags.getPreFlag(flag) ?: throw DominionOpException("未知 privilege flag: $flag")
        // 写操作 8/8：MemberProvider.setMemberFlag
        bridge.run("member.setMemberFlag") {
            await(MemberProvider.getInstance().setMemberFlag(console, dom, member, pri, value), "member.setMemberFlag")
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

    @Suppress("unused")
    private fun GroupDTO.idOf(): Int = id

    private companion object {
        /** Dominion future 的等待上限；必须小于 `GlobalBridge.DEFAULT_TIMEOUT_MS`（10 s） */
        const val FUTURE_TIMEOUT_SECONDS = 8L
    }
}

package cn.trtown.dominion

import cn.lunadeer.dominion.api.DominionAPI
import cn.lunadeer.dominion.api.dtos.DominionDTO
import cn.lunadeer.dominion.api.dtos.GroupDTO
import cn.lunadeer.dominion.api.dtos.flag.Flags
import cn.lunadeer.dominion.providers.GroupProvider
import cn.lunadeer.dominion.providers.MemberProvider
import cn.trtown.model.TownRole
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
 * operator 一律 `Bukkit.getConsoleSender()`；future 只允许在异步线程 join。
 */
class DominionApiGateway : DominionGateway {

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
        val existing = dom.groups.firstOrNull { it.namePlain == name }
        val group = existing ?: await(GroupProvider.getInstance().createGroup(console, dom, name), "createGroup($name)")
        flags.forEach { (flagName, value) ->
            val flag = Flags.getPreFlag(flagName)
                ?: throw DominionOpException("未知 privilege flag: $flagName")
            if (group.getFlagValue(flag) != value) {
                await(GroupProvider.getInstance().setGroupFlag(console, dom, group, flag, value), "setGroupFlag($name,$flagName)")
            }
        }
        return GroupRef(group.id, group.namePlain, existing == null)
    }

    override fun ensureMember(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        if (api.getMember(dom, uuid) != null) return
        val player = api.getPlayer(uuid) ?: throw RetryLaterException("玩家 $uuid 不在 Dominion 缓存")
        await(MemberProvider.getInstance().addMember(console, dom, player), "member.addMember")
    }

    override fun putInGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        val member = api.getMember(dom, uuid) ?: throw RetryLaterException("玩家 $uuid 不是领地成员")
        if (member.groupId == groupId) return
        val group = dom.groups.firstOrNull { it.id == groupId }
            ?: throw DominionOpException("组 $groupId 不存在于领地 ${ref.id}")
        await(GroupProvider.getInstance().addMember(console, dom, group, member), "group.addMember")
    }

    override fun removeFromGroup(ref: DominionRef, groupId: Int, uuid: UUID) {
        val dom = dom(ref) ?: return
        val member = api.getMember(dom, uuid) ?: return
        if (member.groupId != groupId) return
        val group = dom.groups.firstOrNull { it.id == groupId } ?: return
        await(GroupProvider.getInstance().removeMember(console, dom, group, member), "group.removeMember")
    }

    override fun removeMemberFromDominion(ref: DominionRef, uuid: UUID) {
        val dom = dom(ref) ?: return
        val member = api.getMember(dom, uuid) ?: return
        await(MemberProvider.getInstance().removeMember(console, dom, member), "member.removeMember")
    }

    override fun deleteGroupIfEmpty(ref: DominionRef, groupId: Int) {
        val dom = dom(ref) ?: return
        val group = dom.groups.firstOrNull { it.id == groupId } ?: return
        val members = runCatching { group.members }.getOrDefault(emptyList())
        if (members.isNotEmpty()) return
        await(GroupProvider.getInstance().deleteGroup(console, dom, group), "deleteGroup($groupId)")
    }

    override fun setMemberFlag(ref: DominionRef, uuid: UUID, flag: String, value: Boolean) {
        val dom = dom(ref) ?: throw DominionOpException("领地 ${ref.id} 不存在")
        val member = api.getMember(dom, uuid) ?: throw RetryLaterException("玩家 $uuid 不是领地成员")
        val pri = Flags.getPreFlag(flag) ?: throw DominionOpException("未知 privilege flag: $flag")
        await(MemberProvider.getInstance().setMemberFlag(console, dom, member, pri, value), "member.setMemberFlag")
    }

    override fun ownedNames(uuid: UUID): List<String> =
        runCatching { api.getPlayerOwnDominionDTOs(uuid).map { it.name } }.getOrDefault(emptyList())

    // ---- 内部 ----

    private fun dom(ref: DominionRef): DominionDTO? = api.getDominion(ref.id)

    private fun DominionDTO.toRef(): DominionRef = DominionRef(id, owner, name)

    private fun <T> await(future: CompletableFuture<T>, op: String): T {
        return try {
            future.get(15, TimeUnit.SECONDS) ?: throw DominionOpException("$op 返回 null")
        } catch (ex: TimeoutException) {
            throw RetryLaterException("$op 超时", ex)
        } catch (ex: ExecutionException) {
            throw DominionOpException("$op 失败: ${ex.cause?.message ?: ex.message}", ex.cause)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw RetryLaterException("$op 被中断", ex)
        }
    }

    @Suppress("unused")
    private fun GroupDTO.idOf(): Int = id
}

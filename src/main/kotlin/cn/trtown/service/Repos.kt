package cn.trtown.service

import cn.trtown.store.AuditRepository
import cn.trtown.store.GrantRepository
import cn.trtown.store.GroupRepository
import cn.trtown.store.InviteRepository
import cn.trtown.store.MemberRepository
import cn.trtown.store.Storage
import cn.trtown.store.SyncTaskRepository
import cn.trtown.store.TownRepository

/** 服务层需要的仓储集合 */
class Repos(
    val towns: TownRepository,
    val members: MemberRepository,
    val groups: GroupRepository,
    val invites: InviteRepository,
    val grants: GrantRepository,
    val tasks: SyncTaskRepository,
    val audit: AuditRepository,
) {
    companion object {
        /** 从给定 Store 构建（测试用） */
        fun from(store: cn.trtown.store.Store): Repos = Repos(
            TownRepository(store),
            MemberRepository(store),
            GroupRepository(store),
            InviteRepository(store),
            GrantRepository(store),
            SyncTaskRepository(store),
            AuditRepository(store),
        )

        /** 从全局单例取；存储未就绪时抛业务异常而不是裸 IllegalStateException（M-2） */
        fun fromStorage(): Repos =
            from(Storage.storeOrNull() ?: throw TownException(TownError.STORAGE_NOT_READY))
    }
}

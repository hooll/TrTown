package cn.trtown.store

import java.io.File

/**
 * 存储单例：启动时异步初始化，之后只读。
 *
 * 任何方法都必须在异步线程调用（PLAN §5.5 / AC-24）。
 */
object Storage {

    @Volatile
    private var current: Store? = null

    val ready: Boolean
        get() = current != null

    /** 未就绪时返回 null（命令层据此给玩家明确提示，而不是抛异常，M-2） */
    fun storeOrNull(): Store? = current

    val store: Store
        get() = current ?: error("存储尚未初始化")

    /** 建池 + 建表；重复调用是幂等的（第二次直接返回已建好的实例） */
    @Synchronized
    fun init(dataFolder: File): Store {
        current?.let { return it }
        val created = StoreFactory.create(dataFolder)
        created.initSchema()
        current = created
        return created
    }

    // 仓储入口（每次构造很轻，只持有 store 引用）
    val towns: TownRepository get() = TownRepository(store)
    val members: MemberRepository get() = MemberRepository(store)
    val invites: InviteRepository get() = InviteRepository(store)
    val grants: GrantRepository get() = GrantRepository(store)
    val syncTasks: SyncTaskRepository get() = SyncTaskRepository(store)
    val audit: AuditRepository get() = AuditRepository(store)
}

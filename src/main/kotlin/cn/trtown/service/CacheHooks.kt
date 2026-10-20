package cn.trtown.service

import java.util.UUID

/**
 * 缓存失效钩子（M-15）。
 *
 * 服务层是纯 Kotlin，不能直接依赖 PAPI / Bukkit 侧的 `PlaceholderCache`；
 * 启动时由 `TrTown.onEnable` 把实现注入进来（默认空实现，单测可替换）。
 */
object CacheHooks {

    @Volatile
    var invalidator: (UUID) -> Unit = {}

    fun invalidate(uuid: UUID) {
        invalidator(uuid)
    }
}

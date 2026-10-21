package cn.trtown.dominion

import cn.lunadeer.dominion.api.dtos.flag.Flags

/**
 * Dominion privilege flag 名的**唯一**判据（M-26 / M-27）。
 *
 * 一个 flag 名合法 ⟺ 它在 `Flags.getAllPriFlags()` 里。
 * 不要再从 `Flags` 类的静态字段里反射兜底：`container` 就有静态常量
 * （`public static final PriFlag CONTAINER`）却**没有**注册，
 * `Flags.getPreFlag("container")` 返回 null，Dominion 自己也会拒绝
 * （真服实证：`/dominion group_set_flag ... container true` → `Unknown privilege flag: container.`）。
 */
object FlagNames {

    /** Dominion 真正注册的 flag 名快照。`plugin.yml` 是 `depend: ['Dominion']`，启动时已填充 */
    val registered: Set<String> by lazy {
        runCatching { Flags.getAllPriFlags() }.getOrDefault(emptyList())
            .map { it.flagName }
            .toSet()
    }

    /**
     * 预检：返回 [flags] 里不在 [known] 中的 key（空列表 = 全部合法）。
     *
     * 纯逻辑、可单测。存在的意义是**在建组之前**就拒掉非法 flag ——
     * 先 `createGroup` 再逐条 `setGroupFlag`，中间任何一条非法都会让已经建好的组变成孤儿
     * （真服残留 `TrTown_6_MAYOR`，M-27）。
     */
    fun illegal(flags: Map<String, Boolean>, known: Set<String> = registered): List<String> =
        flags.keys.filter { it !in known }
}

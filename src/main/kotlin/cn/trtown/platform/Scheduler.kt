package cn.trtown.platform

/**
 * 调度抽象。
 *
 * 服务层只依赖这个接口，不直接碰 Bukkit / Folia（PLAN §5.5）。
 * 真实实现是 `PlatformScheduler`（唯一允许接触调度器的文件，AC-2）；
 * 测试里用严格实现来断言"主线程零 DB"（AC-24）。
 */
interface Scheduler {

    /**
     * 在主线程 / 所属 region 线程执行（读 `Player`、发消息用）。
     *
     * 实现必须用 [cn.trtown.store.ThreadGuard.enterMain] 标记该线程，
     * 这样主线程上的 DB 访问会被立刻拦下。
     */
    fun runOnMain(delayTicks: Long = 0, task: () -> Unit)

    /**
     * 异步执行（DB / Dominion / 网络）。
     *
     * `delayTicks > 0`：真实调度器会真正切到异步线程；`delayTicks = 0` 只允许
     * 用于"纯内存、无阻塞"的逻辑（AC-29）。
     */
    fun runAsync(delayTicks: Long = 1, task: () -> Unit)
}

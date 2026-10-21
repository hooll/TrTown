package cn.trtown.platform

import taboolib.common.platform.function.warning

/**
 * 失败日志（M-24 / PLAN §5.14 硬规则 6）。
 *
 * 起因：真服只看到一句 `GroupCreateEvent may only be triggered synchronously.`，
 * 为了定位这句话，规划方在服务器上扫了 917 个 jar。以后任何被吞掉的 `Throwable`
 * 都必须打「一行摘要 + 完整堆栈」。
 *
 * 走 TabooLib 的 `warning`（进插件日志）而不是 `println`；`printStackTrace()` 写到
 * stderr，服务器会把两者都收进 `logs/latest.log`。
 */
object Log {

    fun failure(op: String, ex: Throwable) {
        val line = "$op 失败: ${ex.javaClass.name}: ${ex.message}"
        emit(line)
        ex.printStackTrace()
    }

    /** 需要被看见、但没有异常可打的风险提示（M-32：收尾时跳过了失败的同步） */
    fun warn(message: String) = emit(message)

    private fun emit(line: String) {
        // 不要自己加 `[TrTown]` 前缀：`warning()` 走 Bukkit Logger，它已经加过一次，
        // 再加就变成 `[TrTown] [TrTown] …`（M-34，审计在真服日志里抓到的双前缀）。
        // 日志本身也绝不允许把调用方带崩：单测环境没有 TabooLib 平台层（`warning` 是 compileOnly），
        // 真服上则一定有。退化成 stderr 也要把这一行留下。
        runCatching { warning(line) }.onFailure { System.err.println(line) }
    }
}

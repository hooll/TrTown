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
        val line = "[TrTown] $op 失败: ${ex.javaClass.name}: ${ex.message}"
        // 日志本身绝不允许把调用方带崩：单测环境没有 TabooLib 平台层（`warning` 是 compileOnly），
        // 真服上则一定有。退化成 stderr 也要把这一行留下。
        runCatching { warning(line) }.onFailure { System.err.println(line) }
        ex.printStackTrace()
    }
}

package cn.trtown.platform

import java.io.File

/**
 * 语言键合并（M-28）。
 *
 * 起因：插件**不覆盖已存在**的 `plugins/TrTown/lang/zh_CN.yml`。真服那份是旧 jar 生成的，
 * 新 jar 新增的键（如 `town.invite.received`）从未落到磁盘，于是玩家看到的是一行
 * **原始键名** `town.invite.received` —— 能跑、不报错，最容易误判成别的 bug。
 *
 * 策略：启动时把 jar 内的键**合并**进磁盘文件 —— 缺的补上，**已有的保留**
 * （不覆盖用户自己改过的翻译），并打一行"补了 N 条"。
 *
 * 只解析扁平 `key: value`（lang 文件就是这个形状），不引入 YAML 库。
 */
object LangMerge {

    /** 合并结果：追加了哪些键、最终行数 */
    data class Plan(val text: String, val appended: List<String>) {
        val changed: Boolean get() = appended.isNotEmpty()
    }

    private const val HEADER = "# 以下条目由 TrTown 启动时自动补齐（升级新增的语言键；已有条目不会被覆盖）"

    /** 从一行 `key: value` 里取 key；注释、空行、无冒号的行返回 null */
    private fun keyOf(line: String): String? {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
        val idx = trimmed.indexOf(':')
        if (idx <= 0) return null
        return trimmed.substring(0, idx).trim().takeIf { it.isNotEmpty() }
    }

    fun keysOf(text: String): Set<String> =
        text.lineSequence().mapNotNull { keyOf(it) }.toSet()

    /**
     * 纯函数：把 [defaults] 里 [existing] 没有的键追加到末尾。
     *
     * 已有键**一律不动**（用户可能改过文案），也保留原有顺序。
     */
    fun merge(existing: String, defaults: String): Plan {
        val have = keysOf(existing)
        val missing = defaults.lineSequence()
            .filter { line -> keyOf(line)?.let { it !in have } == true }
            .toList()
        if (missing.isEmpty()) return Plan(existing, emptyList())

        val builder = StringBuilder(existing.trimEnd())
        if (builder.isNotEmpty()) builder.append('\n')
        builder.append('\n').append(HEADER).append('\n')
        missing.forEach { builder.append(it.trim()).append('\n') }
        return Plan(builder.toString(), missing.mapNotNull { keyOf(it) })
    }

    /**
     * 启动时调用：读 jar 内 [resource]，补齐 [file] 缺失的键。
     *
     * 文件不存在时**不处理** —— 那种情况 TabooLib 的 `@Config` 会从 jar 整份生成，轮不到我们。
     */
    fun run(file: File, resource: String = "/lang/zh_CN.yml"): Plan {
        if (!file.isFile) return Plan("", emptyList())
        val defaults = LangMerge::class.java.getResourceAsStream(resource)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return Plan("", emptyList())
        val plan = merge(file.readText(), defaults)
        if (plan.changed) file.writeText(plan.text)
        return plan
    }
}

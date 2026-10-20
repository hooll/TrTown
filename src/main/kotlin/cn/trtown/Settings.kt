package cn.trtown

import taboolib.module.configuration.Config
import taboolib.module.configuration.Configuration

/**
 * 配置注入（TabooLib `@Config`）。
 *
 * S0 阶段仅用于探针验证，正式配置项在后续步骤按 PLAN §5.7 补齐。
 */
object Settings {

    @Config("config.yml")
    lateinit var conf: Configuration

    /** 配置是否已完成注入 */
    val injected: Boolean
        get() = this::conf.isInitialized

    /** 探针读取项 */
    val probeValue: String
        get() = if (injected) conf.getString("probe.value") ?: "<missing>" else "<not-injected>"

    /** 配置热重载（由 `admin reload` 调用） */
    fun reload() {
        if (injected) {
            conf.reload()
        }
    }
}

package cn.trtown

import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info

/** 插件版本，与 gradle.properties 中的 version 保持一致 */
const val VERSION = "0.1.0"

/**
 * TrTown 主类。
 *
 * TabooLib 会通过扫描 `taboolib.common.platform.Plugin` 的实现来定位本类，
 * jar 内的 `plugin.yml` 主类固定为 `taboolib.platform.BukkitPlugin`。
 */
object TrTown : Plugin() {

    override fun onLoad() {
        info("TrTown v$VERSION 加载中 ...")
    }

    override fun onEnable() {
        info("TrTown v$VERSION 已启用")
        Settings.reload()
        TrTownCommand.register()
        S0Probe.run()
    }

    override fun onDisable() {
        info("TrTown v$VERSION 已卸载")
    }
}

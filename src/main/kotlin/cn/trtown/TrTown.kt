package cn.trtown

import cn.trtown.store.Storage
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.submitAsync
import taboolib.platform.BukkitPlugin

/** 插件版本，与 gradle.properties 中的 version 保持一致 */
const val VERSION = "0.1.0"

/**
 * TrTown 主类。
 *
 * TabooLib 会通过扫描 `taboolib.common.platform.Plugin` 的实现来定位本类，
 * jar 内的 `plugin.yml` 主类固定为重定位后的 `taboolib.platform.BukkitPlugin`。
 */
object TrTown : Plugin() {

    override fun onLoad() {
        info("TrTown v$VERSION 加载中 ...")
    }

    override fun onEnable() {
        info("TrTown v$VERSION 已启用")
        TrTownCommand.register()
        // 建池 + 建表必须异步：submitAsync(now = true) 会在调用线程同步执行（AC-29）
        submitAsync(delay = 1) {
            runCatching { Storage.init(BukkitPlugin.getInstance().dataFolder) }
                .onSuccess {
                    info("存储就绪：${it.dialect}，共 ${it.tables.all.size} 张表")
                }
                .onFailure {
                    severe("存储初始化失败：${it.javaClass.simpleName}: ${it.message}")
                    it.printStackTrace()
                }
        }
    }

    override fun onDisable() {
        info("TrTown v$VERSION 已卸载")
    }
}

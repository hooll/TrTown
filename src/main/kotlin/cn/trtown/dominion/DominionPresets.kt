package cn.trtown.dominion

import org.bukkit.configuration.file.YamlConfiguration
import taboolib.platform.BukkitPlugin
import java.io.File

/**
 * 读取 Dominion 的 flag 预设组（PLAN §5.16.1）。
 *
 * 结构（实测 4.9.5）：`plugins/Dominion/flags.yml` → `groups.privilege.<名称>.flags: [flag_name…]`。
 * **不手抄 administration 的 7 个名字**：手抄会在 Dominion 升级新增 flag 时静默落后；
 * 读文件永远与 Dominion 自己的 GUI 一致。文件读不到直接启动失败，不回退内置常量（M-26 的教训）。
 */
object DominionPresets {

    fun load(): Map<String, List<String>> {
        val file = locate()
        if (!file.isFile) {
            throw ConfigException(
                "读不到 Dominion 的 flags.yml：${file.absolutePath}（Dominion 未安装，或数据目录不在默认位置）",
            )
        }
        val yaml = runCatching { YamlConfiguration.loadConfiguration(file) }
            .getOrElse { throw ConfigException("解析 flags.yml 失败：${it.javaClass.simpleName}: ${it.message}") }
        // 实测 4.9.5 的预设组在 `groups.privilege.<名称>.flags`；
        // 早期文档/PLAN 写的是顶层 `privilege`，那是 flag 定义段（子项是 default/enable，不是 flags）。
        // 优先读实际结构，只有顶层确实长得像预设组时才回退。
        val privilege = yaml.getConfigurationSection("groups.privilege")
            ?: yaml.getConfigurationSection("privilege")?.takeIf { section ->
                section.getKeys(false).any { section.getConfigurationSection(it)?.contains("flags") == true }
            }
            ?: throw ConfigException("flags.yml 缺少 groups.privilege 预设段：${file.absolutePath}")
        return privilege.getKeys(false).associateWith { name ->
            privilege.getConfigurationSection(name)?.getStringList("flags").orEmpty()
        }
    }

    /** `plugins/Dominion/flags.yml`（本插件的数据目录是 `plugins/TrTown`） */
    fun locate(): File {
        val dataFolder = BukkitPlugin.getInstance().dataFolder
        return File(dataFolder.parentFile, "Dominion/flags.yml")
    }
}

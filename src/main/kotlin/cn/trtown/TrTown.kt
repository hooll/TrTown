package cn.trtown

import cn.trtown.command.SuggestCache
import cn.trtown.dominion.ConfigException
import cn.trtown.dominion.DominionConfig
import cn.trtown.dominion.FlagConfig
import cn.trtown.model.TownRole
import cn.trtown.papi.TrTownPlaceholders
import cn.trtown.platform.LangMerge
import cn.trtown.platform.Lifecycle
import cn.trtown.platform.Notify
import cn.trtown.platform.PlatformScheduler
import cn.trtown.platform.Players
import cn.trtown.service.NotifyHooks
import cn.trtown.service.Repos
import cn.trtown.service.SuggestHooks
import cn.trtown.store.Storage
import org.bukkit.Bukkit
import taboolib.common.platform.Plugin
import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.warning
import taboolib.platform.BukkitPlugin
import java.io.File

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

        // M-28：插件不覆盖已存在的 lang 文件，升级新增的键必须主动合并进来，
        // 否则玩家看到的是原始键名（能跑、不报错，最难查）
        runCatching {
            val plan = LangMerge.run(File(BukkitPlugin.getInstance().dataFolder, "lang/zh_CN.yml"))
            if (plan.changed) {
                info("语言文件已补齐 ${plan.appended.size} 条键：${plan.appended.joinToString("、")}")
            }
        }.onFailure { warning("[语言] 合并失败: ${it.javaClass.simpleName}: ${it.message}") }
        cn.trtown.command.Messages.reloadIfLoaded()

        // M-3：权限配置校验，任何一条不合法都拒绝启用（避免"配了但没生效"的静默事故）
        try {
            val validated = FlagConfig.validate()
            DominionConfig.install(validated)
            validated.warnings.forEach { warning("[配置] $it") }
            info("权限配置校验通过：" + listOf(TownRole.MEMBER, TownRole.MANAGER, TownRole.MAYOR).joinToString(" / ") { role ->
                val count = validated.effective[role]?.size ?: 0
                val presets = validated.presets[role].orEmpty()
                val note = when {
                    presets.isNotEmpty() -> presets.joinToString("、")
                    role == TownRole.MEMBER -> "不设 flag，仅加成员"
                    else -> "presets 为空，继承上级"
                }
                "${role.name} $count 项（$note）"
            })
        } catch (ex: ConfigException) {
            severe("配置校验失败，TrTown 不启用：${ex.message}")
            throw ex
        }

        // AC-27：风险必须看得见
        warning("管理拥有领地大小 / 改名 / 子领地操作权，且 Dominion 无撤销——管理只给信得过的人")

        // 命令层：由 TabooLib 的 `@CommandHeader` / `@CommandBody` 注解在 ENABLE 阶段自动注册，
        // 这里不再手动 register()（PLAN §5.6.2 硬约束 4）。
        Lifecycle.registerListener()
        // PLAN §5.18.5：GUI 的聊天输入监听器（只拦有待输入的玩家，绝不吞普通聊天）
        org.bukkit.Bukkit.getPluginManager().registerEvents(cn.trtown.gui.ChatInput, BukkitPlugin.getInstance())
        // v1.25：拦截 Dominion 原生转让（主领地必须用 /tt transfer 连同小镇一起转）
        cn.trtown.platform.DominionTransferListener.register()
        // M-15：服务层变更（建镇/入镇/退镇/改角色/改前缀/解散）要能立刻刷新 PAPI 缓存
        cn.trtown.papi.PlaceholderCache.installHooks()
        // M-21：补全数据接线（在线玩家名 / 镇名 / 邀请 / 领地）
        SuggestHooks.onlineNames = { Players.onlineNames() }
        SuggestCache.installHooks()
        // M-25：玩家消息接线。服务层只发钩子，投递统一走 platform/Notify（切目标玩家 region 线程）
        NotifyHooks.onInvited = { target, townName, inviterUuid ->
            Notify.send(target, "town.invite.received", townName, Players.nameOf(inviterUuid))
        }
        NotifyHooks.onJoinReminder = { target, townNames ->
            Notify.send(target, "town.invite.join-reminder", townNames.size, townNames.joinToString("、"))
        }

        // 建池 + 建表必须异步：submitAsync(now = true) 会在调用线程同步执行（AC-29）
        PlatformScheduler.runAsync(delayTicks = 1) {
            runCatching { Storage.init(BukkitPlugin.getInstance().dataFolder) }
                .onSuccess {
                    info("存储就绪：${it.dialect}，共 ${it.tables.all.size} 张表")
                    // v1.25：转让事件监听器不查库，启动时把绑定关系装进内存索引
                    cn.trtown.dominion.DominionBindings.reload(Repos.fromStorage().towns.listActive())
                    // 两个常驻任务（PLAN §5.13 上限 2 个）
                    Lifecycle.startSyncLoop(cn.trtown.Settings.syncPollTicks)
                    Lifecycle.startInviteCleanup()
                    // 启动恢复：把 PENDING 任务拉到现在
                    Repos.fromStorage().tasks.wakeAllPending()
                    SuggestCache.refreshTownsAsync()
                    // PAPI 扩展（软依赖）
                    if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
                        TrTownPlaceholders().register()
                        info("PlaceholderAPI 扩展已注册：%trtown_prefix% 等 5 个变量")
                    }
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

package cn.trtown

import taboolib.common.platform.function.info
import taboolib.common.platform.function.severe
import taboolib.common.platform.function.submit
import taboolib.common.platform.function.submitAsync
import taboolib.module.database.ColumnOptionSQLite
import taboolib.module.database.ColumnTypeSQLite
import taboolib.module.database.HostSQLite
import taboolib.module.database.Table
import taboolib.platform.BukkitPlugin
import java.io.File

/**
 * S0 探针（PLAN §6 S0 / R20）。
 *
 * 逐条验证 TabooLib 6.3 在本服（Folia 26.3）上能否工作：
 * 1. `@Config` 配置注入
 * 2. `Table` + HikariCP 建表 / 写入 / 读取
 * 3. `submit` / `submitAsync` 调度
 * 4. `command()` DSL 注册（见 [TrTownCommand]）
 *
 * 结果全部打印到控制台，前缀 `[S0]`，由执行方抄进 EXEC。
 */
object S0Probe {

    private const val P = "[S0]"

    fun run() {
        info("$P Folia 环境: ${if (isFolia()) "YES (RegionizedServer 存在)" else "NO"}，服务端: ${BukkitPlugin.getInstance().server.name} ${BukkitPlugin.getInstance().server.version}")
        probeConfig()
        probeSubmit()
        probeDatabase()
    }

    private fun isFolia(): Boolean = runCatching {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    }.getOrDefault(false)

    /** 探针 1：@Config 注入 */
    private fun probeConfig() {
        if (!Settings.injected) {
            severe("$P @Config 注入: FAIL（Settings.conf 未初始化）")
            return
        }
        val value = Settings.probeValue
        if (value == "probe-ok") {
            info("$P @Config 注入: OK（config.yml -> probe.value = $value）")
        } else {
            severe("$P @Config 注入: FAIL（probe.value 读到 $value，期望 probe-ok）")
        }
    }

    /** 探针 3：submit / submitAsync */
    private fun probeSubmit() {
        val caller = Thread.currentThread().name
        submit(now = true) {
            info("$P submit(): OK（执行线程 ${Thread.currentThread().name}，调用线程 $caller）")
        }
        submitAsync(now = true) {
            info("$P submitAsync(): OK（执行线程 ${Thread.currentThread().name}）")
        }
    }

    /** 探针 2：Table + HikariCP（异步执行，避免阻塞启动） */
    private fun probeDatabase() {
        val host = HostSQLite(File(BukkitPlugin.getInstance().dataFolder, "trtown.db"))
        val table = Table("trtown_probe", host) {
            add("k") { type(ColumnTypeSQLite.TEXT, 64) { options(ColumnOptionSQLite.PRIMARY_KEY) } }
            add("v") { type(ColumnTypeSQLite.TEXT, 64) }
        }
        submitAsync(delay = 20) {
            runCatching {
                val dataSource = host.createDataSource()
                table.createTable(dataSource)
                table.delete(dataSource) { where("k" eq "probe") }
                table.insert(dataSource, "k", "v") { value("probe", "ok") }
                var read: String? = null
                table.select(dataSource) { where("k" eq "probe") }.first {
                    read = getString("v")
                }
                if (read == "ok") {
                    info("$P Table/HikariCP: OK（建表 + 写入 + 读取 = $read，线程 ${Thread.currentThread().name}）")
                } else {
                    severe("$P Table/HikariCP: FAIL（读回值为 $read）")
                }
            }.onFailure {
                severe("$P Table/HikariCP: FAIL（${it.javaClass.simpleName}: ${it.message}）")
                it.printStackTrace()
            }
        }
    }
}

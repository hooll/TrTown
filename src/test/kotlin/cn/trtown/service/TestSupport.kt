package cn.trtown.service

import cn.trtown.model.TownRole
import cn.trtown.store.Store
import cn.trtown.store.StoreFactory
import org.sqlite.SQLiteDataSource
import taboolib.module.database.HostSQLite
import java.io.File
import kotlin.io.path.createTempDirectory

/** 单测公共设施：临时 SQLite 库 + 固定规则 */
object TestSupport {

    private val tempDirs = mutableListOf<File>()

    val rules = TownRules(
        nameRegex = "^[A-Za-z0-9_\\u4e00-\\u9fa5]{3,16}$",
        maxMembers = 3,
        maxTownsPerPlayer = 1,
        inviteExpireSeconds = 300,
        maxPendingInvites = 50,
        transferDemoteTo = TownRole.MEMBER,
    )

    fun store(): Store {
        val dir = createTempDirectory("trtown-test").toFile()
        tempDirs += dir
        val file = File(dir, "test.db")
        val dataSource = SQLiteDataSource().apply { url = "jdbc:sqlite:${file.absolutePath}" }
        val store = StoreFactory.fromSqliteHost(HostSQLite(file), dataSource)
        store.initSchema()
        return store
    }

    fun repos(store: Store): Repos = Repos.from(store)

    /** Dominion `administration` 预设的 7 项（生产 flags.yml 实测） */
    val administrationFlags = linkedMapOf(
        "admin" to true,
        "resize" to true,
        "rename_dominion" to true,
        "create_sub" to true,
        "delete_sub" to true,
        "resize_sub" to true,
        "rename_sub" to true,
    )

    /** 单测用：装一份合法的 Dominion 配置（不触碰真实 Dominion 类） */
    fun installDominionConfig() {
        cn.trtown.dominion.DominionConfig.install(
            cn.trtown.dominion.FlagConfig.Validated(
                effective = mapOf(
                    TownRole.MEMBER to emptyMap(),
                    TownRole.MANAGER to administrationFlags,
                    TownRole.MAYOR to administrationFlags,
                ),
                managed = administrationFlags.keys,
                presets = mapOf(
                    TownRole.MEMBER to emptyList(),
                    TownRole.MANAGER to listOf("administration"),
                    TownRole.MAYOR to emptyList(),
                ),
                warnings = emptyList(),
            ),
        )
        cn.trtown.dominion.DominionBindings.clear()
    }

    /** 删除本轮测试建出来的临时库 */
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
        cn.trtown.dominion.DominionBindings.clear()
    }
}

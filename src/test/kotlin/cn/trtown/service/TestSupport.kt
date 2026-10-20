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
        groupNameFormat = "TrTown_%s_%s",
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

    /** 删除本轮测试建出来的临时库 */
    fun cleanup() {
        tempDirs.forEach { runCatching { it.deleteRecursively() } }
        tempDirs.clear()
    }
}

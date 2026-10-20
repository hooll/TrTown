package cn.trtown.store

import java.sql.Connection
import javax.sql.DataSource

/**
 * 包一层 DataSource，所有取连接的动作都要过 [ThreadGuard]（AC-24）。
 */
class GuardedDataSource(private val delegate: DataSource) : DataSource by delegate {

    override fun getConnection(): Connection {
        ThreadGuard.onDbAccess()
        return delegate.connection
    }

    override fun getConnection(username: String?, password: String?): Connection {
        ThreadGuard.onDbAccess()
        return delegate.getConnection(username, password)
    }
}

package cn.trtown.dominion

import cn.trtown.Settings

/** 同步执行器参数 */
data class SyncSettings(
    val maxAttempts: Int,
    val backoffSeconds: List<Long>,
    val groupNameFormat: String,
) {
    companion object {
        fun fromSettings(): SyncSettings = SyncSettings(
            maxAttempts = Settings.syncMaxAttempts,
            backoffSeconds = Settings.syncBackoffSeconds,
            groupNameFormat = Settings.groupNameFormat,
        )
    }
}

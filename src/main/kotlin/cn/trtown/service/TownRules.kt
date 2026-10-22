package cn.trtown.service

import cn.trtown.Settings
import cn.trtown.model.TownRole

/**
 * 领域规则参数。
 *
 * 从 `config.yml` 抽出来的纯数据，服务层不直接读 TabooLib 配置（方便单测）。
 */
data class TownRules(
    val nameRegex: String,
    val maxMembers: Int,
    val maxTownsPerPlayer: Int,
    val inviteExpireSeconds: Long,
    val maxPendingInvites: Int,
    val transferDemoteTo: TownRole,
    /** 是否允许 `/tt tp <别人的镇>`（PLAN §5.17.3）；默认开，保持旧行为 */
    val teleportAllowOthers: Boolean = true,
) {
    companion object {
        fun fromSettings(): TownRules = TownRules(
            nameRegex = Settings.nameRegex,
            maxMembers = Settings.maxMembers,
            maxTownsPerPlayer = Settings.maxTownsPerPlayer,
            inviteExpireSeconds = Settings.inviteExpireSeconds,
            maxPendingInvites = Settings.maxPendingInvites,
            transferDemoteTo = Settings.transferDemoteTo,
            teleportAllowOthers = Settings.teleportAllowOthers,
        )
    }
}

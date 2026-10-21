package cn.trtown.service

/** 业务错误码。命令层（S4）负责把这些码映射到 I18n 文案。 */
enum class TownError(val message: String) {
    STORAGE_NOT_READY("存储未就绪"),
    NAME_INVALID("镇名不合法（3-16 个中文/字母/数字/下划线）"),
    NAME_TAKEN("镇名已被占用"),
    TOWN_NOT_FOUND("小镇不存在"),
    TOWN_DISBANDED("小镇已解散"),
    TOWN_LIMIT("建镇数量已达上限"),
    TOWN_FULL("小镇人数已满"),
    ALREADY_IN_TOWN("你已经在某个小镇里"),
    NOT_IN_TOWN("你不在任何小镇里"),
    NOT_MAYOR("只有镇长可以这么做"),
    NO_PERMISSION("你没有权限这么做"),
    TARGET_NOT_MEMBER("对方不是本镇成员"),
    TARGET_IN_TOWN("对方已在其他小镇"),
    CANNOT_TARGET_SELF("不能对自己这么做"),
    CANNOT_TARGET_MAYOR("不能对镇长这么做，请先转让镇长"),
    MAYOR_MUST_TRANSFER("镇长必须先转让才能退出"),
    MANAGER_CANNOT_TOUCH_MANAGER("管理只能管理普通成员"),
    ROLE_INVALID("目标阶级不合法"),
    INVITE_NOT_FOUND("没有待处理的邀请"),
    INVITE_EXPIRED("邀请已过期"),
    INVITE_LIMIT("待处理邀请已达上限"),
    TOWN_ALREADY_BOUND("小镇已绑定主领地"),
    TOWN_NOT_BOUND("小镇未绑定主领地"),
    DOMINION_NOT_FOUND("找不到该领地"),
    DOMINION_NOT_OWNER("你不是该领地的所有者"),
    DOMINION_IN_USE("该领地已被其他小镇绑定"),
    DOMINION_BIND_FAILED("绑定失败，已回滚"),
    PREFIX_TOO_LONG("前缀太长"),
    PREFIX_DENY_CHAR("前缀包含不允许的字符"),
    PREFIX_INVALID("前缀不合法"),
    PREFIX_DISABLED("前缀功能已关闭"),
}

class TownException(val error: TownError, val detail: String? = null) :
    RuntimeException(if (detail.isNullOrBlank()) error.message else "${error.message}（$detail）")

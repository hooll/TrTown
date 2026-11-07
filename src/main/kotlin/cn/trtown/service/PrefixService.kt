package cn.trtown.service

import cn.trtown.Settings
import cn.trtown.model.Town
import cn.trtown.model.TownRole
import cn.trtown.model.TownState
import cn.trtown.util.PrefixCodec
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 前缀相关的纯数据规则（从 `config.yml` 抽出，便于单测，不依赖 TabooLib 配置对象）。
 */
data class PrefixRules(
    val enabled: Boolean,
    val maxLength: Int,
    val allowColor: Boolean,
    val displayFormat: String,
    val fallbackToName: Boolean,
    val denyChars: List<String>,
) {
    companion object {
        /** raw 输入硬上限（UTF-16 单元），解析前先验。 */
        const val MAX_RAW_UNITS: Int = PrefixCodec.MAX_RAW_UNITS

        fun fromSettings(): PrefixRules = PrefixRules(
            enabled = Settings.prefixEnabled,
            maxLength = Settings.prefixMaxLength,
            allowColor = Settings.prefixAllowColor,
            displayFormat = Settings.prefixDisplayFormat,
            fallbackToName = Settings.prefixFallbackToName,
            denyChars = Settings.prefixDenyChars,
        )
    }
}

/** 打开编辑器前的目标快照：草稿只存在于内存，不写库。 */
data class PrefixTarget(
    val town: Town,
    val originalPrefix: String?,
    /** 打开时的草稿原文（自定义优先，否则按回退规则取镇名；已按 allow-color 去色）。 */
    val draftRaw: String,
    val role: TownRole,
    val rules: PrefixRules,
)

/** 设置页只读快照：成员也能看到已保存效果。 */
data class PrefixView(
    val town: Town,
    val role: TownRole,
    val rendered: String,
    val raw: String,
)

/** 保存结果；业务失败用异常表达，这里只区分可预期分支。 */
sealed interface PrefixSaveResult {
    data class Saved(val value: String?) : PrefixSaveResult
    data object Unchanged : PrefixSaveResult
    data object Conflict : PrefixSaveResult
    data object Busy : PrefixSaveResult
}

/**
 * 小镇前缀：校验、设置、渲染。
 *
 * 订阅层（命令 / GUI）共用同一套校验与保存入口；GUI 不拼命令字符串。
 * 本类只做同步业务，调用方必须保证在异步线程执行数据库访问。
 */
class PrefixService(
    private val repos: Repos,
    private val rules: PrefixRules = PrefixRules.fromSettings(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 设置页/编辑器打开前的目标校验：只允许镇长或管理编辑。 */
    fun openTarget(actor: UUID): PrefixTarget {
        if (!rules.enabled) throw TownException(TownError.PREFIX_DISABLED)
        val (member, town) = memberTown(actor)
        if (!member.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
        return PrefixTarget(town, town.prefix, rawOf(town), member.role, rules)
    }

    /** 设置页只读视图：成员也能查看，不创建可编辑草稿。 */
    fun readView(actor: UUID): PrefixView {
        val (member, town) = memberTown(actor)
        return PrefixView(town, member.role, render(town), rawOf(town))
    }

    private fun memberTown(actor: UUID): Pair<cn.trtown.model.Member, Town> {
        val member = repos.members.findByUuid(actor) ?: throw TownException(TownError.NOT_IN_TOWN)
        val town = repos.towns.findById(member.townId) ?: throw TownException(TownError.TOWN_NOT_FOUND)
        if (town.state != TownState.ACTIVE) throw TownException(TownError.TOWN_DISBANDED)
        return member to town
    }

    /**
     * 统一校验：所有写入口（命令 / 铁砧 / 样式 / 渐变 / 未来接入）都必须走这里。
     *
     * @param raw 原始输入，可含 `&` / `§` 颜色与格式码
     * @param allowEmpty true 时空白/仅颜色返回 null（= 回退默认）；false 时抛业务异常
     * @return 规范 source（`&` 码 + 大写 `&#RRGGBB`）；空白返回 null
     */
    fun validate(raw: String, allowEmpty: Boolean = true): String? =
        PrefixValidator.validate(raw, rules, allowEmpty)

    /** 兼容旧测试/调用：清洗并返回规范串；空白 = null。 */
    fun clean(raw: String): String? = validate(raw, allowEmpty = true)

    /**
     * 命令入口：`setprefix reset`（忽略大小写）写 null，其余按普通文本校验。
     *
     * 与 GUI 保存共用同一套校验、单飞与原子条件提交；无变化不发审计。
     */
    fun set(actor: UUID, raw: String): String? {
        if (!rules.enabled) throw TownException(TownError.PREFIX_DISABLED)
        if (!PrefixSaves.begin(actor)) throw TownException(TownError.PREFIX_SAVING)
        try {
            val (member, town) = memberTown(actor)
            if (!member.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
            val value = if (raw.trim().equals(RESET_WORD, ignoreCase = true)) {
                null
            } else {
                validate(raw, allowEmpty = true)
            }
            val current = town.prefix
            if (value == current) return value
            val saved = repos.towns.savePrefixWithAudit(
                townId = town.id,
                expected = current,
                newValue = value,
                actor = actor,
                action = "setprefix",
                detail = value ?: "<reset>",
                ts = clock(),
            )
            if (!saved) throw TownException(TownError.PREFIX_CONFLICT)
            invalidateTown(town.id)
            return value
        } finally {
            PrefixSaves.end(actor)
        }
    }

    /**
     * GUI 保存入口：草稿在内存里，只有这里触发业务。
     *
     * 最终重查成员角色、ACTIVE 镇、会话目标镇与当前配置；条件更新使用打开时的原始 prefix，
     * 其他管理在此期间改过就返回 [PrefixSaveResult.Conflict]，绝不覆盖新值。
     */
    fun saveDraft(
        actor: UUID,
        townId: Int,
        openedPrefix: String?,
        reset: Boolean,
        draftChanged: Boolean,
        draftSource: String,
    ): PrefixSaveResult {
        if (!rules.enabled) throw TownException(TownError.PREFIX_DISABLED)
        if (!PrefixSaves.begin(actor)) return PrefixSaveResult.Busy
        try {
            val (member, town) = memberTown(actor)
            if (!member.role.isManagerOrAbove) throw TownException(TownError.NO_PERMISSION)
            if (town.id != townId) throw TownException(TownError.PREFIX_CONFLICT)
            val value = when {
                reset -> null
                !draftChanged -> openedPrefix
                else -> validate(draftSource, allowEmpty = true)
            }
            if (value == openedPrefix) return PrefixSaveResult.Unchanged
            val saved = repos.towns.savePrefixWithAudit(
                townId = town.id,
                expected = openedPrefix,
                newValue = value,
                actor = actor,
                action = "setprefix",
                detail = value ?: "<reset>",
                ts = clock(),
            )
            if (!saved) return PrefixSaveResult.Conflict
            invalidateTown(town.id)
            return PrefixSaveResult.Saved(value)
        } finally {
            PrefixSaves.end(actor)
        }
    }

    /** 前缀原文（未套格式）：自定义前缀优先，否则回落到镇名。 */
    fun rawOf(town: Town): String {
        val custom = town.prefix
        val value = if (!custom.isNullOrBlank()) {
            custom
        } else if (rules.fallbackToName) {
            town.name
        } else {
            ""
        }
        if (!rules.allowColor && value.isNotEmpty()) {
            // 读侧同样去色：配置关掉颜色后，历史彩色前缀不能继续显示颜色
            return PrefixCodec.stripColors(value) ?: value
        }
        return value
    }

    /** 套上 `prefix.display-format`，返回带 `&` 颜色码的字符串。 */
    fun render(town: Town): String {
        if (!rules.enabled) return ""
        val raw = rawOf(town)
        if (raw.isEmpty()) return ""
        return rules.displayFormat.replace("%s", raw)
    }

    /** 给 PAPI 用：先查镇，再渲染。 */
    fun renderFor(uuid: UUID): String {
        val member = repos.members.findByUuid(uuid) ?: return ""
        val town = repos.towns.findById(member.townId) ?: return ""
        return render(town)
    }

    /** 全镇成员缓存失效（PAPI 钩子会异步重建在线玩家）。 */
    private fun invalidateTown(townId: Int) {
        repos.members.listByTown(townId).forEach { CacheHooks.invalidate(it.uuid) }
    }

    companion object {
        /** 命令保留字；GUI 正文里的同名字面量按普通文字保存。 */
        const val RESET_WORD: String = "reset"

        /**
         * 预览：raw 为空时按 `fallback-to-name` 回退镇名。
         *
         * saved 与 draft 共用同一函数，保证"保存后结果 = 预览结果"。
         */
        fun previewOf(raw: String?, townName: String, rules: PrefixRules): String =
            PrefixValidator.previewOf(raw, townName, rules)
    }
}

/** 前缀保存单飞：同一玩家重复点击只接受第一次。 */
object PrefixSaves {
    private val inFlight = ConcurrentHashMap.newKeySet<UUID>()

    fun begin(uuid: UUID): Boolean = inFlight.add(uuid)

    fun end(uuid: UUID) {
        inFlight.remove(uuid)
    }

    fun isSaving(uuid: UUID): Boolean = inFlight.contains(uuid)

    internal fun clearForTest() {
        inFlight.clear()
    }
}

package cn.trtown.gui

import cn.trtown.Settings
import cn.trtown.command.Cmds
import cn.trtown.command.Messages
import cn.trtown.model.TownRole
import cn.trtown.platform.PlatformScheduler
import cn.trtown.service.PrefixRules
import cn.trtown.service.PrefixSaveResult
import cn.trtown.service.PrefixTarget
import cn.trtown.service.PrefixValidator
import cn.trtown.service.TownError
import cn.trtown.service.TownException
import cn.trtown.util.PrefixStyleEditor
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 编辑器内部页面。 */
internal enum class PrefixPage { EDITOR, STYLE, GRADIENT }

/** 铁砧输入用途。 */
internal enum class PrefixInputMode { TEXT, HEX_RANGE, HEX_FROM, HEX_TO }

/**
 * 一份前缀编辑草稿（只存在内存）。
 *
 * 窗口身份用 [windowId]：每次打开新页面都递增，旧页面的点击/关闭回调据此丢弃，
 * 避免内部换页误触发 Esc 返回循环，也避免旧回调把已关闭的窗口重新顶开。
 */
internal class PrefixEditSession(
    val uuid: UUID,
    val townId: Int,
    val townName: String,
    val openedPrefix: String?,
    val role: TownRole,
    val rules: PrefixRules,
    val configGeneration: Long,
    /** display-format 中 `%s` 前的外壳颜色；样式编辑用它落定未指定颜色的字符。 */
    val shellColor: String,
    initialChars: List<PrefixStyleEditor.StyledChar>,
    val unreadable: Boolean,
) {

    var chars: List<PrefixStyleEditor.StyledChar> = initialChars
        private set

    /** 是否点了"恢复默认"（保存写 null）。 */
    var reset: Boolean = false

    /** 草稿是否真的被改过；未改过时保存等同于原值，不会把默认镇名变成自定义前缀。 */
    var draftChanged: Boolean = false

    var anchor: Int? = null
    var selStart: Int = 0
    var selEnd: Int = -1
    var page: Int = 0
    var gradientFrom: String = "55FFFF"
    var gradientTo: String = "5555FF"

    /** 铁砧 rename 包线程写入，玩家线程读取。 */
    @Volatile
    var pendingInput: String? = null

    @Volatile
    var saving: Boolean = false

    @Volatile
    var windowId: Long = 0L

    @Volatile
    var inventory: Inventory? = null

    @Volatile
    var closed: Boolean = false

    val canColor: Boolean
        get() = rules.allowColor

    val plainText: String
        get() = chars.joinToString(separator = "") { it.value }

    /** 当前草稿的规范 source（未改动时也返回打开时的内容）。 */
    val draftSource: String
        get() = PrefixStyleEditor.render(chars, rules.allowColor) ?: ""

    /** 预览用 raw：reset / 未改动时按原值（null 表示回退镇名）。 */
    val previewRaw: String?
        get() = when {
            reset -> null
            !draftChanged -> openedPrefix
            else -> draftSource
        }

    fun selection(): IntRange? = PrefixStyleEditor.normalizeRange(selStart, selEnd, chars.size)

    fun selectAll() {
        anchor = null
        selStart = 0
        selEnd = if (chars.isEmpty()) -1 else chars.size - 1
    }

    /**
     * 点字格：第一次成为单字选区并记锚点，第二次与锚点组成闭区间并清锚点。
     * 支持反向与跨页。
     */
    fun clickCharacter(index: Int) {
        if (index !in chars.indices) return
        val anchorIndex = anchor
        if (anchorIndex == null) {
            selStart = index
            selEnd = index
            anchor = index
        } else {
            selStart = minOf(anchorIndex, index)
            selEnd = maxOf(anchorIndex, index)
            anchor = null
        }
    }

    /** 应用样式修改；只有内容真的变化才标记 draftChanged。 */
    fun applyChars(newChars: List<PrefixStyleEditor.StyledChar>) {
        if (newChars == chars) return
        chars = newChars
        draftChanged = true
        reset = false
        val size = newChars.size
        selStart = selStart.coerceIn(0, maxOf(0, size - 1))
        selEnd = selEnd.coerceIn(0, maxOf(0, size - 1))
        if (size == 0) {
            selStart = 0
            selEnd = -1
        }
    }

    /** 整段替换文字；旧配色清空，重新全选。 */
    fun replaceText(newChars: List<PrefixStyleEditor.StyledChar>) {
        chars = newChars
        draftChanged = true
        reset = false
        page = 0
        selectAll()
    }

    fun markReset() {
        reset = true
        draftChanged = true
    }
}

/**
 * 首次打开编辑器的异步请求身份。
 *
 * 读取目标（成员/镇/角色）在 PrefixIo 线程完成；期间玩家可能关闭设置页、切菜单、
 * 退出或 reload。请求发出时登记原窗口与配置代次，回调必须仍是同一请求、同一窗口、
 * 同一代次才允许创建草稿并打开编辑器。
 */
internal class PrefixOpenRequest(
    val id: Long,
    val window: Any?,
    val generation: Long,
)

internal object PrefixOpenRequests {

    private val seq = AtomicLong()
    private val pending = ConcurrentHashMap<UUID, PrefixOpenRequest>()

    /** 新请求覆盖旧请求；旧回调的身份检查会失败。 */
    fun begin(uuid: UUID, window: Any?, generation: Long = Settings.generation): PrefixOpenRequest {
        val request = PrefixOpenRequest(seq.incrementAndGet(), window, generation)
        pending[uuid] = request
        return request
    }

    fun isCurrent(uuid: UUID, request: PrefixOpenRequest): Boolean =
        pending[uuid] === request && request.generation == Settings.generation

    fun complete(uuid: UUID, request: PrefixOpenRequest) {
        pending.remove(uuid, request)
    }

    fun cancel(uuid: UUID) {
        pending.remove(uuid)
    }

    /** 设置页关闭时取消仍指向该窗口的请求；已换窗口/已完成的请求不受影响。 */
    fun cancelIfWindow(uuid: UUID, window: Any?) {
        val current = pending[uuid] ?: return
        if (current.window === window) pending.remove(uuid, current)
    }

    fun invalidateAll() {
        pending.clear()
    }

    fun onQuit(uuid: UUID) {
        pending.remove(uuid)
    }

    internal fun clearForTest() {
        pending.clear()
    }
}

/**
 * 会话注册表与页面导航。
 *
 * 所有 GUI 回调都先核对窗口身份与配置代次；reload / 退出 / 卸载清空会话。
 */
internal object PrefixEditSessions {

    private val sessions = ConcurrentHashMap<UUID, PrefixEditSession>()

    /** 由打开目标构建新草稿；无镇/无权限/存储失败的判断已在服务层完成。 */
    fun begin(uuid: UUID, target: PrefixTarget): PrefixEditSession {
        val decoded = PrefixStyleEditor.decode(target.draftRaw, target.rules.allowColor)
        val unreadable = decoded == null || (target.originalPrefix != null && decoded.isEmpty())
        val session = PrefixEditSession(
            uuid = uuid,
            townId = target.town.id,
            townName = target.town.name,
            openedPrefix = target.originalPrefix,
            role = target.role,
            rules = target.rules,
            configGeneration = Settings.generation,
            shellColor = if (target.rules.allowColor) PrefixValidator.shellColor(target.rules) else "&f",
            initialChars = decoded.orEmpty(),
            unreadable = unreadable,
        )
        session.selectAll()
        return session
    }

    /** 登记会话（uuid 由玩家传入，不放进 target，避免服务层依赖 Bukkit）。 */
    fun put(uuid: UUID, session: PrefixEditSession) {
        sessions[uuid] = session
    }

    fun get(uuid: UUID): PrefixEditSession? = sessions[uuid]

    fun isCurrent(session: PrefixEditSession): Boolean =
        !session.closed &&
            sessions[session.uuid] === session &&
            session.configGeneration == Settings.generation

    fun remove(session: PrefixEditSession) {
        sessions.remove(session.uuid, session)
    }

    /** reload：旧会话与在途打开请求全部失效，重新进入才用新配置。 */
    fun invalidateAll() {
        sessions.values.forEach { it.closed = true }
        sessions.clear()
        PrefixOpenRequests.invalidateAll()
    }

    fun onQuit(uuid: UUID) {
        sessions.remove(uuid)?.closed = true
        PrefixOpenRequests.onQuit(uuid)
    }

    /** 退出/卸载释放草稿（不发消息，不重开界面）。 */
    fun clear() {
        sessions.values.forEach { it.closed = true }
        sessions.clear()
        PrefixOpenRequests.invalidateAll()
    }

    /** 打开指定页面（会话仍有效时才执行）。 */
    fun show(player: Player, session: PrefixEditSession, page: PrefixPage) {
        if (!isCurrent(session)) {
            notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        when (page) {
            PrefixPage.EDITOR -> PrefixEditorMenu.open(player, session)
            PrefixPage.STYLE -> PrefixStyleMenu.open(player, session)
            PrefixPage.GRADIENT -> PrefixGradientMenu.open(player, session)
        }
    }

    /**
     * 内部换页：先递增窗口身份并关旧窗，下一玩家 tick 再开新页。
     *
     * 旧页面的 close 回调因身份不符直接返回，不会误触发 Esc 返回循环。
     */
    fun switchTo(player: Player, session: PrefixEditSession, page: PrefixPage) {
        if (!isCurrent(session)) return
        session.windowId++
        session.pendingInput = null
        player.closeInventory()
        PlatformScheduler.runForPlayer(player, 1) {
            if (!player.isOnline || !isCurrent(session)) return@runForPlayer
            show(player, session, page)
        }
    }

    /** 放弃草稿并关闭；[reopen] 在下一 tick 执行（通常回设置页）。 */
    fun discard(player: Player, session: PrefixEditSession, reopen: (() -> Unit)? = null) {
        sessions.remove(session.uuid, session)
        session.closed = true
        session.windowId++
        player.closeInventory()
        if (reopen != null) {
            PlatformScheduler.runForPlayer(player, 1) {
                if (player.isOnline) reopen()
            }
        }
    }

    /** 点保存：立即单飞，业务全部投递到前缀专用 IO 线程。 */
    fun save(player: Player, session: PrefixEditSession) {
        if (!isCurrent(session)) {
            notice(player, Messages.get("gui.prefix.expired"))
            return
        }
        if (session.saving) {
            notice(player, Messages.get("gui.prefix.saving"))
            return
        }
        session.saving = true
        // 立即重绘为"保存中"，把按钮置灰；重复点击只提示，不再发起第二次保存
        PrefixEditorMenu.open(player, session)
        val accepted = PlatformScheduler.runPrefixIo {
            val outcome = runCatching {
                Cmds.prefix().saveDraft(
                    actor = player.uniqueId,
                    townId = session.townId,
                    openedPrefix = session.openedPrefix,
                    reset = session.reset,
                    draftChanged = session.draftChanged,
                    draftSource = session.draftSource,
                )
            }
            PlatformScheduler.runForPlayer(player) {
                session.saving = false
                if (!isCurrent(session)) {
                    // 保存已经完成，但界面在等待期间被关闭/重载；不重新开窗
                    return@runForPlayer
                }
                outcome.fold(
                    onSuccess = { result -> finishSave(player, session, result) },
                    onFailure = { ex ->
                        notice(player, feedback(ex, session))
                        PrefixEditorMenu.open(player, session)
                    },
                )
            }
        }
        if (!accepted) {
            session.saving = false
            notice(player, Messages.get("gui.prefix.save-failed"))
        }
    }

    private fun finishSave(player: Player, session: PrefixEditSession, result: PrefixSaveResult) {
        when (result) {
            is PrefixSaveResult.Saved -> {
                val reset = result.value == null
                discard(player, session) {
                    SettingsMenu.open(player)
                }
                notice(player, Messages.get(if (reset) "gui.prefix.reset-saved" else "gui.prefix.saved"))
            }

            PrefixSaveResult.Unchanged -> {
                notice(player, Messages.get("gui.prefix.unchanged"))
                PrefixEditorMenu.open(player, session)
            }

            PrefixSaveResult.Conflict -> {
                discard(player, session) {
                    SettingsMenu.open(player)
                }
                notice(player, Messages.get("gui.prefix.conflict"))
            }

            PrefixSaveResult.Busy -> {
                notice(player, Messages.get("gui.prefix.saving"))
                PrefixEditorMenu.open(player, session)
            }
        }
    }

    fun feedback(ex: Throwable, session: PrefixEditSession): String {
        if (ex !is TownException) return Messages.get("gui.prefix.save-failed")
        return when (ex.error) {
            TownError.PREFIX_TOO_LONG -> {
                val parts = ex.detail?.split(">")?.map { it.trim() } ?: emptyList()
                val visible = parts.getOrNull(0)?.toIntOrNull() ?: 0
                Messages.get("gui.prefix.too-long", visible, session.rules.maxLength)
            }

            TownError.PREFIX_DENY_CHAR -> Messages.get("gui.prefix.deny-char", ex.detail ?: "")
            TownError.PREFIX_INVALID_COLOR -> Messages.get("gui.prefix.invalid-color")
            TownError.PREFIX_RAW_TOO_LONG -> Messages.get("gui.prefix.raw-too-long")
            TownError.PREFIX_EMPTY -> Messages.get("gui.prefix.text-empty")
            TownError.PREFIX_DISABLED -> Messages.get("gui.prefix.disabled")
            TownError.PREFIX_CONFLICT -> Messages.get("gui.prefix.conflict")
            TownError.PREFIX_SAVING -> Messages.get("gui.prefix.saving")
            TownError.NO_PERMISSION, TownError.NOT_IN_TOWN, TownError.TOWN_NOT_FOUND, TownError.TOWN_DISBANDED ->
                Messages.get("gui.prefix.readonly")
            else -> ex.message ?: Messages.get("gui.prefix.save-failed")
        }
    }

    /** 任意线程给玩家发消息，必须回玩家 region 线程。 */
    fun notice(player: Player, text: String) {
        PlatformScheduler.runForPlayer(player) { player.sendMessage(text) }
    }
}

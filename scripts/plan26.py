# -*- coding: utf-8 -*-
"""PLAN v1.26：删 perms + settp/tp + GUI 化。"""
import io

P = r'D:\Dev-Project\TrTown\agent-work\PLAN.md'
s = io.open(P, encoding='utf-8', newline='').read()
n = [0]


def rep(old, new, cnt=1):
    global s
    assert old in s, 'MISS: ' + old[:80]
    s = s.replace(old, new, cnt)
    n[0] += 1


# ---------- 1) 版本行 ----------
rep(
    '- 版本：**v1.25**（用户 m01521：',
    '- 版本：**v1.26**（用户 m02091：**传送点 + GUI 化**——删除只读的 `/tt perms`；新增 `/tt settp`（设置小镇传送点，'
    '**同时写 Dominion 领地传送点** `DominionDTO.setTpLocation`）与 `/tt tp`（传送到自己小镇，走 `TeleportProvider`）；'
    '**GUI 化**：`/tt` 无参改为打开 Chest 菜单，新增 `gui/` 包 6 个菜单，玩家侧操作全部有 GUI 入口（**命令全部保留**，'
    '理由：控制台/RCON/单测只能走命令）。新增 **§5.17**、**§5.18**、**AC-56…AC-60**、**W9**，命令根集合 20→**21**'
    '（`-perms` `+settp` `+tp`），2026-10-22 16:15）；v1.25（用户 m01521：'
)

# ---------- 2) 插入 §5.17 / §5.18 ----------
SEC = """## 5.17 小镇传送点：`/tt settp` 与 `/tt tp`（v1.26，用户 m02091 ②）

### 5.17.1 数据源唯一：只存 Dominion

传送点**只存在 Dominion 的 `DominionDTO.tpLocation`**，TrTown **不建表、不存副本**。

- 用户明确要求"设置小镇传送点的同时修改 Dominion 领地的传送点" → Dominion 是权威。
- M-36 的教训：写和读必须是同一份数据；存副本必然出现"TrTown 改了、Dominion 没改"或反之。
- 后果：`/tt info`、GUI 展示传送点时**现场读** `dom.getTpLocation()`（在全局线程读，结果只用于展示，不落库）。

### 5.17.2 `/tt settp`（设置传送点）

| 项 | 规格 |
|---|---|
| 权限 | `trtown.use`；**必须是该镇镇长（MAYOR）**，或 `trtown.admin` |
| 前置 | `town.dominionId != null`（先绑定主领地），否则 `town.tp.no-dominion` |
| 动作 | `bridge.run { dom.setTpLocation(player.location) }` —— **必须全局线程**（§5.14 铁律，`setTpLocation` 抛 `SQLException` 且触发 Dominion 缓存写） |
| 位置校验 | **不校验**玩家是否站在领地内 —— 传送点是领地属性，镇长可以站在任何地方设（对齐 Dominion GUI 行为） |
| 回包 | `town.tp.set`：`&a已将小镇传送点设为 &e({x}, {y}, {z})` |
| 失败 | `SQLException` / 网关异常 → `town.tp.set-failed`：`&c传送点设置失败，请稍后再试`（原文进 `Log.failure`） |

### 5.17.3 `/tt tp`（传送到小镇）

| 项 | 规格 |
|---|---|
| 权限 | `trtown.use`；必须在某个镇里（MEMBER 及以上） |
| 无参 | 传送到**自己小镇**主领地的 `tpLocation` |
| 范围 | **v1.26 只做"传送到自己小镇"**，不支持传送到别人的镇（用户没要求；且"能否进入别人的镇"是权限模型之外的新需求，另批讨论） |
| 前置 | `tpLocation == null` → `town.tp.not-set`：`&c小镇还没有设置传送点，请镇长用 &e/tt settp &c设置` |
| 动作 | `DominionAPI.getTeleportProvider().teleport(player, dom)` → `CompletableFuture<Boolean>`；`true` → `town.tp.done`，`false` → `town.tp.failed`（Dominion 判定目标点不安全/无权限） |
| 线程 | 在**玩家 region 线程**调用（`PlatformScheduler.runForPlayer`），回调也回 region 线程发消息；**不阻塞** region tick（`CompletableFuture` 异步回调） |

### 5.17.4 边界与文案（`lang/zh_CN.yml` 新增键）

| 场景 | 键 | 文案 |
|---|---|---|
| 没镇 | `town.tp.no-town` | `&c你还没有加入任何小镇` |
| 有镇但没绑领地 | `town.tp.no-dominion` | `&c小镇还没有绑定领地，镇长请先 &e/tt setterritory` |
| 绑了领地但没设传送点 | `town.tp.not-set` | `&c小镇还没有设置传送点，请镇长用 &e/tt settp &c设置` |
| 非镇长执行 settp | `town.tp.not-mayor` | `&c只有镇长能设置小镇传送点` |
| 设置成功 | `town.tp.set` | `&a已将小镇传送点设为 &e({0}, {1}, {2})` |
| 设置失败 | `town.tp.set-failed` | `&c传送点设置失败，请稍后再试` |
| 传送成功 | `town.tp.done` | `&a已传送到 &e{0}` |
| 传送失败 | `town.tp.failed` | `&c传送失败：目标点不可用或你没有进入权限` |

## 5.18 GUI 化（v1.26，用户 m02091 ③）

### 5.18.1 定位与边界（**命令不删**）

- **GUI 是玩家交互的主入口**；**命令全部保留**，一个都不删。理由：
  1. 控制台 / RCON / 自动化脚本只能走命令；
  2. 单测只能测命令层（无 Bukkit 客户端）；
  3. GUI 打不开时（客户端异常、region 线程调度失败、菜单被插件干扰）命令是唯一兜底；
  4. 老玩家习惯命令，强制 GUI 会掉效率。
- 用户原话"后续操作希望全部通过 GUI 显示、操作" → 落实为**每个玩家侧操作都有 GUI 入口**（可达性），而不是**只有** GUI 入口。
- **`/tt` 无参从"打印文字帮助"改为"打开主菜单"**；`/tt help` 保留文字版；`gui.enabled: false` 时 `/tt` 回退文字帮助（出问题可降级）。

### 5.18.2 技术选型

- **`BukkitUI` 模块 v1.12 就已装**（`build.gradle.kts:31` `install(BukkitUI)`），无需改构建。
- 用 TabooLib `HumanEntity.openMenu<Chest>(title) { rows(n); map(...); set(c, item); onClick(c) {}; handLocked(false) }`。
- **不引入 TrMenu / YAML 布局**：布局硬编码在 `gui/` 包，文案走 `lang/zh_CN.yml` 的 `gui.*` 键（保持与命令同一套消息体系）。
- 新增包 `src/main/kotlin/cn/trtown/gui/`：`GuiBase.kt`（公共开菜单/取数/占位）、`MainMenu.kt`、`TownMenu.kt`、`MembersMenu.kt`、`InviteMenu.kt`、`TerritoryMenu.kt`、`AdminMenu.kt`、`ChatInput.kt`（改前缀的聊天输入）。

### 5.18.3 线程纪律（**最容易出 bug 的地方**）

| 阶段 | 线程要求 | 实现 |
|---|---|---|
| 取数（DB / Dominion 读） | 异步 | `PlatformScheduler.runAsync { data = load() }` |
| 打开菜单 | **玩家 region 线程** | 取数完成后 `PlatformScheduler.runForPlayer(player) { player.openMenu<Chest>(...) }` |
| 点击回调 | region 线程（Bukkit 事件天然如此） | 回调里**只做**：取参数 → 关菜单/刷新；业务复用 `Cmd.run` 同构的异步路径 |
| 业务写入 | 异步 + Dominion 写回全局线程 | 新增 `GuiActions.submit(player) { ... }`，与 `Cmd.run` 同构（`ThreadGuard` + `runAsync` + `TownException` 捕获 + `Log.failure`） |

**硬约束**：点击回调里**禁止**同步调用 DB / Dominion（会卡 region tick）；禁止在 `openMenu` 之前阻塞等数据。

### 5.18.4 菜单清单（v1.26 全部实现）

| 菜单 | 入口 | 内容与交互 |
|---|---|---|
| **主菜单**（3×9） | `/tt` 无参 | 我的小镇 / 成员 / 邀请（有待处理邀请时发光 + lore 显示数量）/ 领地与传送 / 设置 / 管理员（仅 `trtown.admin`）/ 关闭 |
| **我的小镇** | 主菜单 | 镇名、前缀、成员数/上限、领地 ID、传送点坐标；按钮：传送到小镇、解绑领地（二次确认）、解散（二次确认） |
| **成员列表** | 主菜单 | 每人一个 `PLAYER_HEAD`（在线亮、离线灰）；点击 → 踢出 / 设为 MANAGER / 降为 MEMBER（按操作者角色置灰不可点） |
| **邀请** | 主菜单 | 列出待处理邀请（镇名 + 邀请人 + 剩余秒数）；左键接受、右键拒绝 |
| **领地与传送** | 主菜单 | 领地名/大小/坐标（实时读 Dominion）；按钮：**设传送点**（等价 `/tt settp`）、**传送过去**（等价 `/tt tp`） |
| **设置** | 主菜单 | 改前缀（左键进聊天输入）、查看当前前缀 |
| **管理员** | 主菜单（仅 OP） | verify / retry / force-disband / reload（对应 `/tt admin *`） |

- 所有可点击物品 **`handLocked(false)`** 且取消 `InventoryClickEvent` 默认行为（防把 GUI 物品拿走 / 防拖拽）。
- 空白格填灰色玻璃板（`XMaterial.GRAY_STAINED_GLASS_PANE`），避免误点。
- 物品一律 `buildItem(XMaterial.XXX) { name = ...; lore = ...; colored() }`，不手写 NBT。

### 5.18.5 聊天输入（改前缀）

TabooLib BukkitUI 没有"关菜单后读聊天"的能力 → 自实现 `gui/ChatInput.kt`：
1. 点"改前缀" → 关菜单 → 提示 `&e请在聊天栏输入新前缀（30 秒内，输入 &c取消&e）`；
2. `ConcurrentHashMap<UUID, InputKind>` 记 pending；`AsyncPlayerChatEvent`（`EventPriority.LOWEST`）里命中则 `event.isCancelled = true` 并转调 `Cmd.run` 的同一业务；
3. 超时 30 秒、或执行任意 `/tt` 子命令、或输入"取消" → 清除 pending；
4. **只对白名单玩家生效**，不干扰正常聊天。

### 5.18.6 帮助与发现性

- 主菜单每个按钮都有 lore（说明这个按钮干什么、需要什么权限）。
- `/tt help` 文字帮助里补两行：`/tt settp`、`/tt tp`，并删除 `perms` 行。
- README 补一节「GUI 怎么用」（截图可后补，至少写清每个菜单从哪进）。

### 5.18.7 配置（`config.yml` 新增）

```yaml
gui:
  enabled: true          # false → /tt 回退文字帮助
  refresh-seconds: 3     # 成员/邀请菜单的自动刷新间隔（实现为点击时重开即可，此项先留占位）
```

## 5.19 删除 `/tt perms`（v1.26，用户 m02091 ①）

- 删干净四处：`MainCommand.kt:83` 的 `@CommandBody val perms`、`CommandSupport.kt:93` 的注释、`:99` 的 `Cmd.replyKey(sender, "town.perms.flags-header")`、`:212` 的帮助文本行。
- 消息键 `town.perms.flags-header` / `town.perms.flag-line` 从 `lang/zh_CN.yml` 删除（`LangMerge` 只补不删，历史文件里残留无副作用，但源码里不留）。
- 能力表、PLAN 命令表、README、`/tt help` 同步删。
- **保留**：`trtown_members.perms` 数据列（v1 未用，但属表结构，删列要迁移，留着不碍事）。

"""
rep('## 6 实施步骤', SEC + '## 6 实施步骤')

# ---------- 3) §6.2 「GUI 不在本次范围」 ----------
rep(
    '- 已知不可交付的东西（GUI 菜单、经济、聊天频道 `/tc`、申请制加入）属于 §11 路线图，**不在本次范围**，别顺手做。',
    '- 已知不可交付的东西（经济、聊天频道 `/tc`、申请制加入）属于 §11 路线图，**不在本次范围**，别顺手做。'
    '**GUI 菜单已在 v1.26 纳入范围**（§5.18），不再是路线图项。'
)

# ---------- 4) 命令表：删 perms 行、加 settp/tp ----------
rep(
    '| `perms [player]` | 自己可看自己；镇长/管理可看他人 | — | 只读：列出该成员在 Dominion 里的 flag 实况（角色应有值 vs 实际值，不一致的行标出） |\n',
    ''
)
rep(
    '| `unsetterritory` | 镇长 | 已绑定 | 全量 `MEMBER_REMOVE` + 解绑（不删领地本身） |',
    '| `unsetterritory` | 镇长 | 已绑定 | 全量 `MEMBER_REMOVE` + 解绑（不删领地本身） |\n'
    '| `settp` | 镇长 / 管理 | 已绑定领地 | 把**玩家当前位置**写进 Dominion 领地的 `tpLocation`（§5.17.2，必须全局线程） |\n'
    '| `tp` | 镇成员 | 已设传送点 | 传送到**自己小镇**领地的 `tpLocation`（§5.17.3，走 `TeleportProvider`） |'
)

# ---------- 5) 能力表 ----------
rep(
    '| **查看权限实况** `perms`（只读；v1.25 起不再能改权限，要改直接用 Dominion 命令） | ✅（任何人） | ✅（仅 `MEMBER`） | ❌ |\n',
    ''
)

# ---------- 6) 用法示例 ----------
rep(
    '/trtown perms [player]                  # 阶级默认 → 个人覆写 → 生效值\n',
    '/trtown settp                           # 把当前位置设为小镇传送点（同时写 Dominion）\n'
    '/trtown tp                              # 传送到自己小镇的传送点\n'
)
rep(
    '| `/trtown perms` | 显示**自己**的权限清单（阶级默认 → 覆写 → 生效） |\n',
    '| `/trtown settp` | 设置小镇传送点（写 Dominion `tpLocation`） |\n'
    '| `/trtown tp` | 传送到自己小镇的传送点 |\n'
)

# ---------- 7) AC-42 集合与计数 ----------
rep(
    '- 根节点命令集合从 21 个降为 **20 个**（AC-42 的精确集合同步改）。',
    '- 根节点命令集合：v1.25 从 21 降为 **20**（删 `perm`）；**v1.26 变 21**（删 `perms`、加 `settp`/`tp`）。见 §7 AC-42 精确集合。'
)
rep(
    '③ 根节点的子命令集合**精确等于** `{version, help, create, invite, accept, deny, leave, kick, transfer, setrole, setprefix, prefix, setterritory, unsetterritory, info, members, list, perm, perms, disband, admin}`（**21 个**，漏挂/多挂都要红）',
    '③ 根节点的子命令集合**精确等于** `{version, help, create, invite, accept, deny, leave, kick, transfer, setrole, setprefix, prefix, setterritory, unsetterritory, settp, tp, info, members, list, disband, admin}`（**21 个**，漏挂/多挂都要红；v1.26 起 `perm`/`perms` 已删、`settp`/`tp` 新增）'
)
rep(
    '>   - `AC-42`：根命令集合 **20** 个（表体写 21 已作废）。',
    '>   - `AC-42`：根命令集合 **21** 个（v1.26：-`perms` +`settp` +`tp`；`perm` v1.25 已删）。'
)
rep(
    '> - **改写**：AC-7 改为"无参取脚下领地 + 非 owner 拒 + 一块地只绑一镇"；AC-42 根命令集合 21 → **20**（删 `perm`）；AC-41 / AC-48 的 `perm` 部分作废；',
    '> - **改写**：AC-7 改为"无参取脚下领地 + 非 owner 拒 + 一块地只绑一镇"；AC-42 根命令集合 21→20（v1.25 删 `perm`）→ **21**（v1.26 删 `perms`、加 `settp`/`tp`）；AC-41 / AC-48 的 `perm` 部分作废；'
)

# ---------- 8) §7 追加 AC-56…AC-60 ----------
AC_SEC = """>   - **AC-56**（v1.26，`/tt` 开 GUI）：`/tt` 无参 → 打开 27 格 Chest 菜单，标题含小镇名（无镇时含「未加入小镇」）；`/tt help` 仍输出文字帮助。
>   - **AC-57**（GUI 成员菜单与命令一致）：主菜单点「成员」→ 成员菜单里在线成员头颅数 = `/tt members` 行数；点击自己无副作用（不踢自己）。
>   - **AC-58**（传送点双写一致）：`/tt settp` 后，`dom.getTpLocation()` 的 world/x/y/z 与执行时玩家坐标**完全一致**（误差 0）；`/tt tp` 后玩家坐标与之一致。
>   - **AC-59**（传送点边界）：未绑领地 `/tt settp` → `town.tp.no-dominion`；未设传送点 `/tt tp` → `town.tp.not-set`；非镇长 `/tt settp` → `town.tp.not-mayor`。
>   - **AC-60**（GUI 可降级）：`gui.enabled: false` 时 `/tt` 回退文字帮助，且所有菜单入口不再打开。
"""
rep(
    '>   - **AC-55**：残留清理 —— 部署后 `dominion_group` 里 `name like \'TrTown_%\'` 命中 **0**。\n',
    '>   - **AC-55**：残留清理 —— 部署后 `dominion_group` 里 `name like \'TrTown_%\'` 命中 **0**。\n' + AC_SEC
)

# ---------- 9) §6 加 W9 ----------
rep(
    '8. **W9 部署按 §5.11**：备份 → 低峰 → 放 jar → 重启 → 冒烟；回滚 = 删 jar 重启。**不回滚数据库**（v1 没有迁移脚本，删 jar 后旧表留不着事）。'.replace('留不着事', '留着不碍事'),
    '8. **W9 部署按 §5.11**：备份 → 低峰 → 放 jar → 重启 → 冒烟；回滚 = 删 jar 重启。**不回滚数据库**（v1 没有迁移脚本，删 jar 后旧表留着不碍事）。\n'
    '9. **W10（v1.26）顺序必须是这样**：先删 `perms`（最小、独立、先绿单测）→ 再做 `settp`/`tp`（`Bridge` 写 + `TeleportProvider`）→ **最后**做 GUI（依赖前两者提供的服务方法）。'
    'GUI 的点击回调**必须**复用 `GuiActions.submit`（与 `Cmd.run` 同构），不许在回调里直接调 service。'
)

io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('replaced=%d, len=%d' % (n[0], len(s)))

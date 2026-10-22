# -*- coding: utf-8 -*-
"""AUDIT 第 23 轮：v1.26 需求（删 perms + settp/tp + GUI 化）。"""
import io

P = r'D:\Dev-Project\TrTown\agent-work\AUDIT.md'
s = io.open(P, encoding='utf-8', newline='').read()

SEC = """## 第 23 轮 — v1.26 需求：删 `perms` + 小镇传送点（`settp`/`tp`）+ GUI 化（2026-10-22 16:20）

**用户原话（m02091）**：

1. 把 `/tt perms` 命令删掉
2. 新增 `/tt settp` 设置小镇传送点 和 `/tt tp` 传送到小镇，设置小镇传送点的**同时修改 Dominion 领地的传送点**
3. 完善 GUI 操作，**后续操作希望全部通过 GUI 显示、操作**

**本轮性质**：这是**需求变更**（不是缺陷修复）。用户上一轮（m01979）刚部署 v1.25 并要求验收，中途插入新需求 → 按流程先改 PLAN、写规格，再交执行方实现；**v1.25 的真机验收（§8.1 第 7/11/12/14 步）依然挂着**，不能因为新需求就当作已验收。

### 23.1 三点需求与 PLAN 落点

| # | 需求 | PLAN 落点 | 关键决策 |
|---|---|---|---|
| ① | 删 `/tt perms` | **§5.19** | 四处代码 + 两个消息键；根命令 21→19→**21**（v1.26 同时加 settp/tp） |
| ② | `settp` / `tp` | **§5.17**（5.17.1–5.17.4） | 传送点**只存 Dominion**、不建表；`setTpLocation` 必须全局线程；`tp` 只传自己小镇 |
| ③ | GUI 化 | **§5.18**（5.18.1–5.18.7） | **命令全部保留**（GUI 是新增入口不是唯一入口）；`BukkitUI` 模块 v1.12 就已装；6 个菜单 + 聊天输入；`gui.enabled` 可降级 |

### 23.2 `settp` / `tp` 的关键规格点（最容易做错的地方）

1. **双写语义**：用户说"设置小镇传送点的同时修改 Dominion 领地的传送点" → 实现上**只有一次写**：`dom.setTpLocation(player.getLocation())`。TrTown 侧不存副本（存了就会两边不一致，M-36 的教训）。
2. **线程**：`DominionDTO.setTpLocation` 是 `throws SQLException` 的同步方法 → 必须在 `bridge.run { }`（全局线程）里调；`TeleportProvider.teleport` 返回 `CompletableFuture<Boolean>` → 在 region 线程发起、回调里判 `Boolean`。
3. **`tp` 的范围**：v1.26 **只做"传送到自己小镇"**。传送到别人的镇是"跨镇访问"的权限问题（谁能进谁的镇），用户没要求，**不要顺手做**。
4. **`settp` 不校验站在领地内**：对齐 Dominion GUI 行为（领地传送点是属性，不必站在里面设）。这条要写进 README，否则玩家会以为必须在领地内。
5. **失败必须留痕**：`set-failed` 走 `Log.failure("设置传送点", ex)`，别只回一句"失败"（M-24 的教训）。

### 23.3 GUI 的关键规格点

1. **命令一个都不删**。用户说"后续操作希望全部通过 GUI"，但控制台/RCON/单测/降级场景都只能靠命令 → 规格写死"GUI 是新增入口，命令保留"。**如果执行方理解成"删命令只留 GUI"，就是理解错了。**
2. **线程两段式**：取数异步 → 切回玩家 region 线程 `openMenu`。**点击回调里禁止同步调 DB/Dominion**（会卡 region tick，`paper` 在 Folia 下直接表现为该区域卡住）。
3. **`handLocked(false)` + 取消默认点击**：否则 GUI 物品能被拿到背包（这是 GUI 插件最常见的刷物品漏洞）。
4. **空白格填玻璃板**：避免点到空气格触发意外行为。
5. **聊天输入自实现**（`ChatInput.kt`）：BukkitUI 没有"关菜单读聊天"能力，必须用 `AsyncPlayerChatEvent` + pending 表 + 30 秒超时 + 白名单，否则会吃掉玩家正常聊天。
6. **降级开关** `gui.enabled: false` → `/tt` 回退文字帮助。GUI 出问题时玩家不至于无路可走。

### 23.4 删 `perms` 的清单（照做即可）

| 位置 | 动作 |
|---|---|
| `src/main/kotlin/cn/trtown/command/MainCommand.kt:83` | 删 `@CommandBody val perms` 整块 |
| `src/main/kotlin/cn/trtown/command/CommandSupport.kt:93` | 删注释行 |
| `CommandSupport.kt:99` | 删 `Cmd.replyKey(sender, "town.perms.flags-header")` 及其上下文方法 |
| `CommandSupport.kt:212` | 删帮助文本里的 `perms` 行，同时**加** `settp`/`tp` 两行 |
| `src/main/resources/lang/zh_CN.yml` | 删 `town.perms.*` 键（`LangMerge` 只补不删，旧文件残留无副作用，但源码里不留） |
| `agent-work/PLAN.md` / README | 已同步（§5.19、命令表、能力表、用法示例） |
| **保留** | `trtown_members.perms` 数据列（表结构，删列要迁移，不值得） |

### 23.5 下一轮任务清单（执行方，按此顺序）

1. **W10-1 删 `perms`**：四处删干净 + 帮助文本加 settp/tp + 单测里 AC-42 集合改 21。跑绿。
2. **W10-2 `settp`/`tp`**：`DominionGateway` 加 `fun setTpLocation(ref, location)` 与 `fun tpLocation(ref): Location?`（`Location` 不出 gateway）；`TerritoryService` 加 `fun settp(actor: UUID, location: BlockPosition)`、`fun tpTarget(actor: UUID): BlockPosition?`；命令层 `CmdBodies` 加两个 body；`TeleportProvider` 调用放 `platform`（命令层禁 Bukkit 类型，`Player` 只能通过 `PlatformScheduler` 拿）。跑绿 + 本地服实测 AC-58/AC-59。
3. **W10-3 GUI**：`gui/` 包 8 个文件（§5.18.2）+ `config.yml` 的 `gui.enabled` + `/tt` 无参改开菜单 + `GuiActions.submit`。跑绿 + 本地服实测 AC-56/AC-57/AC-60。
4. **EXEC 必须留证的**：`/tt settp` 前后 `dom.getTpLocation()` 的原始值、`/tt tp` 前后玩家坐标、`/tt` 打开的菜单标题与物品数、`gui.enabled=false` 时的回退证据。

### 23.6 闸门（下一轮验收用）

| # | 闸门 | 判定 |
|---|---|---|
| 1 | 静态 | `grep -rn '"perms"\\|CmdPerm' src/main/kotlin/` = **0**；`grep -rn 'perms' src/main/resources/lang/zh_CN.yml` 只剩 `trtown_members.perms` 无关项 = **0**；`gui/` 包存在且 ≥6 文件 |
| 2 | 单测 | 全绿且 **skip = 0**；AC-42 集合断言 = 21 个（精确集合见 PLAN §7） |
| 3 | 本地服 settp/tp | AC-58（双写一致）、AC-59（三条边界文案） |
| 4 | 本地服 GUI | AC-56（`/tt` 开菜单）、AC-57（成员菜单与命令一致）、AC-60（降级） |
| 5 | 线程 | 日志无 `may only be triggered synchronously`、无 `Current thread is not the global thread`；GUI 打开后 region tick 无卡顿告警 |

### 23.7 风险与提醒

1. **GUI 是这次最容易出隐蔽 bug 的部分**：点开菜单能不能刷物品、点击能不能重复触发（双击）、离线玩家头颅怎么显示、管理员菜单对非 OP 是否真的不可见 —— 这些都要执行方自己在本地服点一遍，**不能只靠单测**。
2. **`TeleportProvider.teleport` 的 `false` 分支要实测**：传送到一个不安全的点（比如领地被挖空/目标点在虚空中）会不会返回 false 还是抛异常，不确定 → 两种都要处理。
3. **`/tt tp` 在 Folia 下的跨区传送**：目标点可能在另一个 region，Dominion 的 `teleport()` 内部是 `player.teleportAsync`，理论上安全，但**必须实测**（跨 region 传送是 Folia 的高危点）。
4. **不要顺手删命令**：用户说的是"后续操作通过 GUI"，不是"只留 GUI"。删了会破坏 RCON 冒烟脚本和 §8.1 验收流程，**明确不做**。

"""

anchor = '## 第 22 轮 — v1.25 真服部署'
assert anchor in s
s = s.replace(anchor, SEC + anchor, 1)
io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('inserted, len=%d' % len(s))

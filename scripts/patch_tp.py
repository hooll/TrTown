# -*- coding: utf-8 -*-
"""PLAN/AUDIT 补丁：/tt tp 支持传送到别人的镇（用户 m02168）。"""
import io

PLAN = r'D:\Dev-Project\TrTown\agent-work\PLAN.md'
AUDIT = r'D:\Dev-Project\TrTown\agent-work\AUDIT.md'

# ---------------- PLAN ----------------
s = io.open(PLAN, encoding='utf-8', newline='').read()
n = [0]


def rep(old, new):
    global s
    assert old in s, 'MISS PLAN: ' + old[:70]
    s = s.replace(old, new, 1)
    n[0] += 1


# 1) 版本行补一句
rep(
    '命令根集合 20→**21**（`-perms` `+settp` `+tp`），2026-10-22 16:15）',
    '命令根集合 20→**21**（`-perms` `+settp` `+tp`）；**m02168 追加：`/tt tp [镇名]` 也支持传送到别人的镇**'
    '（§5.17.3，受 `teleport.allow-others` 开关约束），2026-10-22 16:30）'
)

# 2) 5.17.3 表格改写
rep(
    '| 无参 | 传送到**自己小镇**主领地的 `tpLocation` |\n'
    '| 范围 | **v1.26 只做"传送到自己小镇"**，不支持传送到别人的镇（用户没要求；且"能否进入别人的镇"是权限模型之外的新需求，另批讨论） |',
    '| 无参 | 传送到**自己小镇**主领地的 `tpLocation` |\n'
    '| 有参 `<镇名>` | 传送到**指定小镇**（任何 `ACTIVE` 且已设传送点的镇，**含别人的镇**；用户 m02168 追加）。'
    '不要求执行者是该镇成员；`town.tp.not-found` 当镇不存在或已解散 |\n'
    '| 全局开关 | `teleport.allow-others: true`（默认开）。`false` 时带镇名的 `tp` 一律拒绝回 `town.tp.others-disabled`，'
    '给不想被串门的服留一条开关（**不按镇单独设置**，那需要给 `trtown_towns` 加列做迁移，本批不做） |\n'
    '| Tab 补全 | `tp` 的参数补全所有 `ACTIVE` 镇名（`SuggestCache` 已有镇名缓存，复用） |'
)

# 3) 边界表补三行
rep(
    '| 传送失败 | `town.tp.failed` | `&c传送失败：目标点不可用或你没有进入权限` |',
    '| 传送失败 | `town.tp.failed` | `&c传送失败：目标点不可用或你没有进入权限` |\n'
    '| 指定镇不存在/已解散 | `town.tp.not-found` | `&c找不到小镇 &e{0}` |\n'
    '| 指定镇没设传送点 | `town.tp.target-not-set` | `&e{0} &c还没有设置传送点` |\n'
    '| 跨镇传送被关闭 | `town.tp.others-disabled` | `&c本服已关闭「传送到其他小镇」` |'
)

# 4) 5.17.1 末尾补一句（为什么跨镇也安全）
rep(
    '- 后果：`/tt info`、GUI 展示传送点时**现场读** `dom.getTpLocation()`（在全局线程读，结果只用于展示，不落库）。',
    '- 后果：`/tt info`、GUI 展示传送点时**现场读** `dom.getTpLocation()`（在全局线程读，结果只用于展示，不落库）。\n'
    '- **跨镇传送不引入新数据**：`/tt tp <镇名>` 只是换一个 `DominionDTO` 调同一个 `teleport()`，'
    '不查 TrTown 的成员表、不写任何库。访客在目标领地里的权限仍然是 Dominion 的 guest 权限（无 flag），'
    '**不会因为传送过去就变成成员**。'
)

# 5) config.yml 段补 teleport
rep(
    "```yaml\ngui:\n  enabled: true          # false → /tt 回退文字帮助\n  refresh-seconds: 3     # 成员/邀请菜单的自动刷新间隔（实现为点击时重开即可，此项先留占位）\n```",
    "```yaml\ngui:\n  enabled: true          # false → /tt 回退文字帮助\n  refresh-seconds: 3     # 成员/邀请菜单的自动刷新间隔（实现为点击时重开即可，此项先留占位）\nteleport:\n"
    "  allow-others: true     # false → /tt tp <镇名> 拒绝（只允许传自己小镇）\n```"
)

# 6) GUI 菜单表：说明跨镇走命令
rep(
    '| **领地与传送** | 主菜单 | 领地名/大小/坐标（实时读 Dominion）；按钮：**设传送点**（等价 `/tt settp`）、**传送过去**（等价 `/tt tp`） |',
    '| **领地与传送** | 主菜单 | 领地名/大小/坐标（实时读 Dominion）；按钮：**设传送点**（等价 `/tt settp`）、**传送过去**（等价 `/tt tp` 无参）。'
    '**跨镇传送不进 GUI**（要输镇名，菜单里放一个"填镇名"的聊天输入反而更难用）——GUI 里只给"回自己镇"，串门走 `/tt tp <镇名>` |'
)

# 7) AC 追加 AC-61
rep(
    '>   - **AC-60**（GUI 可降级）：`gui.enabled: false` 时 `/tt` 回退文字帮助，且所有菜单入口不再打开。\n',
    '>   - **AC-60**（GUI 可降级）：`gui.enabled: false` 时 `/tt` 回退文字帮助，且所有菜单入口不再打开。\n'
    '>   - **AC-61**（跨镇传送）：非成员执行 `/tt tp <别人镇>` → 成功传送到该镇 `tpLocation`，且**不改变**他任何镇的成员关系；'
    '该镇不存在 → `town.tp.not-found`；该镇无传送点 → `town.tp.target-not-set`；`teleport.allow-others: false` 时 → `town.tp.others-disabled`。\n'
)

io.open(PLAN, 'w', encoding='utf-8', newline='').write(s)

# ---------------- AUDIT ----------------
a = io.open(AUDIT, encoding='utf-8', newline='').read()
m = [0]


def repa(old, new):
    global a
    assert old in a, 'MISS AUDIT: ' + old[:70]
    a = a.replace(old, new, 1)
    m[0] += 1


repa(
    '3. **`tp` 的范围**：v1.26 **只做"传送到自己小镇"**。传送到别人的镇是"跨镇访问"的权限问题（谁能进谁的镇），用户没要求，**不要顺手做**。',
    '3. **`tp` 的范围**：v1.26 原本只做"传送到自己小镇"；**用户 m02168 追加"也可以做传送别人小镇"** → 改成 `/tt tp [镇名]`：'
    '无参传自己镇、带参传指定镇（含别人的镇）。**跨镇不引入新数据**（换一个 `DominionDTO` 调同一个 `teleport()`，不写库），'
    '访客权限仍是 Dominion guest（**传送过去不会变成成员**）。用 `teleport.allow-others`（默认 true）做全局开关。'
)
repa(
    '4. **`/tt tp` 在 Folia 下的跨区传送**：目标点可能在另一个 region，Dominion 的 `teleport()` 内部是 `player.teleportAsync`，理论上安全，但**必须实测**（跨 region 传送是 Folia 的高危点）。',
    '4. **`/tt tp` 在 Folia 下的跨区传送**：目标点可能在另一个 region，Dominion 的 `teleport()` 内部是 `player.teleportAsync`，理论上安全，但**必须实测**（跨 region 传送是 Folia 的高危点）。**跨镇传送会把这个问题放大**（目标镇在完全不同的 region，且访客与目标领地无任何关系）—— 本地服实测必须覆盖"从 A 区域传到 B 区域"。\n'
    '5. **跨镇传送的副作用（用户已拍板接受，但执行方要知道）**：任何玩家都能用 `/tt list` + `/tt tp <镇名>` 走访所有镇 → '
    '被用于盯梢/偷看是可能的。`teleport.allow-others: false` 是唯一的缓解手段（全局，不能按镇关）。'
    '**不做传送冷却**（用户未要求，避免过度设计）；如果上线后被刷，再补 CD。\n'
    '6. **`tp` 的 Tab 补全要补镇名**：`SuggestCache` 已有镇名缓存（`/tt list` 用的就是它），直接复用；不要另建缓存。'
)

# 更新 23.1 落点表里第②行
repa(
    '| ② | `settp` / `tp` | **§5.17**（5.17.1–5.17.4） | 传送点**只存 Dominion**、不建表；`setTpLocation` 必须全局线程；`tp` 只传自己小镇 |',
    '| ② | `settp` / `tp` | **§5.17**（5.17.1–5.17.4） | 传送点**只存 Dominion**、不建表；`setTpLocation` 必须全局线程；`tp [镇名]` 无参传自己镇、带参可传别人的镇（m02168 追加，受 `teleport.allow-others` 约束） |'
)
# 闸门补一条
repa(
    '| 4 | 本地服 GUI | AC-56（`/tt` 开菜单）、AC-57（成员菜单与命令一致）、AC-60（降级） |',
    '| 4 | 本地服 GUI | AC-56（`/tt` 开菜单）、AC-57（成员菜单与命令一致）、AC-60（降级） |\n'
    '| 4b | 本地服跨镇 | AC-61：非成员 `/tt tp <别人镇>` 成功且成员关系不变；三条失败分支；`allow-others: false` 生效；**跨 region 实测** |'
)

io.open(AUDIT, 'w', encoding='utf-8', newline='').write(a)
print('PLAN replaced=%d, AUDIT replaced=%d' % (n[0], m[0]))

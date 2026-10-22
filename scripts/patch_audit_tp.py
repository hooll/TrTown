# -*- coding: utf-8 -*-
"""AUDIT 第 23 轮补丁：/tt tp 支持跨镇（用户 m02168）。"""
import io

A = r'D:\Dev-Project\TrTown\agent-work\AUDIT.md'
a = io.open(A, encoding='utf-8', newline='').read()
cnt = [0]


def r(old, new):
    global a
    assert old in a, 'MISS: ' + old[:60]
    a = a.replace(old, new, 1)
    cnt[0] += 1


# 1) 23.2 第 3 条
r(
    '3. **`tp` 的范围**：v1.26 **只做"传送到自己小镇"**。传送到别人的镇是"跨镇访问"的权限问题（谁能进谁的镇），用户没要求，**不要顺手做**。',
    '3. **`tp` 的范围**：原本只做"传送到自己小镇"，**用户 m02168 追加"也可以做传送别人小镇"** → 改为 `/tt tp [镇名]`：无参传自己镇、带参传指定镇（含别人的镇）。'
    '**跨镇不引入新数据**（换一个 `DominionDTO` 调同一个 `teleport()`，不读 TrTown 成员表、不写库），访客权限仍是 Dominion guest —— **传送过去不等于加入该镇**。'
    '用 `teleport.allow-others`（默认 `true`）做全局开关；`false` 时只允许传自己镇。'
)

# 2) 23.1 落点表第 ② 行
r(
    '| ② | `settp` / `tp` | **§5.17**（5.17.1–5.17.4） | 传送点**只存 Dominion**、不建表；`setTpLocation` 必须全局线程；`tp` 只传自己小镇 |',
    '| ② | `settp` / `tp` | **§5.17**（5.17.1–5.17.4） | 传送点**只存 Dominion**、不建表；`setTpLocation` 必须全局线程；`tp [镇名]` 无参传自己镇、带参可传别人的镇（m02168 追加，受 `teleport.allow-others` 约束） |'
)

# 3) 23.7 第 3 条扩写
r(
    '3. **`/tt tp` 在 Folia 下的跨区传送**：目标点可能在另一个 region，Dominion 的 `teleport()` 内部是 `player.teleportAsync`，理论上安全，但**必须实测**（跨 region 传送是 Folia 的高危点）。',
    '3. **`/tt tp` 在 Folia 下的跨区传送**：目标点可能在另一个 region，Dominion 的 `teleport()` 内部是 `player.teleportAsync`，理论上安全，但**必须实测**。'
    '**跨镇传送把这个问题放大了**（目标镇在完全不同的 region，且访客与目标领地无任何关系）→ 本地服必须覆盖"从 A 区域传到 B 区域"，并确认传送后 `player.teleportAsync` 的 future 不阻塞 region tick。'
)

# 4) 23.7 第 4 条后面补两条
r(
    '4. **不要顺手删命令**：用户说的是"后续操作通过 GUI"，不是"只留 GUI"。删了会破坏 RCON 冒烟脚本和 §8.1 验收流程，**明确不做**。',
    '4. **不要顺手删命令**：用户说的是"后续操作通过 GUI"，不是"只留 GUI"。删了会破坏 RCON 冒烟脚本和 §8.1 验收流程，**明确不做**。\n'
    '5. **跨镇传送的副作用（用户已拍板，执行方要知道）**：任何玩家都能 `/tt list` + `/tt tp <镇名>` 走访所有镇 → 被盯梢/偷看是可能的。'
    '`teleport.allow-others: false` 是唯一缓解手段（**全局，不能按镇关**——按镇关要给 `trtown_towns` 加列做迁移，本批不做）。'
    '**不加传送冷却**（用户未要求）；上线后被刷再补。\n'
    '6. **`tp` 的 Tab 补全要补镇名**：复用 `SuggestCache` 已有的镇名缓存（`/tt list` 用的同一份），不要另建缓存、不要每次查库。'
)

# 5) 23.6 闸门加 4b
r(
    '| 4 | 本地服 GUI | AC-56（`/tt` 开菜单）、AC-57（成员菜单与命令一致）、AC-60（降级） |',
    '| 4 | 本地服 GUI | AC-56（`/tt` 开菜单）、AC-57（成员菜单与命令一致）、AC-60（降级） |\n'
    '| 4b | 本地服跨镇 | AC-61：非成员 `/tt tp <别人镇>` 成功且**成员关系不变**；三条失败分支（无此镇/无传送点/开关关闭）；**跨 region 实测** |'
)

io.open(A, 'w', encoding='utf-8', newline='').write(a)
print('replaced=%d' % cnt[0])

# -*- coding: utf-8 -*-
"""STATE 更新：v1.26 需求变更（第 23 轮）。"""
import io

P = r'D:\Dev-Project\TrTown\agent-work\STATE.md'
s = io.open(P, encoding='utf-8', newline='').read()
reps = []


def rep(old, new):
    global s
    assert old in s, 'MISS: ' + old[:70]
    s = s.replace(old, new, 1)
    reps.append(1)


# 第 4 行：阶段
old4 = s.split('\n')[3]
assert old4.startswith('- 阶段：'), old4[:30]
new4 = ('- 阶段：**待实现（需求变更 → PLAN v1.26）**（第 23 轮：用户 m02091 提三点新需求——删 `/tt perms`、新增 `/tt settp`+`/tt tp`'
        '（传送点同时写 Dominion `tpLocation`）、**GUI 化**；已改 PLAN 到 v1.26（§5.17/§5.18/§5.19 + AC-56…AC-60 + W10），'
        '命令根集合 20→21。**v1.25 已在线运行**，本批是增量开发，不动线上语义）')
rep(old4, new4)

# 第 5 行：下一步
old5 = s.split('\n')[4]
assert old5.startswith('- 下一步轮到：')
new5 = ('- 下一步轮到：**执行方**（按 PLAN v1.26 的 **W10 三步顺序**做：① 删 `perms`（§5.19，最小独立，先绿单测）；'
        '② `settp`/`tp`（§5.17，`setTpLocation` 必须全局线程、`TeleportProvider` 回调判 Boolean）；'
        '③ GUI（§5.18，`gui/` 包 6 菜单 + `ChatInput` + `gui.enabled` 降级）。'
        '验收闸门见 AUDIT §23.6 五条；**命令一个都不许删**。'
        '另：v1.25 的真人验收（§8.1 第 7/11/12/14 步、`/tt setrole PluginTest manager`）仍挂起，与本批并行）')
rep(old5, new5)

# 第 8 行：最近更新
old8 = s.split('\n')[7]
assert old8.startswith('- 最近更新：'), old8[:30]
new8 = '- 最近更新：2026-10-22 16:20 by 规划审计方（第 23 轮：v1.26 需求落 PLAN）'
rep(old8, new8)

# 备注区插入
anchor = '  - **第 22 轮（部署，规划审计方执行，2026-10-22 15:52）**'
assert anchor in s
NOTE = """  - **第 23 轮（需求变更，规划审计方，2026-10-22 16:20）**：用户 m02091 三点 —— ① 删 `/tt perms`；② 新增 `/tt settp`（设小镇传送点，**同时写 Dominion `setTpLocation`**）+ `/tt tp`（传送到自己小镇）；③ **GUI 化**，后续操作希望全走 GUI。
    - **PLAN v1.26** 已落盘：新增 **§5.17**（传送点，5.17.1–5.17.4 含 8 条文案键）、**§5.18**（GUI，5.18.1–5.18.7 含 6 菜单清单 + 线程两段式 + 聊天输入 + 降级开关）、**§5.19**（删 perms 清单）；**AC-56…AC-60**；`§7` AC-42 精确集合改 **21 个**（`-perms` `+settp` `+tp`）；`§6` 新增 **W10** 三步顺序；`§6.2` 的「GUI 不在本次范围」已改（GUI 现纳入）。
    - **关键决策**：**命令全部保留**，GUI 是新增入口不是唯一入口（控制台/RCON/单测/降级只能走命令）—— 用户说"希望全部通过 GUI"不等于"删命令"。
    - **传送点只存 Dominion**，TrTown 不建表（存副本必然两边不一致，M-36 教训）；`setTpLocation` 抛 `SQLException` → **必须 `bridge.run` 全局线程**；`/tt tp` 只做"传自己小镇"，跨镇访问是另一个需求。
    - **技术前提已核实**：`build.gradle.kts:31` 早已 `install(BukkitUI)`，GUI 无需改构建；`DominionAPI.getTeleportProvider()` + `teleport(Player, DominionDTO): CompletableFuture<Boolean>` 已用 `javap` 确认存在。
    - **AUDIT 第 23 轮**已写入（23.1 落点表 / 23.2 settp-tp 五个易错点 / 23.3 GUI 六个易错点 / 23.4 删 perms 清单 / 23.5 下一轮 W10-1…W10-3 / 23.6 五条闸门 / 23.7 四条风险：GUI 刷物品与双击、`teleport` 的 false 分支、Folia 跨 region 传送、不要顺手删命令）。
"""
s = s.replace(anchor, NOTE + anchor, 1)

io.open(P, 'w', encoding='utf-8', newline='').write(s)
print('ok, len=%d' % len(s))

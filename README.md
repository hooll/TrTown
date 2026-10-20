# TrTown

Minecraft 小镇插件（Folia 26.3 / Java 25 / Dominion 4.9.5 / TabooLib 6.3）。

## 功能

- 小镇：建镇、邀请、接受/拒绝、退出、踢人、转让、改角色、解散
- 阶级：`MAYOR`（镇长）/ `MANAGER`（管理）/ `MEMBER`（成员）
- 主领地绑定：把**一个已存在的 Dominion 领地**绑定为小镇主领地，自动建立三个阶级组
- 权限同步：入镇/改角色自动入组，退镇/被踢/解散自动撤销（**只撤销本插件记账过的授权**）
- 聊天前缀：`%trtown_prefix%` 等 5 个 PlaceholderAPI 变量，由 TrChat 引用
- 运维：`/trtown admin reload|retry|verify|force-disband`

## 安装

1. 服务器需已安装 **Dominion 4.9.5+**；建议同时安装 **PlaceholderAPI**（软依赖）。
2. 把 `TrTown-*.jar` 放进 `plugins/`，重启服务器。
3. 首次启动会生成 `plugins/TrTown/config.yml`、`lang/zh_CN.yml`、`trtown.db`。
4. 控制台应出现：
   - `权限配置校验通过：MEMBER .. 项 / MANAGER .. 项 / MAYOR .. 项`
   - `存储就绪：SQLITE，共 7 张表`
   - `PlaceholderAPI 扩展已注册`
   - 一条警告：`管理拥有领地大小 / 改名 / 子领地操作权，且 Dominion 无撤销`

> 首次启动 TabooLib 会从 `repo.tabooproject.org` 下载运行期模块；离线环境需要预置缓存，否则插件无法加载。

## 聊天前缀（由服主手动接入）

TrTown **不监听聊天**，只提供变量。把 `plugins/TrChat/channels/Normal.yml` 中
`Formats[0].prefix.part-before-player.text` 从 `' '` 改为：

```yaml
      part-before-player:
        text: ' %trtown_prefix%'
```

改前请备份该文件（例如 `Normal.yml.bak-YYYYmmdd-HHmmss`）。变量列表：
`%trtown_prefix%`、`%trtown_prefix_raw%`、`%trtown_name%`、`%trtown_role%`、`%trtown_role_display%`。

## 卸载与回滚

1. **先对每个镇执行 `/trtown disband`**，等 Dominion 里的 `TrTown_*` 组消失。
2. 删除 `plugins/TrTown-*.jar`，重启服务器。
3. 需要彻底清理时再删 `plugins/TrTown/`（含数据库）。

> 直接删 jar 而不解散，会在 Dominion 里留下 `TrTown_<镇id>_MAYOR/MANAGER/MEMBER` 三个组和成员授权。

## 风险（务必读）

- **管理 = 领地管理员**：`MANAGER` 组拥有 `admin` 以及 `resize` / `rename_dominion` / `create_sub` /
  `delete_sub` / `resize_sub` / `rename_sub`。也就是说**管理能改领地大小、给领地改名、建/删子领地**。
  **Dominion 没有撤销，TrTown 也不备份领地几何**——改小了就是把地让出去，删子领地不可逆。
  **管理只给信得过的人。**
- **只加不减**：TrTown 只撤销自己记账过的授权；镇长在 Dominion 里手工加的人、手工设的权限，不会被改动。
- **管理与镇长的唯一两条差别**：管理不能解散小镇（`/trtown disband` 被拒），也删不掉主领地
  （Dominion 里删领地是 owner 专属，没有对应 flag）。
- **个人权限覆写默认关闭**：实测 Dominion 4.9.5 在成员属于某个组时**只看组 flag**，
  成员个人 flag 被忽略，因此本功能不可用（`permissions.allow-override: false`）。
- **MySQL 是实验性的**：只在 SQLite 上做过完整验证；切到 MySQL 前请在真实例上跑一遍。
- **转让镇长不会改 Dominion 领地所有权**：若新镇长不是领地 owner，下次 `/trtown setterritory`
  会被拒绝（这是预期行为）。

## 配置要点

- `storage.type`：`sqlite`（默认）/ `mysql`
- `town.max-members`：**含镇长**的总人数上限
- `dominion.roles.*`：每档的 flag（key 必须是小写 `flag_name`）；启动时会校验 flag 是否合法、
  三档是否递进，任何一条不合法都会拒绝启用
- `prefix.*`：前缀长度/颜色/禁用字符/显示格式
- `permissions.allow-override`：个人权限覆写开关（本服实测不可用，保持 `false`）

## 构建

```bash
./gradlew clean build     # Windows: .\gradlew.bat clean build
./gradlew test
./gradlew perfTest
```

产物：`build/libs/TrTown-0.1.0.jar`

# TrTown

Minecraft 小镇插件（Folia 26.3 / Java 25 / Dominion 4.9.5 / TabooLib 6.3）。

## 功能

- 小镇：建镇、邀请、接受/拒绝、退出、踢人、转让、改角色、解散
- 阶级：`MAYOR`（镇长）/ `MANAGER`（管理）/ `MEMBER`（成员）
- 主领地绑定：把**一个已存在的 Dominion 领地**绑定为小镇主领地（`/tt setterritory` 无参 = 脚下领地）
- 权限模型（v1.25）：**不创建 Dominion 权限组**
  - 镇长 = 领地 owner，天然全权限，TrTown 不对他做任何 Dominion 写操作；
  - 成员 = 只加入 Dominion 成员表，**一个 flag 都不设**（与 Dominion GUI 手动加成员一致）；
  - 管理 = 加入成员表 + 把 `administration` 预设（7 项）设为 true；降回成员时设回 false；
  - 退镇 / 被踢 / 解散 = 从 Dominion 成员表移除。
- 领地转让：`/tt transfer <玩家>` → `/tt transfer <玩家> confirm`，小镇与主领地一起转；
  **在 Dominion GUI 里转让主领地会被拦截**（必须走 `/tt transfer`）。
- 聊天前缀：`%trtown_prefix%` 等 5 个 PlaceholderAPI 变量，由 TrChat 引用
- 运维：`/trtown admin reload|retry|verify|force-disband`

## 安装

1. 服务器需已安装 **Dominion 4.9.5+**；建议同时安装 **PlaceholderAPI**（软依赖）。
2. **确认 `plugins/Dominion/flags.yml` 存在**。TrTown 启动时从
   `groups.privilege.<preset>.flags`（Dominion 4.9.5 实测结构）展开角色权限；
   文件读不到或 preset 不存在会**拒绝启用**（不回退内置常量）。
3. 把 `TrTown-*.jar` 放进 `plugins/`，重启服务器。
4. 首次启动会生成 `plugins/TrTown/config.yml`、`lang/zh_CN.yml`、`trtown.db`。
5. 控制台应出现：
   - `权限配置校验通过：MEMBER 0 项（不设 flag，仅加成员）/ MANAGER 7 项（administration）/ ...`
   - `存储就绪：SQLITE，共 7 张表`
   - `PlaceholderAPI 扩展已注册`
   - 一条警告：`管理拥有领地大小 / 改名 / 子领地操作权，且 Dominion 无撤销`

> 首次启动 TabooLib 会从 `repo.tabooproject.org` 下载运行期模块；离线环境需要预置缓存，否则插件无法加载。

### 从 v1.24 及更早版本升级

旧版会创建 `TrTown_<镇id>_MAYOR/MANAGER/MEMBER` 组。新版**不再创建、也不再使用**这些组。

1. 备份 `plugins/TrTown/` 与 `plugins/Dominion/dominion.db`。
2. 更新 `plugins/TrTown/config.yml` 的 `dominion.roles`：旧版的逐 flag 表已废弃，
   改成 `presets: [名称…]`（默认 `MEMBER: []`、`MANAGER: [administration]`、`MAYOR: []`）。
   旧配置没有 `presets` 键时会按默认值运行，但**建议显式迁移**，避免以后误读。
3. 用 Dominion 的 `/dominion group` 相关命令清理历史 `TrTown_*` 组（**不要直接改 SQL**，
   否则 Dominion 缓存不一致）。清理前把 `dominion_member` 里 `group_id` 指向这些组的行置 `-1`。
   新版不会再产生新组；历史组不清理只会让 GUI 里多出垃圾项。

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

1. 先对每个镇执行 `/trtown disband`，等 Dominion 成员表里本镇玩家被移除。
2. 删除 `plugins/TrTown-*.jar`，重启服务器。
3. 需要彻底清理时再删 `plugins/TrTown/`（含数据库）。

> 直接删 jar 而不解散，会留下 Dominion 成员关系（TrTown 的清理任务不会跑）。
> 新版不创建组，所以不会再有 `TrTown_*` 组残留。

## 风险（务必读）

- **管理 = 领地管理员**：`MANAGER` 拿到的 `administration` 预设包含
  `admin`、`resize`、`rename_dominion`、`create_sub`、`delete_sub`、`resize_sub`、`rename_sub`。
  也就是说**管理能改领地大小、给领地改名、建/删子领地，也能加删成员、改成员 flag**。
  **Dominion 没有撤销，TrTown 也不备份领地几何**——改小了就是把地让出去，删子领地不可逆。
  **管理只给信得过的人。**
- **成员默认没有任何 flag**：`MEMBER.presets` 为空，成员在 Dominion 里只是"成员"，
  能不能破坏/开箱取决于领地的 guest 权限或镇长在 Dominion 里另行设置。这是用户明确要求的行为。
- **转让主领地会被拦截**：在 Dominion GUI 里把主领地转给他人会失败一次，并提示改用 `/tt transfer`。
  这是有意的行为变更，避免"在 Dominion 里误点一下就把镇长换了"。
- **转让任务失败不回滚 TrTown**：TrTown 先改镇长，Dominion `setOwner` 后调；任务重试耗尽会变 `DEAD`，
  `/trtown admin verify` 会报 owner 不一致，需要管理员处理。
- **MySQL 是实验性的**：只在 SQLite 上做过完整验证；切到 MySQL 前请在真实例上跑一遍。

## 配置要点

- `storage.type`：`sqlite`（默认）/ `mysql`
- `town.max-members`：**含镇长**的总人数上限
- `town.transfer-confirm-seconds`：已绑定主领地时转让的二次确认窗口（默认 10 秒）
- `dominion.roles.<ROLE>.presets`：Dominion `flags.yml` 里的预设组名列表。
  **空列表合法**（该角色不设任何 flag）；preset 不存在 / 非空但展开为空 / flag 名未注册 → 启动失败
- `prefix.*`：前缀长度/颜色/禁用字符/显示格式

## 构建

```bash
./gradlew clean build     # Windows: .\gradlew.bat clean build
./gradlew test
./gradlew perfTest
```

产物：`build/libs/TrTown-0.1.0.jar`

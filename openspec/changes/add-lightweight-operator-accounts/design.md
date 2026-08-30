## Context

会员管理端是运行在会员管理 Windows 主机上的 Electron 应用，Electron 主进程通过 IPC 将渲染进程请求转发到同机 Platform Spring Boot 后端；同一后端还通过局域网为自助注册端和游戏端提供公开业务接口。当前没有账号表、登录 API 或当前操作员状态，侧边栏账号和退出按钮只是演示 UI，会员管理写操作也主要使用固定 `createdBy` 字符串。

本设计遵循 `proposal.md` 和 `lightweight-operator-accounts` spec 确认的可信局域网假设。账号机制用于防误操作、界面区分和人员留痕，不作为后端安全边界。

## Goals / Non-Goals

**Goals:**

- 用最少的新状态实现出厂管理员与普通操作员登录、切换和退出。
- 让现有会员管理端危险入口按账号类型隐藏，同时不破坏普通操作员的日常核心流程。
- 用统一的操作账号上下文记录关键写操作，避免各页面自行拼接操作人名称。
- 保持现有 SQLite、Spring Boot、Vue 和 Electron 架构，不增加独立身份服务。
- 让现有数据库升级后自动获得出厂账号，让自动化测试可以注入独立测试凭据。

**Non-Goals:**

- 不防范了解 API 的局域网用户直接调用后端，也不防范修改渲染进程状态。
- 不实现后端逐接口角色授权、会话令牌、Cookie、JWT、刷新令牌、登录限速或并发会话。
- 不实现找回密码、恢复码、厂家远程后门或应用内出厂管理员重置。
- 不实现任意角色、权限勾选器、账号删除或复杂组织结构。
- 不改变自助注册端、游戏端和房间 WebSocket 的身份模型。

## Decisions

### 1. 登录只验证一次，当前账号只保存在 Vue 进程内存

新增一个无持久化能力的 `operatorSession` 状态模块，持有公开账号资料：

```text
{ id, username, displayName, accountType }
```

`App.vue` 在该值为空时只渲染登录页面，登录成功后渲染现有管理框架；退出时清空。模块不写入 `localStorage`、Electron 配置或 SQLite，因此页面刷新、渲染进程重载和应用重启都会回到登录页。

登录 API 只负责读取账号、检查启用状态并校验密码摘要，然后返回公开资料。后续请求不携带会话令牌。相比在 Electron 主进程维护 Cookie 或 Bearer token，这符合用户确认的轻量范围，并让浏览器开发模式和桌面模式保持相同行为。

### 2. 固定两种账号类型，不建立权限关系表

账号类型固定为：

- `FACTORY_ADMIN`：全部现有功能、账号管理和危险操作。
- `OPERATOR`：日常会员注册与查询、手环充值与回收、房间状态查看、排行榜和记录查询。

前端建立一个小型纯函数策略模块，例如 `canUse(currentOperator, capability)`，capability 只覆盖需要隐藏的少量入口。它不是可配置权限引擎，也不从数据库加载权限组合。各页面复用该函数，避免散落 `username === "admin"` 判断。

后端不根据请求调用者的角色阻止普通业务 API，但无论谁调用都禁止停用、删除或改变 `FACTORY_ADMIN` 类型，以避免正常 UI/代码错误破坏唯一出厂账号。

### 3. SQLite 保存账号与独立操作日志

新增 `operator_accounts`：

```text
id INTEGER PRIMARY KEY AUTOINCREMENT
username TEXT NOT NULL COLLATE NOCASE UNIQUE
display_name TEXT NOT NULL
password_hash TEXT NOT NULL
account_type TEXT NOT NULL CHECK(account_type IN ('FACTORY_ADMIN','OPERATOR'))
enabled INTEGER NOT NULL DEFAULT 1
created_by_operator_id INTEGER NULL
created_at TEXT NOT NULL
updated_at TEXT NOT NULL
```

账号不物理删除；普通账号通过 `enabled` 停用，以保留历史引用。密码使用 `spring-security-crypto` 的 BCrypt 摘要，初始强度使用 10；本 Change 不引入 Argon2 及额外 native/crypto 依赖。

新增 `operator_action_logs`：

```text
id INTEGER PRIMARY KEY AUTOINCREMENT
operator_id INTEGER NOT NULL
operator_username TEXT NOT NULL
operator_display_name TEXT NOT NULL
action TEXT NOT NULL
target_type TEXT NOT NULL
target_id TEXT NULL
summary_json TEXT NULL
created_at TEXT NOT NULL
```

日志同时保存账号 ID 和名称快照，使普通账号后续改名或停用后仍能解释历史记录。`summary_json` 只保存业务摘要，不写密码或完整敏感响应。

相比给所有现有业务表增加操作人列，独立日志表迁移更小，也能覆盖异构操作。现有业务记录字段在自然适合时仍可保存 `createdBy=operator:<username>`。

### 4. 出厂账号由集中配置初始化且只初始化一次

后端增加出厂账号配置，默认值集中定义为：

```text
username: admin
password: 888888
displayName: 出厂管理员
```

打包或测试环境可以通过应用属性/环境变量覆盖。后端在账号表为空时散列初始密码并创建一个 `FACTORY_ADMIN`；只要表中已经有任意账号，后续启动就不再读取出厂密码覆盖数据。

使用配置而不是 schema.sql 中的固定密码摘要，可以让测试使用独立凭据，也让厂家以后更换出厂值而不做数据库迁移。默认凭据属于产品约定，不被描述为安全秘密。

### 5. 使用白名单请求头传递操作账号 ID，但不把它当凭据

会员管理端关键写操作统一携带：

```text
X-Operator-Id: <currentOperator.id>
```

共享 API 客户端通过一个内存 `operatorIdProvider` 自动附加该头；Electron `api-transport.cjs` 只额外放行并转发这个命名头，不开放任意渲染进程请求头。后端公共的 `OperatorContextResolver` 解析 ID、确认账号存在，并向业务服务提供账号快照。

该请求头的目标是统一留痕而非认证：它可以被直接调用者伪造，这是已接受的产品权衡。需要留痕的会员管理端操作缺少或携带不存在账号时返回稳定错误；自助注册端和游戏端原有路径不要求该头。

### 6. 账号管理放入系统设置，但普通操作员看不到设置页

现有系统设置页增加“操作账号”区域，出厂管理员可以：

- 查看普通操作员列表和启用状态；
- 创建普通操作员；
- 修改用户名与显示名称；
- 重设普通操作员密码；
- 启用或停用普通操作员；
- 修改自己的密码。

普通操作员的导航中隐藏系统设置页。会员删除、主动清空手环余额、房间改名等现有控件使用同一策略模块隐藏。账号不提供删除按钮，出厂管理员自身不提供停用或类型修改控件。

### 7. 操作日志在后端业务成功后写入

账号创建/修改、会员管理、手环管理、房间改名和持久化系统设置等操作，在业务数据库更新成功后由后端写入日志。若业务操作失败则不记录“成功”日志；可选记录失败不属于第一版范围。

日志写入与对应业务更新尽可能处于同一数据库事务中，避免业务成功但操作人记录丢失。前端不单独提交“写日志”请求。

### 8. 登录和权限测试以行为边界为主

后端集成测试使用临时 SQLite 和覆盖后的出厂凭据，覆盖初始化、登录、账号 CRUD 边界、密码摘要不泄露和操作日志。前端纯函数/结构测试覆盖内存状态、角色策略、登录门禁和危险入口隐藏。桌面与门店验收在启动后先用测试出厂账号登录，再继续原有流程。

不编写“直接调用后端必须被拒绝”或“篡改前端无法提权”测试，因为这两项明确不是需求。

## Risks / Trade-offs

- [任何知道 API 的人都能绕过 UI 权限或伪造 `X-Operator-Id`] → 在产品说明和 spec 中明确该机制只防误操作；将来开放互联网或远程管理时必须另开 Change 引入真实会话和后端授权。
- [所有设备使用同一默认出厂密码] → 这是用户接受的低成本产品约定；支持厂家打包时通过配置覆盖，但不增加首次改密流程。
- [出厂密码遗失且无恢复入口] → 登录页明确提示联系厂家；产品说明区分普通覆盖安装和删除持久化 SQLite 的恢复出厂。
- [恢复出厂可能删除会员业务数据] → 厂家操作手册在执行前说明数据影响；本 Change 不实现自动备份或账号表单独修复工具。
- [普通操作员仍可能通过历史页面引用触发隐藏操作] → 当前账号变化时回到默认页面并重新计算策略；组件测试覆盖退出和不同账号重新登录后的界面重建。
- [旧自动化流程启动后被登录页阻挡] → 验收 harness 统一注入测试出厂账号并提供登录 helper，避免各场景重复实现。

## Migration Plan

1. 以幂等方式建立 `operator_accounts` 和 `operator_action_logs`，不修改或删除现有业务表数据。
2. 后端启动时仅在账号表为空时建立出厂账号；现有数据库首次升级后因此获得 `admin` 账号。
3. 先发布登录与账号 API，再接入会员管理端登录门禁和角色策略，避免前端先发布后无法进入。
4. 分批给现有关键写接口接入操作账号上下文和日志，同时保持自助注册端、游戏端调用兼容。
5. 更新打包、开发桌面和验收配置中的测试/出厂账号说明。

回滚应用版本时保留两张新增表；旧版本忽略它们。再次升级时按已有账号继续使用，不重新覆盖密码。

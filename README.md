# LED Game Platform

中心平台项目，负责会员、注册、手环、操作账号和跨终端数据同步。

## 目录

```text
apps/
  registration-kiosk/    自助注册端
  member-admin/          会员管理端
packages/
  api-client/            中心平台共享 API 客户端
  shared-ui/             两个前端共享 UI
server/                  Spring Boot 中心后端
dev/                     开发环境说明
```

会员管理端与中心后端部署在同一台 Windows 机器上，并使用 SQLite 持久化会员、手环、房间、操作账号和操作记录。自助注册端通过局域网调用中心后端，不直接访问数据库。

## 开发环境

需要 Node.js、pnpm、Java 17+ 和 Maven。

```bash
pnpm install
pnpm dev
```

默认端口：

- 自助注册端：`5176`
- 会员管理端：`5177`
- 中心后端：`8090`

也可以分别启动：

```bash
pnpm dev:registration
pnpm dev:member-admin
mvn -f server/pom.xml spring-boot:run
```

## 核心流程测试

核心业务改动遵循测试先行：先增加或修改失败测试，再实现最小可通过逻辑，最后运行针对性测试和完整核心套件。

```bash
pnpm test:core
pnpm test:e2e
```

`test:core` 会运行 Spring API 集成测试、共享 API 客户端测试以及自助端 Player Info 状态流测试。服务端测试每次创建唯一的临时 SQLite 文件，并在 Spring 测试服务关闭后删除，不会读取或修改门店正式数据库。

`test:e2e` 会在随机本机端口启动平台后端，并使用读卡器 UID `2283055618` 完成不依赖实体硬件的完整烟测：创建会员、充时、绑定、激活、开始游戏、结算和查询 Player Info。

实体读卡器行为不适合自动化。每次门店版本发布前仍需在目标 Windows 机器上做一次验收：插入键盘式读卡器，将焦点放在手环输入框，刷真实手环，并确认它只输入数字 UID 后自动发送 Enter。

## 会员管理端操作账号

新数据库会创建一个出厂管理员，默认账号为 `admin`、密码为 `888888`。厂家可通过 `PLATFORM_FACTORY_ADMIN_USERNAME`、`PLATFORM_FACTORY_ADMIN_PASSWORD` 和 `PLATFORM_FACTORY_ADMIN_DISPLAY_NAME` 在数据库首次初始化前覆盖默认值。

操作账号只用于可信门店局域网内的界面门禁和业务留痕，不是面向互联网的完整安全认证。登录状态仅保存在会员管理端当前窗口内，退出或重启应用后需要重新登录。产品不提供“忘记密码”入口；出厂管理员密码丢失时需要联系厂家处理。普通覆盖安装会沿用 userData 中的 SQLite，不会重新生成或覆盖已有账号。

## 与现有项目的关系

- `ledGame` 继续作为游戏桌面端。
- `ledGame-backend` 继续负责本地游戏运行时和 LED 硬件。
- 本项目的 `server` 是中心平台后端，不会打包进游戏桌面端。

cd /f/project/ledGame-platform
pnpm test:acceptance

## Windows 桌面端

会员管理端和自助注册端的 Electron 开发、测试与便携打包说明见 [Windows 桌面端开发与打包说明](docs/Windows桌面端开发与打包说明.md)。门店 IP、端口、防火墙和跨机冒烟步骤见 [门店局域网配置与冒烟说明](docs/门店局域网配置与冒烟说明.md)。

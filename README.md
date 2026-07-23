# LED Game Platform

中心平台项目，负责会员、注册、权限和跨终端数据同步。

## 目录

```text
apps/
  registration-kiosk/    自助注册端
  member-admin/          会员管理端
packages/
  api-client/            中心平台 API 客户端占位包
  shared-ui/             两个前端共享 UI 占位包
server/                  Spring Boot 中心后端
dev/                     开发环境说明
```

当前项目只有空壳入口，不包含会员业务、数据库表和认证逻辑。

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

## 与现有项目的关系

- `ledGame` 继续作为游戏桌面端。
- `ledGame-backend` 继续负责本地游戏运行时和 LED 硬件。
- 本项目的 `server` 是中心平台后端，不会打包进游戏桌面端。


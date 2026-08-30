# Windows 桌面端开发与打包说明

## 产品边界

本仓库生成两个相互独立的 Windows 产品：

- `LED Game 会员管理端`：包含会员管理 UI、Spring Boot 后端、SQLite 数据库运行逻辑和内置 JRE。
- `LED Game 自助注册端`：只包含自助注册 UI 与 Electron 桌面壳，通过局域网连接会员管理端，不携带 JAR、JRE 或 SQLite。

两个产品使用不同的 appId、userData 和输出目录，不能互相覆盖配置或数据。

## 构建依赖

- Windows 10/11；
- Node.js、pnpm；
- JDK 17 或更高版本，并设置 `JAVA_HOME`；
- Maven；
- 首次执行 `pnpm install` 时允许 Electron 与 esbuild 的安装脚本。

## 开发命令

开发桌面行为时使用真实 Electron main/preload 和窗口生命周期：

```shell
pnpm dev:member-admin:desktop
pnpm dev:registration:desktop
```

仅需快速预览 renderer 时，可使用辅助命令：

```shell
pnpm dev:member-admin:browser
pnpm dev:registration:browser
```

浏览器预览不是桌面权限、双窗口或发布行为的验收入口。

## 测试命令

```shell
pnpm test:desktop
pnpm test:desktop:member
pnpm test:desktop:electron
pnpm test:acceptance:desktop
```

最后一条命令会使用隔离端口和临时 SQLite，复用充时、注册绑定、游戏、排队、自然结算、跨端数据核对的门店核心流程。

## 打包命令与产物

```shell
pnpm portable:member-admin:dir
pnpm portable:member-admin:zip
pnpm portable:registration:dir
pnpm portable:registration:zip
pnpm portable:all
```

目录包与 ZIP 分别生成在：

```text
release/member-admin
release/registration-kiosk
```

打包后执行：

```shell
pnpm portable:verify
pnpm portable:smoke
```

## 会员管理端运行数据

默认端口为 `8090`。端口可在“设置”中的 Windows 本机服务卡片修改，修改会先校验端口，再重启本应用拥有的后端进程；不会随机改用其他端口。

SQLite、配置和日志都保存在 Electron 为会员管理端分配的 userData 中，而不写入只读安装目录。实际绝对路径会显示在诊断卡片中。典型结构为：

```text
<userData>/member-admin.json
<userData>/data/platform.db
<userData>/logs/server.log
```

若后端启动失败，UI 仍会打开。诊断卡片会显示状态、错误、当前端口、数据库路径、日志路径和可供其他终端填写的局域网 IPv4 地址。排查顺序：确认 JRE/JAR 存在、端口未占用、日志无数据库权限错误，再点击“重试启动”。

关闭会员管理端时，只会停止本次应用启动的 Java 进程树，不会按进程名批量结束系统中的其他 Java。

## 操作账号与数据保留

会员管理端启动后先显示登录页。新数据库的默认出厂管理员为：

```text
账号：admin
密码：888888
```

正式制作门店包时，可在后端首次初始化数据库前通过 `PLATFORM_FACTORY_ADMIN_USERNAME`、`PLATFORM_FACTORY_ADMIN_PASSWORD` 和 `PLATFORM_FACTORY_ADMIN_DISPLAY_NAME` 覆盖出厂值。开发桌面版和便携版读取各自进程实际使用的 SQLite；账号一旦写入数据库，改变环境变量或重启不会覆盖它。

出厂管理员可在“设置 → 操作账号”中创建、编辑、重设密码和停用普通操作员。普通操作员保留会员办理、手环充值/回收、房间查看、排行榜和记录等日常功能，但界面不显示会员删除、主动清空余额、房间改名、系统设置和账号管理入口。

登录账号只保存在当前 Electron renderer 的内存中，不写入 localStorage、Cookie 或桌面配置。退出、刷新 renderer 或重启应用后都要重新登录。应用没有“忘记密码”入口；出厂管理员密码遗失时需要联系厂家处理。

普通覆盖安装、替换程序目录或升级版本会继续使用 userData 下原有的 `data/platform.db`，因此不会恢复默认账号，也不会清除会员数据。若厂家执行真正的恢复出厂并删除或替换该 SQLite，会员、手环、房间名称、账号和操作日志都会一并丢失；执行前必须明确备份和数据影响。

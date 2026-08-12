# 门店核心流程验收测试

本测试会在本机回环网络中自动启动一套可随时销毁、但结构接近真实门店的运行环境，包括会员管理后端、会员管理端、自助注册端、游戏后端、游戏前端和 Electron 游戏桌面端。测试会走真实的页面操作、键盘式读卡器输入路径、IPC、HTTP、房间 WebSocket 长连接、SQLite 数据库以及游戏端 H2 数据库。

## 环境要求

- Windows 系统，并已在 `PATH` 中配置可用的 JDK 和 Maven。
- 已安装 Node.js 和 `pnpm`。
- `ledGame-platform`、`ledGame`、`ledGame-backend` 三个项目均已安装依赖。
- 三个项目必须位于同一父目录下，目录名分别为 `ledGame-platform`、`ledGame` 和 `ledGame-backend`。
- 默认使用 Microsoft Edge。若要使用其他已经安装的 Playwright Chromium 浏览器通道，可设置 `ACCEPTANCE_BROWSER_CHANNEL`。
- 不需要连接 USB 读卡器、游戏控制器或 LED 地砖。

## 运行命令

建议先运行耗时较短的验收基础测试：

```shell
pnpm test:acceptance:unit
```

运行全部门店验收场景：

```shell
pnpm test:acceptance
```

当前共有四个场景，在开发电脑上完整运行通常需要约 4 分钟。开发过程中如果只想运行黄金流程，可以执行：

```shell
pnpm exec playwright test --config acceptance/playwright.config.ts --grep "门店黄金流程"
```

如需观察浏览器端的操作过程，可设置：

```shell
ACCEPTANCE_HEADED=1 pnpm test:acceptance
```

仅在排查故障时设置 `ACCEPTANCE_KEEP_RUNTIME=1`。启用后，测试会保留带所有权标记的临时运行目录，并将其路径附加到测试报告中。

## 测试数据与正式数据的关系

每个测试场景都会创建一个独立且带所有权标记的目录，位置在 `acceptance/.runtime`。测试会自动分配本机端口，并在该目录中创建：

- 会员管理后端使用的临时 SQLite 数据库；
- 游戏后端使用的临时 H2 数据库；
- Electron 游戏端专用的临时设置目录；
- 本次测试各进程的日志文件。

自助注册端没有自己的数据库，它通过 HTTP 请求本次测试启动的会员管理后端。测试不会读取或改写门店的正式数据库和操作员配置。正常结束后，测试只会停止自己启动的进程树，并删除本次测试的临时运行目录。

## 测试报告与故障诊断

中文摘要报告位于：

```text
acceptance/artifacts/测试报告.md
```

可交互 HTML 测试报告位于：

```text
acceptance/artifacts/report
```

截图、录像、Playwright 执行轨迹（trace）和失败附件位于：

```text
acceptance/artifacts/test-results
```

失败附件包含本次测试注入的端口和配置摘要，以及每个受管进程的有限长度日志末尾。测试场景名、业务步骤名和诊断附件名均使用中文；Playwright 报告查看器自身的固定按钮和状态文字由框架提供，可能仍显示英文。

## 仍需现场确认的内容

本测试通过代表核心软件边界可以正确协作，但不能替代以下现场验收：

- USB 读卡器能否被 Windows 正确识别、焦点是否正确，以及实体设备是否输出“数字 UID + 回车”；
- ELC-408 或其他控制器连接、LED 地砖输出、音响及其他外设；
- 路由器网段、DHCP 或静态 IP、Windows 防火墙以及多台实体机器之间的局域网通信；
- 多台游戏机并发运行和门店负载下的性能；
- 安装程序、开机自启、升级、备份恢复以及意外断电后的恢复；
- 非核心页面、所有游戏类型、视觉布局以及尚未完成的翻译和演示文本清理。

这些项目属于交付部署和现场验收范围，不应被误认为已经由本地软件集成测试覆盖。

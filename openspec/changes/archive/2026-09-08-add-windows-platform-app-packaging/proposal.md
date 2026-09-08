## Why

会员管理端和自助注册端目前只能通过 Vite 开发服务器或静态构建产物运行，缺少可交付的 Windows 应用、运行时连接配置和一致的桌面窗口生命周期。核心流程已经跑通，现在需要把会员管理电脑与自助注册机变成无需修改源码或重新编译即可安装、配置、启动和排错的门店应用。

## What Changes

- 为会员管理端增加 Windows Electron 打包能力，安装包或便携包携带会员管理 UI、Spring Boot 后端、精简 JRE，并在应用管理的数据目录中运行 SQLite、配置和日志。
- 为会员管理后端增加运行前可配置的监听端口，默认使用 `8090`；修改端口必须经过校验并重启后端，不支持运行中热切换，也不允许用户配置本机监听 IP。
- 会员管理应用展示本机可供局域网终端连接的 IPv4 地址、实际端口、后端状态和日志位置，并在端口冲突或后端启动失败时给出明确错误。
- 为自助注册端增加 Windows Electron 打包能力和运行时会员管理端 `host + port` 配置，替代交付环境对编译期 `VITE_PLATFORM_BASE_URL` 的依赖。
- 自助注册端采用正式的双窗口结构：店员使用运维启动页配置、测试连接并启动顾客窗口；顾客流程在独立全屏窗口运行，不能进入连接配置。
- 开发模式、自动化测试和打包模式共用同一个 Electron 主进程、preload、配置存储和双窗口生命周期，仅页面资源地址不同；浏览器预览只作为辅助开发入口。
- 自助注册端断线时在顾客窗口显示不暴露网络配置的服务不可用提示并自动重连；受保护地退出顾客模式后恢复运维窗口。
- 增加两个应用的目录包、便携 ZIP 构建脚本，以及开发模式、运行时配置、双窗口、后端拉起和打包产物的自动化验证。

## Capabilities

### New Capabilities

- `windows-platform-app-packaging`: 会员管理端和自助注册端的 Windows 桌面打包、内置运行资源、数据目录、构建产物与启动诊断。
- `platform-server-runtime-configuration`: 会员管理后端监听端口、端口冲突检查、重启生效、局域网连接地址展示及配置持久化。
- `registration-kiosk-desktop-shell`: 自助注册端在开发与发布环境一致的运维/顾客双窗口、运行时服务器配置、连接测试、断线恢复和受保护退出。

### Modified Capabilities

<!-- 本 Change 不修改现有核心业务能力的需求。 -->

## Impact

- `ledGame-platform` 根级构建脚本、工作区依赖与 Windows 打包资源。
- `apps/member-admin` 和 `apps/registration-kiosk` 的桌面入口、运行时配置读取与页面启动方式。
- `server` 的端口注入、SQLite/日志路径、健康检查和可执行 JAR 打包。
- 新增 Electron/electron-builder 相关依赖、主进程、preload、窗口生命周期测试和打包冒烟测试。
- 交付产物新增会员管理端与自助注册端 Windows 目录包和便携 ZIP；现有开发用 `VITE_PLATFORM_BASE_URL` 仍可保留给纯浏览器预览与自动化夹具。

## 1. 建立 test-first 边界和桌面模块骨架

- [x] 1.1 记录会员管理端、自助注册端当前只能通过 Vite 运行、后端固定端口和客户端依赖编译期地址的基线证据
- [x] 1.2 为共享 API client 增加可插拔 desktop transport 的失败测试，覆盖浏览器 HTTP 回退与桌面 IPC 分流
- [x] 1.3 为会员管理桌面端增加失败契约测试，要求本地 UI、受管后端、默认/自定义端口和稳定数据目录
- [x] 1.4 为自助注册桌面端增加失败契约测试，要求开发模式也创建 operator/kiosk 两个真实 Electron 窗口
- [x] 1.5 为打包资源增加失败契约测试，要求两个 appId、两个产品输出目录以及 kiosk 包排除 JAR/JRE/SQLite
- [x] 1.6 增加 Electron、electron-builder、wait-on 等受控版本依赖和共享 `desktop` 目录结构，不改动现有游戏端仓库

## 2. 收敛运行时配置与桌面 API 传输

- [x] 2.1 实现共享的原子 JSON 配置存储、schema 默认值和损坏配置回退，并将配置限制在产品各自的 userData 目录
- [x] 2.2 实现共享的 host/port 校验、回环与局域网 URL 组装、有限超时健康检查和稳定连接错误分类
- [x] 2.3 为 preload/主进程实现受控 API transport，只接受相对 `/api` 路径、白名单 HTTP 方法、有限 JSON body 和有限响应
- [x] 2.4 按窗口类型建立最小 IPC 能力表，阻止 renderer 访问任意 URL、文件、命令和不属于其角色的配置接口
- [x] 2.5 扩展 `@ledgame/platform-api-client` 支持可插拔 transport，同时保持 `VITE_PLATFORM_BASE_URL` 的纯浏览器开发/验收回退
- [x] 2.6 将会员管理端各页面的零散直接 `fetch` 收敛到共享 API client/transport，禁止桌面模式绕过运行时目标
- [x] 2.7 将自助注册端注册、查询、刷卡和绑定请求收敛到共享 API client/transport
- [x] 2.8 运行 API client、非法 IPC、超时、响应大小、浏览器回退和现有客户端业务测试，确认 1.2 转绿

## 3. 实现会员管理端 Windows 桌面运行时

- [x] 3.1 将会员管理后端端口改为显式运行参数可覆盖且默认 `8090`，并为 SQLite 与日志绝对路径增加启动配置测试
- [x] 3.2 实现 member-admin Electron main/preload，使本地 UI 在后端尚未健康或启动失败时仍可打开
- [x] 3.3 实现开发 JAR/Java 与打包 JAR/内置 JRE 的资源解析，禁止依赖当前工作目录或系统 Java
- [x] 3.4 实现端口范围和可绑定性预检；端口占用时保留上次配置、显示稳定错误且不随机选择端口
- [x] 3.5 实现受管 Spring Boot 子进程、有限日志、健康等待、退出监测和仅清理本次进程树的关闭逻辑
- [x] 3.6 在会员管理设置/诊断 UI 中展示后端状态、实际端口、日志路径和非回环 IPv4 候选连接地址
- [x] 3.7 实现端口修改确认与后端重启事务：校验、停止、保存、重启、健康成功后恢复业务；失败时保留 SQLite 和可恢复诊断
- [x] 3.8 实现后端意外退出后的 UI 降级和人工重试启动，不通过进程名批量终止 Java
- [x] 3.9 增加不同工作目录、默认端口、自定义端口、占用端口、重启失败、网络地址变化和子进程清理测试，确认 1.3 转绿

## 4. 实现自助注册端开发/发布一致的双窗口结构

- [x] 4.1 实现 registration-kiosk Electron main/preload 和 `operator`/`kiosk` window kind，应用启动时只创建 operator
- [x] 4.2 新增本地运维启动页，提供 host、port、保存、测试连接、连接状态和“启动自助注册”按钮
- [x] 4.3 实现只有当次健康测试成功后才能启动 kiosk；测试失败时保留可编辑值和上一次合法持久化配置
- [x] 4.4 将现有顾客 App 放入独立 kiosk 窗口，成功启动后隐藏而不销毁 operator，并保证最多存在一个 kiosk
- [x] 4.5 为 operator 与 kiosk 分配不同 preload 能力，验证顾客窗口无法读取/保存配置、测试任意目标或启动窗口
- [x] 4.6 将 kiosk 设置为全屏 kiosk 并拦截普通关闭，实现由主进程识别的店员退出动作，退出后恢复 operator
- [x] 4.7 保证顾客流程的“返回首页”只回到顾客待机页，不切换或泄露运维启动页
- [x] 4.8 实现运行中有限间隔健康重试、脱敏离线提示、业务提交禁用和恢复后的安全待机/重试状态
- [x] 4.9 增加正式开发命令，使用与发布相同的 main/preload/配置和窗口生命周期，仅把 renderer 地址切到 Vite
- [x] 4.10 保留明确命名的纯浏览器预览辅助命令，但不将其作为桌面窗口或发布行为的验收入口
- [x] 4.11 用 Playwright Electron 测试开发模式的首次启动、成功/失败连接、双窗口、权限隔离、普通关闭、店员退出、断线和重连，确认 1.4 转绿

## 5. 生成两个独立 Windows 便携产品

- [x] 5.1 调整两个 Vite renderer 的发布资源路径，验证从 Electron 本地文件加载时路由、字体、国旗和静态资源正常
- [x] 5.2 新增会员管理打包准备脚本，构建 Spring Boot JAR并用 jlink 生成经过验证的最小 JRE
- [x] 5.3 新增 member-admin electron-builder 配置，使用独立 appId/productName/output 并只携带自身 UI、桌面入口、JAR 和 JRE
- [x] 5.4 新增 registration-kiosk electron-builder 配置，使用独立 appId/productName/output 且不携带 JAR、JRE、SQLite 或会员数据
- [x] 5.5 在根 `package.json` 增加两个产品各自的开发、目录包、ZIP 命令和一次构建全部产品的聚合命令
- [x] 5.6 实现打包后目录包冒烟 harness，使用测试拥有的 userData 和动态端口启动真实 exe、等待本地首页后安全退出
- [x] 5.7 增加包内容、appId、产品名、只读安装目录和可变数据外置检查，确认 1.5 转绿

## 6. 跨端验收、文档与完成检查

- [x] 6.1 扩展验收 harness，使桌面模式的 member-admin 可使用动态端口/隔离 SQLite 并由现有核心流程复用
- [x] 6.2 新增自助注册 Electron 双窗口验收：operator 配置测试、启动 kiosk、完成注册绑定、退出后恢复 operator
- [x] 6.3 新增会员管理端端口变更验收，确认本机 UI 切换到新回环端口、旧端口释放且其他终端需更新目标
- [x] 6.4 运行会员管理后端完整 Maven、平台客户端测试、类型检查和构建，保持现有核心业务回归绿色
- [x] 6.5 运行开发模式 Electron 双窗口测试和现有门店核心流程验收，确认浏览器夹具与桌面 transport 均有效
- [x] 6.6 构建两个 Windows `dir` 与 ZIP，并对全新测试 userData 连续执行至少两次目录包启动冒烟，确认无固定端口和进程残留
- [x] 6.7 编写中文开发与打包说明，列出构建依赖、命令、产物、默认端口、数据/日志位置和失败诊断方法
- [x] 6.8 编写中文门店配置说明，说明如何从会员管理端读取局域网地址、配置自助注册端和游戏端、检查防火墙并完成跨机冒烟
- [x] 6.9 检查安装资源不含正式 SQLite、配置不写安装目录、kiosk 包不含后端、Electron 未关闭 `webSecurity` 且未放宽匿名任意 IPC
- [x] 6.10 检查 OpenSpec 勾选、三个完成 Change 的兼容性和本 Change 最终测试报告，确认所有任务完成后再准备归档

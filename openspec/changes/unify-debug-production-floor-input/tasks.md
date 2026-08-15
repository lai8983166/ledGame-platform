## 1. 建立失败测试与当前边界证据

- [x] 1.1 在 `ledGame-backend` 增加规范化地砖事件测试，先证明当前规则层仍直接依赖 `GameInput(click/tile)` 而失败
- [x] 1.2 在 `ledGame-backend` 增加同一游戏夹具的 Debug 与 ELC/TCP 输入一致性测试，断言 gameplay snapshot、LevelOutcome、终止原因和 raw score 相同，并记录首次失败边界
- [x] 1.3 在 `ledGame` 增加 Debug 画布和确定性自然完成动作必须依次发送 `DOWN/UP` 的失败测试
- [x] 1.4 在 `ledGame-platform` 增加 production-shaped acceptance 场景骨架，要求 `PRODUCTION` 模式、有效 LED 帧、0x68 `DOWN/UP` 和自然结算，并记录首次失败边界
- [x] 1.5 更新 test-first 记录，区分“共享规则失败”“输入适配失败”“生产形态编排尚未实现”三类边界

## 2. 游戏后端统一地砖领域输入

- [x] 2.1 新增来源无关的 `FloorInputEvent` 与 `DOWN/UP/RESET` 动作模型，集中校验坐标和动作
- [x] 2.2 在 `GameRuntimeService` 建立 `GameInput` 到 `FloorInputEvent` 的唯一规范化入口，并为非法载荷返回稳定错误且不改变 gameplay state
- [x] 2.3 将 `GameHandler` 的游戏输入边界迁移为统一地砖事件，同时保留非游戏 Demo Engine 所需的独立输入能力
- [x] 2.4 将 `SimpleGameHandler` 改为只处理统一地砖事件，删除规则层的 Debug `click` 分支
- [x] 2.5 保持快速 `DOWN -> UP`、持续按住、同物体去重、跨帧移动物体和 `RESET` 清理语义
- [x] 2.6 在传输规范化层将遗留 `click` 暂时转换为同坐标的 `DOWN -> UP`，并增加兼容测试证明不会形成第二套得分逻辑
- [x] 2.7 调整运行时停止、输入连接断开和会话替换路径，确保统一触发 `RESET`
- [x] 2.8 运行新增规范化、非法输入和 Simple Game 规则测试，确认 1.1 与 1.2 的规则层失败转绿

## 3. 对齐真实控制器与 Debug 输入适配器

- [x] 3.1 将 `TcpLedOutputManager` 的 0x68 回传映射到统一 `DOWN/UP` 事件，并保持断线时的 `RESET`
- [x] 3.2 补充 ELC-408 状态机到逻辑坐标事件的契约测试，覆盖防抖、快速按下抬起、无效状态和多控制器坐标
- [x] 3.3 补充 `TcpLedClient` 分帧、顺序、重连和断线重置测试，证明字节流不会产生重复或乱序地砖事件
- [x] 3.4 在 `ledGame` 建立可复用的 Debug floor tap helper，严格等待 `DOWN` 完成后再发送 `UP`
- [x] 3.5 将 Debug 画布正常点击和 `game-debug-complete-natural` 迁移到 floor tap helper，不再发送 one-shot `click`
- [x] 3.6 保持灯光输入 Demo 的 `set/release` 与游戏地砖输入分离，避免颜色编辑行为误入游戏规则
- [x] 3.7 确认强制关卡结果、重试、跳关和 `End Game` 仍只走 `SIMULATION` debug-command，且自然完成测试不调用这些指令
- [x] 3.8 运行游戏前端、Electron IPC、输入适配器和后端规则聚焦测试，确认 1.2 与 1.3 全部转绿

## 4. 建立无硬件的 PRODUCTION 就绪与双向 TCP 替身

- [x] 4.1 从 `GameLaunchService` 抽取窄的游戏硬件就绪端口，生产实现继续委托 `Elc408RuntimeManager.prepareForGame`
- [x] 4.2 增加生产配置测试，证明正常 `PRODUCTION` 模式仍在缺少控制器时 fail-closed 并保留准备会话
- [x] 4.3 增加只在 acceptance profile 且显式启用时注册的就绪替身，禁止普通生产配置选择跳过硬件检查
- [x] 4.4 在平台验收 harness 中实现 run-owned 双向 TCP 地砖替身，支持接收并校验 LED 帧及回写生产格式 0x68 `DOWN/UP`
- [x] 4.5 为 TCP 替身分配动态端口、限制日志与事件数量，并纳入现有按运行目录管理的启动和清理流程
- [x] 4.6 使 acceptance profile 可将 `elc408-sdk` 输出指向动态 TCP 替身并开启 `input-enabled`，同时保持嵌入式真实 ELC 运行时关闭
- [x] 4.7 增加测试证明替身至少收到一个尺寸匹配的 LED 帧后才发送输入，错误帧或连接失败会产生可定位的验收错误

## 5. 收紧跨端大型验收

- [x] 5.1 将现有 Debug 黄金流程改为通过显式 `DOWN/UP` 命中确定性目标，并继续断言自然成功、raw score、平台积分、排名和队列切换
- [x] 5.2 为 `StoreAcceptanceHarness` 增加测试拥有的运行选项，使 Debug 黄金流程使用 `SIMULATION`，生产形态场景使用 `PRODUCTION`，且不修改操作员配置
- [x] 5.3 新增 production-shaped 场景：通过真实游戏配置 UI 启动、通过 TCP 替身接收帧并回写地砖输入、自然完成并验证结算
- [x] 5.4 在 production-shaped 场景中断言未调用强制成功或 `End Game`，并核对游戏后端与平台记录的终止类型为自然完成
- [x] 5.5 保留主动 `End Game` 的独立负面场景，继续断言中止、零积分且不冒充正常结算
- [x] 5.6 为输入链路失败收集有限的 Debug 请求、规范化事件、TCP 帧元数据、运行模式和后端日志，不记录会员敏感数据或完整 RGB 内容
- [x] 5.7 更新中文测试报告器，使其分别报告“Debug 规范化输入”“PRODUCTION 软件形态”“未覆盖的现场硬件项目”

## 6. 文档、回归与完成检查

- [x] 6.1 在中文验收使用说明中解释统一输入链路、两个大型场景的启动命令、预期耗时和失败产物位置
- [x] 6.2 增加简短现场冒烟清单，覆盖控制器发现、地砖坐标、按下/抬起、LED 输出、断线重连，并明确它不属于自动化通过结果
- [x] 6.3 运行 `ledGame-backend` 的规范化输入、Simple Game、ELC-408/TCP、生产就绪、生命周期和结算聚焦测试
- [x] 6.4 运行 `ledGame-backend` 完整 Maven 测试，并确认不存在依赖真实控制器或固定端口的测试
- [x] 6.5 运行 `ledGame` 的前端、Electron 和游戏流程完整测试
- [x] 6.6 运行 `ledGame-platform` 的 `pnpm test:core` 与 `pnpm test:e2e`
- [x] 6.7 连续运行至少两次 `pnpm test:acceptance`，确认 Debug 与 production-shaped 场景均可重复、无端口残留且使用隔离存储
- [x] 6.8 检查三个仓库的改动范围、OpenSpec 勾选状态和中文报告，确认没有把测试替身暴露到生产配置后再准备归档

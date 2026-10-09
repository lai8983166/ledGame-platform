# Proposal

## Why

新版保存外围灯布局后缺少旧版的圆灯倒计时玩法和调试输入，无法在没有灯具时验证这部分业务。补齐 Simple、Normal、Diffcult 共用玩法与调试页面，保持普通地砖及会员积分逻辑不变。

## What Changes

- 按保存且启用的符号灯布局，在编辑器调试页 RGB 四周显示配对的方形符号灯、圆形灯。
- 每关可启用倒计时并设置最小/最大秒数，复用已有 `pixelLightType/countdownMin/countdownMax`；默认不启用，不改旧游戏配置。
- 各圆灯独立倒计时；按蓝灯重置、短暂变绿但不加分；超时变黄并按现有生命限制扣生命，约两秒后恢复。
- 后端处理模拟点击，前端只显示状态；暂停不计时、换关/重试重建、结算和退出清理，错误会话或非调试输入不能越权操作。
- 配套纯逻辑、接口、真实组件和打包版隔离测试，并重新打包游戏端。
- 已找到旧 UDP 外围输出复用代码，但真实圆灯输入及新 SDK 的外围编码/映射尚未完整验证；本次不接入物理外围通信，不修改地砖 RGB 字节流。

## Capabilities

### New Capabilities

- `circle-light-countdown`: 共用玩法的圆灯倒计时、生命业务、状态显示、调试点击与生命周期。

### Modified Capabilities

无。

## Impact

统一规划在本仓库；实际修改 `F:/project/ledGame` 和 `F:/project/ledGame-backend` 的共用编辑器、单页调试、Simple 运行时、既有 debug command 与状态快照。复用布局和字段，无数据库迁移、不改 Rank、会员管理端或自助注册端，不新增通用硬件框架。

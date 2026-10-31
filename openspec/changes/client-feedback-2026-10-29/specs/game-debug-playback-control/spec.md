## ADDED Requirements

### Requirement: 共用玩法恢复旧版公共开场倒计时

Simple、Normal和Diffcult共用default玩法 SHALL 在每关启动时先播放公共321动画，再播放该关原有startGIF，完成后才推进玩法；不以公共倒计时替换关卡startGIF。动画 SHALL 根据场地宽高读取gif/321_宽_高.gif，缺失时按旧版生成中央黄色3、2、1点阵，每帧1秒。已有整局gameStartAudio说明 SHALL 继续先等待播放完成。ReadyGo.mp3 SHALL 在关卡索引0时于321开始前非阻塞发起并与动画并行，首关重试同样执行，非首关直启或换关不播放该语音。该公共流程 SHALL 不新增配置项或改写迁移数据，不影响Rank及Touch自身准备倒数。

#### Scenario: First level and later levels
- **WHEN** 从首关、非首关开始，或换关、重试
- **THEN** 每次均依次显示321、该关startGIF及玩法，只有实际首关触发Ready Go语音，原开场资源引用保持

#### Scenario: Missing dimension-specific countdown
- **WHEN** 共用玩法没有相应场地尺寸的321文件
- **THEN** 按旧版数字样式和1秒帧间隔生成对应尺寸动画，不修改游戏配置及日常数据库

#### Scenario: Pause, stop and stale playback
- **WHEN** 开场时暂停、恢复、停止、重开或切关
- **THEN** 媒体沿用本局owner及暂停时钟，停止清理，旧回调不得开始新关或覆盖新局画面；故障保持可诊断并遵循原媒体失败处理

### Requirement: 媒体GIF待机及调试显示

系统待机及准备阶段 SHALL 循环播放媒体库`彩虹效果16x36.gif`，通过已有媒体目录解析和GIF帧机制输出16×36实时帧，替代程序生成彩虹；进入正式玩法后停止待机循环，取消准备回待机时恢复。无效媒体不得悄悄用生成动画替代。

调试界面 SHALL 在系统IDLE及本页准备阶段显示同一实时待机帧。停止状态进入调试时 SHALL 仅在引擎空闲时初始化待机，没有设备仍能软件预览；不得接管其他会话。运行时 SHALL 切回已保存游戏尺寸的实时画面，不把错误尺寸待机帧当作游戏输出。

#### Scenario: 待机和准备循环GIF
- **WHEN** 系统处于IDLE或本页准备选择调试配置/刷卡
- **THEN** 后端循环真实媒体GIF并推送帧，调试矩阵显示持续变化的16×36画面，不影响配置和准备操作

#### Scenario: 无设备进入调试及开始游戏
- **WHEN** 引擎停止且无设备时进入调试，然后正式启动不同尺寸游戏
- **THEN** 安全初始化待机并显示动画，游戏启动后按游戏尺寸显示且不再接收待机画面，其他会话仍受原隔离保护

#### Scenario: 取消或停止输出
- **WHEN** 取消准备恢复待机，或停止/切换输出
- **THEN** 恢复或清理对应GIF循环，旧循环迟到tick不影响新阶段，监听及执行器按原生命周期释放

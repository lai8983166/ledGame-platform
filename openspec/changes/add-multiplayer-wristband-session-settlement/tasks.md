## 1. 先建立失败测试

- [x] 1.1 为真实 SQLite schema 与 `PlatformSchemaMigration` 增加测试，证明同一 external session 可保存多个 participant 且历史单人数据迁移后可读
- [x] 1.2 为 batch start service/API 增加测试，覆盖 N 条原子创建、任一无资格时零写入、重复 UID/binding/member 和并发占用
- [x] 1.3 为 batch 幂等增加测试，覆盖同一有序参与者集返回原记录以及参与者、顺序冲突时稳定拒绝
- [x] 1.4 为多人逐 play 结算增加测试，覆盖相同 raw score/points、重复回调不重复加分以及单人接口兼容

## 2. 迁移多人 play 存储结构

- [x] 2.1 在新建 schema 中为 `game_play_records` 增加 `participant_index`，并建立 session+binding 与 session+index 唯一约束
- [x] 2.2 实现现有 SQLite migration：增加并回填 `participant_index=0`、移除旧 `(device_id, external_session_id)` 唯一索引并创建新索引
- [x] 2.3 验证 migration 可重复执行且不会改写既有会员、积分、result 或历史 play 数据

## 3. 实现原子 batch start

- [x] 3.1 新增 `POST /api/game-plays/start-batch` DTO、controller 和有序响应，明确 device、room、externalSessionId、game 与 UID 列表契约
- [x] 3.2 在单一事务中解析并验证所有 wristband、binding、member、余额窗口和 RUNNING 占用，再一次性插入所有 participant play
- [x] 3.3 校验同一请求内 UID、binding、member 均唯一，任一失败时回滚整批并返回稳定业务错误
- [x] 3.4 以完整有序参与者集合实现幂等重试；同 session 的集合或顺序不一致时保持既有记录不变并报告冲突
- [x] 3.5 让现有单人 start endpoint 复用一项 batch 核心逻辑，保持既有请求和响应兼容

## 4. 保持逐会员结算与查询正确

- [x] 4.1 验证现有 result endpoint 能按每个 playId 独立保存终态、raw score、payload、points 与 end time
- [x] 4.2 验证同一共享结果应用到 N 条 play 时每位会员各获得一次相同积分，重复回调不重复累计
- [x] 4.3 验证 Player Info、会员积分、积分排名和游戏记录查询自然包含各会员自己的多人 play，不重复显示其他参与者记录
- [x] 4.4 验证手动停止、startup abort 和 runtime failure 会逐条关闭 play，并遵循现有非自然完成计分策略

## 5. 更新跨端核心验收

- [x] 5.1 扩展测试数据构造，创建显然属于测试用途的多位会员、互异手环和隔离 SQLite 数据库
- [x] 5.2 增加“两人只刷一张卡不能启动且零 play”跨端场景
- [x] 5.3 增加“两人自然完成后产生两条同 session、同 raw score、同 points 记录”跨端场景，并核对双方 Player Info
- [x] 5.4 参数化验收 `simple`、`normal`、`diffcult`，确认共享玩法状态、逐会员结算以及 Debug Panel/副屏契约不变
- [x] 5.5 保留并运行单人自然完成与手动 End Game 场景，确认两种终止路径和旧核心流程无回归

## 6. 完整验证与中文报告

- [x] 6.1 运行平台 server 聚焦测试、完整测试和 `pnpm test:core`，修复多人数据库与接口回归
- [x] 6.2 运行三仓库大型验收并生成中文测试报告，记录用例、结果、SQLite 数据证据和失败排查方式
- [x] 6.3 更新中文使用说明，说明多人测试启动命令、测试数据隔离方式及如何核对每位会员的记录和积分

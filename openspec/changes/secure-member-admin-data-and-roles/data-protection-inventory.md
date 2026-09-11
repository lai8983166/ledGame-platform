# 本机数据保护路径清单

| 表/文件 | 敏感内容 | 写入入口 | 查询/读取入口 | 保护方式 |
| --- | --- | --- | --- | --- |
| `members` | phone、name、avatar_id、birthday、gender | 管理端/自助端注册 | 会员、玩家信息、排行榜、导出 | AES-GCM；phone 使用 HMAC 索引 |
| `wristbands` | card_uid | 柜台充值 | 充值、绑定、准入、玩家信息 | AES-GCM；UID 使用 HMAC 索引 |
| `wristband_charge_records` | wristband_uid | 充值 | 交易记录、运营统计、导出 | UID AES-GCM；金额/分钟保持聚合字段 |
| `wristband_bindings` | 无独立外部身份字段 | 绑定/激活/结束 | 手环状态、发卡记录 | 仅内部 ID、状态和时间 |
| `game_play_records` | wristband_uid、result_json | 游戏开始/结算 | 游玩记录、玩家信息、导出 | AES-GCM；积分/状态保持聚合字段 |
| `room_settings` | 无本 Change 敏感字段 | 房间重命名 | 房间列表 | 不加密 |
| `store_feature_settings` | 无本 Change 敏感字段 | 功能开关 | 管理端/游戏端 | 不加密 |
| `operator_accounts` | password_hash | 账号创建/改密 | 登录 | BCrypt 单向哈希；用户名/显示名保留登录定位 |
| `operator_action_logs` | 账号快照、target_id、summary_json | 管理操作审计 | 授权审计读取 | AES-GCM；动作、内部 ID、时间可筛选 |
| `database_state` | 无本 Change 敏感字段 | 修订触发器/导入 | 备份核对 | 不加密 |
| SQLite/WAL/SHM | 上述数据库页 | SQLite | 离线文件检查 | 业务写入前加密，禁止先落明文 |
| 异盘 `latest/history` | 数据库及密钥信封 | 在线备份 | 同 Windows 用户恢复 | 密文数据库 + DPAPI 信封 + keyId 元数据 |
| `server.log` | 请求错误和诊断 | 后端日志 | 维护排障 | 不记录请求体、CSV 正文、密钥和完整身份值 |

自动化明文扫描使用唯一测试手机号、姓名和手环 UID 同时扫描主库、`-wal`、`-shm`、备份与日志；夹具先证明扫描器能检出人为写入的明文，再验证真实业务写入后全部不可检出。

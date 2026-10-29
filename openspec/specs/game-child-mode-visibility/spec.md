# game-child-mode-visibility Specification

## Purpose

保留既有逐游戏可见性字段的兼容契约，并明确其现行含义为游戏端独立的触屏显示配置。玩家目录及即时更新规则由 game-touch-catalog-visibility 定义，旧门店儿童模式联动及隐藏游戏启动拦截已被后续规则取代。

## Requirements

### Requirement: 兼容既有逐游戏可见性字段

系统 SHALL 继续读写既有 childModeVisible 字段及数据库列，不以游戏名称判定玩家目录可见性；true、null 或缺省 SHALL 视为显示，false SHALL 视为隐藏，不批量重写历史数据。

#### Scenario: 读取和修改历史游戏
- **WHEN** 读取缺少可见性字段的旧游戏，或修改已隐藏游戏的名称
- **THEN** 缺省游戏保持显示，改名的隐藏游戏仍按字段值隐藏，其他配置不变

### Requirement: 门店儿童模式不控制新游戏端目录或调试权限

游戏端 SHALL 将该字段解释为独立触屏显示配置，不受会员管理端 childMode 状态控制，也不据此拒绝管理端指定游戏调试。会员管理端的原总开关和兼容旧客户端的通信协议 SHALL 保留。

#### Scenario: 门店状态变化和指定调试
- **WHEN** 门店儿童模式开启、关闭或重连同步，或操作员调试一个触屏隐藏的游戏
- **THEN** 玩家目录继续只按逐游戏显示配置过滤；指定调试不因显示标记被拒绝，仍执行原有玩法、会话和硬件校验

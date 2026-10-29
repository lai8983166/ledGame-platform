# Proposal

## Why

娱乐挑战编辑器无法配置每关独立生命与整局共享生命，现有数据库字段的编辑链路不完整。

## What Changes

- Simple、Normal、Difficult 全局配置增加娱乐模式／闯关模式；闯关模式可设置整局生命值。
- 复用 global_life_limit 与 global_life_limit_value，补齐编辑文档读写。
- 娱乐模式仅使用每关生命限制；闯关模式仅使用整局生命限制，关卡配置保留以便切换回来。
- 测试保存、切换、跨关生命值和失败判定，不修改 Rank。

## Capabilities

### New Capabilities
- `simple-game-life-modes`: 娱乐挑战玩法的生命值范围选择及编辑、运行行为。

### Modified Capabilities
无。

## Impact

涉及 F:/project/ledGame 的共用全局配置与编辑器、F:/project/ledGame-backend 的编辑 DTO、映射、校验和生命值运行逻辑；不新增数据库列，不修改会员管理端。

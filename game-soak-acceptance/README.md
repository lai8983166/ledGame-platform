# 游戏端烤机工具

仅测试游戏端打包程序，不启动会员管理端或自助注册端。默认目标为 18 小时，崩溃后不会自动重启。

正式使用步骤见 [使用说明](使用说明.md)，真实覆盖和运行证据见 [实施记录](实施记录.md)。需要本 Change 构建后的游戏包，旧版缺少会话/通信证据接口时会明确失败。

在本仓库运行：

```sh
node game-soak-acceptance/src/cli.mjs list http://127.0.0.1:37680
node game-soak-acceptance/src/cli.mjs preflight http://127.0.0.1:37680 配置.json
node game-soak-acceptance/src/cli.mjs inspect 配置.json
node game-soak-acceptance/src/cli.mjs run 配置.json
pnpm test:game-soak
pnpm portable:game-soak
```

地址应填写待检查游戏端的实际后端地址。list/preflight 不启动游戏、不调用初始化 seed、不保存游戏配置。inspect 在隔离目录启动打包游戏端，仅查看列表；run 才进入 UI 循环。便携产物在 release/game-soak-tool-时间戳。

复制 `config.example.json` 并填写列表中的实际 `gameId`、`playerCount`、从 0 开始的 `startLevelIndex`。模板故意留空游戏列表，不提供虚构 ID。无限整局时间的游戏不能参加本次烤机；Rank 按其玩法配置时长判断。

预检通过不等于执行通过。已执行打包 UI 单玩法、四玩法循环及可选模拟 DOWN/UP 短测；真实硬件、实际 8×8 地砖和 18 小时稳定性尚未验收。tests 下的夹具制作器仅为开发验证复制并缩短游戏配置，不随工具交付，正式 run 不修改游戏内容。

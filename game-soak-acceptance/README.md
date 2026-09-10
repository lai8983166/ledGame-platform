# 游戏端烤机工具

仅测试游戏端打包程序，不启动会员管理端或自助注册端。默认目标为 18 小时，提供 5 分钟、30 分钟和 2 小时短测档位，崩溃后不会自动重启。

现场推荐直接运行便携目录中的 `烤机测试.cmd`。向导会读取实际游戏列表，让操作者多选参与测试的游戏，默认全部未选，并自动完成预检和运行。

选择真实地砖时，向导会自动读取游戏端 exe 同级 `elc408\conf.json` 和 `elc408\wiring.json`，不再要求手工输入配置目录或地砖宽高。

向导还会优先读取本机当前游戏数据库（游戏端关闭后），复制到本轮隔离目录；找不到时才使用打包包内的初始数据库。数据库被游戏端占用时会明确提示先关闭游戏端。

在本仓库运行：

```sh
node game-soak-acceptance/src/cli.mjs wizard
node game-soak-acceptance/src/cli.mjs list http://127.0.0.1:37680
node game-soak-acceptance/src/cli.mjs preflight http://127.0.0.1:37680 配置.json
node game-soak-acceptance/src/cli.mjs inspect 配置.json
node game-soak-acceptance/src/cli.mjs run 配置.json
pnpm test:game-soak
pnpm portable:game-soak
```

地址应填写待检查游戏端后端地址。list/preflight 不启动游戏；inspect 仅读取游戏列表；run 才进入 UI 循环。单独执行 `pnpm portable:game-soak` 时便携产物在 `release/game-soak-tool-时间戳`；统一执行 `pnpm portable:all` 时固定更新到 `release/game-soak`。

底层 JSON 入口仍需填写实际 `gameId`、`playerCount` 和从 0 开始的 `startLevelIndex`。向导会从实际游戏目录读取这些值，所有游戏默认未选；无限整局时间的游戏不能参加本次烤机，Rank 按其玩法配置时长判断。

预检通过不等于执行通过。真实硬件、实际场地和完整 18 小时稳定性仍需现场验收；正式 run 不修改游戏内容。

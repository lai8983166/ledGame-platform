# 游戏桌面端与会员管理端房间连接

房间身份使用游戏桌面端建立 WebSocket 连接时观察到的源 IP。会员管理端服务端监听 `/ws/rooms`，管理端页面通过 `GET /api/rooms` 读取在线、离线、运行态和排队数量。

## 配置

在会员管理端服务器设置：

```text
LED_ROOM_CONNECTION_ENABLED=true
LED_ROOM_CONNECTION_TOKEN=<门店内统一的房间连接令牌>
```

在每台游戏桌面端的 `ledGame-backend` 设置：

```text
MEMBER_PLATFORM_BASE_URL=http://<会员管理端IP>:8090
LEDGAME_DEVICE_ID=<设备唯一名称>
LEDGAME_ROOM_ID=<房间标识>
LEDGAME_ROOM_NAME=<展示名称，可选>
LED_ROOM_CONNECTION_TOKEN=<与会员管理端一致>
LED_ROOM_CONNECTION_ENABLED=true
LED_ROOM_RECONNECT_DELAY=5s
```

建议在路由器中为会员管理端和每台游戏桌面端配置 DHCP 静态租约。IP 不需要手工写入房间卡；连接建立后服务端会以实际源 IP 建立房间投影。当前 MVP 使用门店内共享令牌，后续可替换为按设备分发的凭据。

## 连接和事件

游戏桌面端启动后主动连接，发送 `HELLO` 和完整 `ROOM_SNAPSHOT`。游戏开始、排队变化、游戏结束时分别发送 `GAME_STARTED`、`QUEUE_CHANGED`、`GAME_ENDED`。短暂断线期间只保留最新事件；重连成功后再次发送完整快照。应用层不发送业务心跳，WebSocket ping/pong 用于保持连接和检测断线。

## 排查

1. 会员管理端页面显示 `OFFLINE`：先确认游戏端能访问 `<会员管理端IP>:8090`，再检查两端令牌是否一致。
2. 服务端没有房间卡：检查游戏端是否启用了 `LED_ROOM_CONNECTION_ENABLED`，以及桌面端日志中的 WebSocket 连接错误。
3. 房间出现重复连接：同一源 IP 只保留最新连接，旧连接会被关闭；确认没有重复启动同一桌面的 backend。
4. 页面能打开但请求失败：确认浏览器访问的是会员管理端地址，并检查 `/api/rooms` 是否返回 JSON；房间状态接口已允许跨域访问。
5. 断线不会自动结束游戏，也不会修改会员余额或游戏记录；恢复连接后以桌面端当前快照为准。

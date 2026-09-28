# 旧版游戏端出站 HTTP 接口清单

## 目的与范围

本文列出旧版游戏端后端主动发出的、与注册中心／会员管理端有关的 HTTP 请求，以及可选的游戏结果回调。内容依据旧版游戏端反编译源码整理；不依赖旧版会员管理端源码。

这里的“请求”包括两类：游戏端固定发出的注册中心请求，以及游戏端收到其他应用请求后代为转发的动态请求。地砖控制器、读卡器、房间内其他控制设备使用的 UDP、串口或 WebSocket 通信不属于本文范围。

## 通用连接信息

- 注册中心 IP 从本机数据库 `dict` 表读取，配置项为 `name=globalConfig, k=consoleIP`。
- 固定端口为 `16668`，固定回调为 `POST /dev/gameCallback`。
- 固定注册中心请求使用明文 HTTP；未设置认证头。
- JSON POST 使用 `Content-Type: application/json`。普通 POST 超时为 20 秒，会跟随重定向，并读取 HTTP 错误响应体；调用方通常只判断响应体，不依赖 HTTP 状态码。
- `cmd=5` 另外发送 `x-language` 请求头；心跳和 `cmd=2/3` 不发送该请求头。

## 固定注册中心接口

### 1. 心跳 `cmd=1`

**请求**

```http
POST http://{consoleIP}:16668/dev/gameCallback
Content-Type: application/json
```

```json
{"cmd":1}
```

- **触发条件：**配置了 `consoleIP`。应用启动约 3 秒后开始，每 20 秒发送一次；不检查本机 `hasTouch` 授权状态。
- **响应处理：**不解析响应内容，不检查业务 `code`。

### 2. 启动前批量校验 `cmd=5`

**请求**

```http
POST http://{consoleIP}:16668/dev/gameCallback
Content-Type: application/json
x-language: {当前界面语言}
```

```json
{
  "cmd": 5,
  "json": {
    "icList": ["2283055618", "2283055620"],
    "isAdmin": false
  }
}
```

- `json.icList`：本局手环／玩家标识字符串数组，顺序对应玩家顺序。
- `json.isAdmin`：本局是否以管理员身份启动。
- **触发条件：**旧游戏端具备 `hasTouch` 权限、`launchMethod=2` 且有 `consoleIP`。若卡号列表为空，或任意卡号以 `mock` 开头，旧游戏端跳过远端校验并在本机直接按成功处理。
- **调用方式：**同步、阻断式。旧游戏端只有在响应体的 `code` 为 `200` 时继续启动。

**响应解析约定**

旧游戏端读取 JSON 根对象的 `code`、`msg`、`data`。若 `data` 非空，会把它按“数组的数组”解析，并从每个内层数组的下标 `1` 读取 `Token` 对象；下标 `0` 在旧游戏端这段解析代码中未使用。

```json
{
  "code": 200,
  "msg": "success",
  "data": [
    ["<旧端忽略的槽位>", {
      "ic": "2283055618",
      "type": 2,
      "endTime": "<可被旧版 Fastjson 解析的日期>",
      "durationMinutes": 60
    }]
  ]
}
```

上例只表达旧游戏端要求的数组层级；日期编码格式和各令牌字段取值仍应以旧版 Fastjson 实际解析结果做兼容验证。

`Token` 类包含 `id`、`batchId`、`ic`、`main`、`enable`、`createTime`、`updateTime`、`type`、`times`、`remainingTimes`、`startTime`、`endTime`、`durationMinutes`。旧启动路径里明确使用：

- `type == 2` 且 `endTime` 非空时，若结束时间早于当前时间加 1 分钟，旧游戏端拒绝启动。
- 如果返回的令牌全部为 `type == 2`，旧游戏端会将游戏的全局时长限制到所有令牌中最短的剩余时长；`endTime` 为空时使用 `durationMinutes` 计算。

### 3. 游戏开始 `cmd=2`

**请求**

```http
POST http://{consoleIP}:16668/dev/gameCallback
Content-Type: application/json
```

```json
{
  "cmd": 2,
  "gameId": 386,
  "gameName": "Ranking",
  "json": {
    "icList": ["2283055618", "2283055620"],
    "isAdmin": false
  }
}
```

- `gameId`：旧游戏数据库中的游戏 ID，数值型。
- `gameName`：旧游戏名称。
- `json.icList`、`json.isAdmin`：含义同 `cmd=5`。
- **触发条件：**旧游戏引擎进入游戏启动流程时，要求 `hasTouch` 权限并配置 `consoleIP`；该回调本身不再检查 `launchMethod=2`。
- **调用方式：**异步通知。旧游戏端记录响应文本，但不解析业务码，也不因业务拒绝回滚游戏启动。

### 4. 游戏结束与积分 `cmd=3`

**请求**

```http
POST http://{consoleIP}:16668/dev/gameCallback
Content-Type: application/json
```

```json
{
  "cmd": 3,
  "gameId": 386,
  "json": {
    "points": [
      {"ic": "2283055618", "points": 120},
      {"ic": "2283055620", "points": 95}
    ],
    "icList": ["2283055618", "2283055620"],
    "isAdmin": false
  }
}
```

- `gameId`：旧游戏 ID。
- `json.points`：每位玩家的最终积分列表；每项包含 `ic`（玩家卡号／标识字符串）和 `points`（Java `long`）。积分由当前玩法的 `getPoints()` 计算，不是逐次实时积分。
- `json.icList`：本局玩家卡号列表。
- `json.isAdmin`：本局管理员启动标志。
- **触发条件：**`hasTouch` 权限、已配置 `consoleIP`，且全局 `launchMethod=2`。
- **调用方式：**异步通知。旧游戏端记录响应文本，不解析业务码、不重试、不补发。
- **结束原因限制：**旧报文没有自然结束／人工停止／运行错误字段。旧游戏端在多种停止路径都会调用 `onStop()` 并发送该消息，因此接收方不能只靠此报文准确判断结束原因。

## 注册中心连通性探测 `GET /dev/ping`

旧游戏端设置界面提供按指定 IP 测试注册中心的功能：

```http
GET http://{ip}:16668/dev/ping
```

- 不发送请求体或额外请求头。
- `ip` 来自本次测试操作输入，不要求已经保存为 `consoleIP`。
- 旧游戏端要求响应 JSON 的 `data` 字段为字符串 `pong`；否则显示连接失败。

## 动态转发接口 `/console/forward`

旧游戏端自身还提供 `POST /console/forward`，供调用它的界面／其他应用传入待转发请求。旧游戏端随后向注册中心发起真正的 GET 或 POST：

```json
{
  "method": "GET",
  "path": "/<注册中心路径>",
  "params": {"key": "value"},
  "data": {"key": "value"}
}
```

- 转发目标：`http://{consoleIP}:16668{path}`。
- 当 `method` 等于 `GET`（忽略大小写）时发 GET，并把 `params` 作为查询参数。
- 其他任何 `method` 值都会按 POST 发出；`data` 序列化为 JSON 请求体。该代码路径不会保留任意 HTTP 方法。
- 转发请求带 `x-language` 请求头；POST 带 `Content-Type: application/json`。
- 成功时，旧游戏端将响应体解析为 JSON 并返回给调用方；失败时返回旧游戏端的错误对象。
- 后端接受任意 `path`，实际使用的具体注册中心路径由调用方决定。仅凭旧游戏后端无法枚举这些动态路径。

## 可选外部游戏结果通知 `notifyURL`

旧游戏端的独立 `POST /api/startGame` 启动入口可接收 `notifyURL`。它不是固定的注册中心地址，也不使用 `consoleIP`。若提供该字段，游戏结束时旧游戏端会另起线程 POST：

```json
{
  "id": 386,
  "success": true,
  "results": [
    {"id": "2283055618", "points": 120},
    {"id": "2283055620", "points": 95}
  ]
}
```

- `id`：旧游戏 ID。
- `success`：旧游戏上下文报告的成功标志。
- `results[].id`：玩家 ID／卡号字符串；`results[].points`：该玩家最终积分。
- 旧游戏端记录响应文本，不校验业务响应、不重试。
- 此接口使用 `id/results`，与注册中心 `cmd=3` 的 `gameId/json.points/ic` 协议不同。

## 兼容实现时的边界

1. 固定回调协议可由新版会员管理端实现：`/dev/gameCallback` 接收 `cmd=1/5/2/3`，并另提供 `/dev/ping`。
2. `cmd=5` 必须返回旧游戏端可解析的业务 JSON 和令牌数组结构；仅返回 HTTP 200 不够。
3. `cmd=2/3` 没有旧客户端要求的业务响应字段，但兼容端仍应以成功 HTTP 响应结束请求，并保存可利用的数据。
4. `cmd=3` 没有唯一会话 ID。兼容端要用来源游戏机、活动游戏及玩家卡号等信息关联开始和结束请求，并定义重复／缺失回调的处理方式。
5. 动态 `/console/forward` 路径以及可选 `notifyURL` 只有在确实属于兼容范围时，才需要实现其目标服务；它们不能从固定 `cmd` 协议推导出完整路径集合。

## 旧版源码依据

- `service/impl/GameCallbackServiceImpl.java`：`cmd=5/2/3` 请求体、发送条件和积分生成。
- `config/ConsoleHeart.java`：心跳周期与 `cmd=1` 请求。
- `controller/ConfigController.java`：`/dev/ping` 探测。
- `controller/ConsoleController.java`、`domain/req/ConsoleForwardReq.java`：动态转发请求。
- `controller/GameController.java`、`endpoint/DefaultEndpoint.java`：`cmd=5` 响应解析与令牌时长处理。
- `run/GameEngine.java`：游戏结束、手动停止及异常停止时的回调路径。
- `domain/console/Token.java`、`domain/resp/PointsWithUser.java`：令牌和积分对象字段。
- `controller/APIController.java`、`domain/api/req/StartGameReq.java`：`notifyURL` 启动和通知。

# Platform Server

独立部署的本机平台 Spring Boot 服务，负责会员、手环、绑定、操作账号和操作留痕数据，并由多个终端通过 HTTP API 调用。

当前使用 SQLite 文件作为本机唯一数据源，默认文件为 `server/platform.db`。会员管理端负责给真实读卡 UID 充时，自助注册端负责先查询/创建会员，再刷卡绑定。

```bash
mvn spring-boot:run
```

可通过 `PLATFORM_DB_PATH` 环境变量指定数据库文件路径。前端 API 基地址为 `http://127.0.0.1:8090/api`。

新数据库首次启动时会创建出厂管理员。默认账号是 `admin`，默认密码是 `888888`；正式交付前可配置：

```text
PLATFORM_FACTORY_ADMIN_USERNAME
PLATFORM_FACTORY_ADMIN_PASSWORD
PLATFORM_FACTORY_ADMIN_DISPLAY_NAME
```

初始化使用 BCrypt 保存密码摘要，不写入明文。已有账号表不为空时，后续启动不会覆盖账号或重置密码。系统不提供密码找回接口；操作账号是可信局域网产品中的 UI 权限和审计机制，不作为互联网级后端鉴权边界。

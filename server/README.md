# Platform Server

独立部署的本机平台 Spring Boot 服务，负责会员、手环和绑定数据，并由两个前端通过 HTTP API 调用。

当前使用 SQLite 文件作为本机唯一数据源，默认文件为 `server/platform.db`。会员管理端负责给真实读卡 UID 充时，自助注册端负责先查询/创建会员，再刷卡绑定。

```bash
mvn spring-boot:run
```

可通过 `PLATFORM_DB_PATH` 环境变量指定数据库文件路径。前端 API 基地址为 `http://127.0.0.1:8090/api`。

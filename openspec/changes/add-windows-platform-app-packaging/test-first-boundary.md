# 测试先行边界

## 变更前基线（2026-08-15）

- 会员管理端和自助注册端只有 Vite `dev/build/preview` 命令，没有 Electron main/preload，也没有 Windows 产品打包配置。
- Spring Boot 的 `server.port` 在 `application.yml` 中固定为 `8090`。
- 两端通过 `VITE_PLATFORM_BASE_URL` 创建 API client，client 内部直接调用 renderer 的 `fetch`。
- 自助注册端没有 operator/customer 双窗口生命周期；开发模式也无法验证真实窗口权限边界。
- 仓库没有把会员管理端 JAR/JRE 与自助注册端轻量包分开的打包契约。

## 首轮红灯

先新增并运行以下测试，再实现业务代码：

- API client 可插拔 desktop transport；
- 会员管理端稳定 userData、JAR/JRE 路径；
- 自助注册端 operator/kiosk 生命周期和权限隔离；
- 两个产品独立 appId/output，且 kiosk 包排除 JAR/JRE/SQLite。

预期首次失败原因是相关 transport、desktop runtime 和 builder 配置尚不存在；实现后必须由同一组测试转绿。

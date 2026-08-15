# Acceptance test-first record

The first golden-path execution is intentionally expected to fail before any testability seam is added.

- Scenario: `store golden path: charge, bind, play, queue, promote, and inspect`
- First boundary: `ACCEPTANCE_STARTUP_NOT_IMPLEMENTED`
- Missing capability at that point: browser clients cannot receive a run-owned platform API endpoint, so an isolated multi-process run cannot safely start.
- Required next step: implement validated endpoint injection and isolated service configuration before replacing the startup stub.

## 统一 Debug 与生产地砖输入

本 Change 在修改业务实现前建立三条失败边界：

- 共享规则边界：`GameRuntimeInputDispatchTest` 要求 `FloorInputEvent` 和统一分发接口，当前因领域事件尚不存在而编译失败。
- 输入适配边界：`ledGame/tests/gameFlow.test.mjs` 要求 Debug 游戏输入按顺序发送 `tile DOWN` 与 `tile UP`，当前仍发送一次性 `click` 而断言失败。
- 生产形态编排边界：`acceptance/support/selectors.test.ts` 要求 run-owned 双向 TCP 地砖替身及 `PRODUCTION` 场景，当前对应 harness 和 spec 尚不存在而失败。

这些失败分别用于证明缺少领域输入契约、Debug 适配一致性和无硬件生产形态验收；后续实现不得用强制成功或 `End Game` 让自然完成测试转绿。

const text = (value) => String(value ?? '无').replaceAll('|', '\\|').replaceAll('\n', ' ');
const number = (value) => Number.isFinite(value) ? value.toFixed(2) : '未取得';

export function renderReport(config, result) {
  const memory = result.monitoring?.memory;
  const communication = result.monitoring?.communication;
  const incomplete = result.status === '未完成';
  const failed = result.status === '失败' || memory?.status === '失败' || communication?.status === '失败' || result.monitoring?.failure;
  const overall = failed ? '失败' : incomplete ? '未完成'
    : memory?.status === '通过' && communication?.status === '通过' && result.status === '完成' ? '通过' : '部分未验证';
  const hours = config.durationHours ?? 18;
  return `# 游戏端烤机报告

结果：${overall}

本次为${hours < 18 ? '短时测试，不代表 18 小时验收通过' : '长时间测试'}。仅包含游戏端及其自有后端，不包含会员管理端、自助注册端或会员积分结算。

## 软件可用性

- 本次循环结果：${text(result.status)}
- 配置目标：${number(hours)} 小时
- 实际循环时间：${number(result.elapsedMillis / 1000)} 秒
- 累计 RUNNING：${number(result.runningMillis / 1000)} 秒
- 自然收尾超出目标：${number(result.drainMillis / 1000)} 秒
- 已完成局数：${result.rounds ?? 0}
- 异常说明：${text(result.error || result.monitoring?.failure || '未记录到异常')}
- 不自动重启；主动中止不能计为完整通过。

| 游戏 ID | 完成局数 |
| --- | --- |
${Object.entries(result.counts ?? {}).map(([id, count]) => `| ${text(id)} | ${count} |`).join('\n')}

## 内存

- 判定：${memory?.status ?? '未判定'}
- 游戏端进程树私有内存峰值：${number(memory?.peakMB)} MB
- Java 私有内存峰值：${number(memory?.javaPeakMB)} MB；Electron（含子进程）：${number(memory?.electronPeakMB)} MB
- 进程树工作集峰值（共享页可能重复计入）：${number(memory?.peakWorkingSetMB)} MB
- 预热后窗口中位数增长趋势：${number(memory?.growthMBPerHour)} MB/小时
- Java 趋势：${number(memory?.javaGrowthMBPerHour)} MB/小时
- Electron 趋势：${number(memory?.electronGrowthMBPerHour)} MB/小时
- 采样数：${memory?.samples ?? '未取得'}；采样缺口：${memory?.gaps ?? '未取得'}
- 预声明阈值：峰值 ${number(config.limits?.memoryLimitMB)} MB；增长 ${number(config.limits?.memoryGrowthMBPerHour)} MB/小时
- 未配置阈值、采样不足或证据不连续时，不宣称内存稳定。

${memory?.windows?.length ? '![预热后私有内存窗口趋势](内存趋势.svg)' : '尚未形成有效趋势窗口，不绘制虚假的长期曲线。'}

## 通信

- 硬件方式：${text(config.hardwareMode)}
- 真实通信判定：${communication?.status ?? '未验证'}
- 实际发送尝试：${communication?.sendAttempts ?? '未取得'}；发送失败：${communication?.sendFailures ?? '未取得'}
- 实际接收报文：${communication?.receivedPackets ?? '未取得'}；解析拒绝：${communication?.parseRejected ?? '未取得'}
- 接收异常：${communication?.receiveFailures ?? '未取得'}；采样缺口：${communication?.gaps ?? '未取得'}；计数重置：${communication?.resets ?? '未取得'}。
- 输入链路：${communication?.input ?? '未验证'}
- SDK 本进程 UDP socket 创建/关闭：${communication?.socketLifecycle?.opened ?? '未取得'} / ${communication?.socketLifecycle?.closed ?? '未取得'}；不是控制器物理在线状态。
- ACK/丢包率：协议不支持可靠判定，不以收发数量之差计算。
- 无回传可能是无人踩踏；不能仅凭零接收认定断网。

## 模拟输出与输入

- 模拟 TCP 游玩帧：${result.floor?.runningFrames ?? '不适用或未取得'}；拒绝帧：${result.floor?.rejectedFrames ?? '不适用或未取得'}
- 过渡帧：${result.floor?.transitionFrames ?? '不适用或未取得'}，不作为稳定游玩帧证据。
- 模拟踩踏：${config.simulatedInputEnabled ? '已启用受控接口（不自动生成踩踏）' : '未启用'}
- 接受的模拟事件：${result.monitoring?.input?.simulatedEvents ?? '未取得或未启用'}；拒绝的注入：${result.monitoring?.input?.rejectedEvents ?? '未取得或未启用'}。
- simulated 结果不能证明真实控制器通信或地砖响应通过。

## 证据文件

- plan.json：开测前配置、实际游戏内容及指纹。
- isolation.json：本轮目录、游戏库/配置及 exe/asar/后端 jar 哈希、端口和运行方式。
- rounds.jsonl：逐局会话、自然终止原因、累计运行时间。
- samples.jsonl：进程身份、私有内存与工作集、测试器自身内存、SDK 计数。
- result.json：本次结果原始数据；failure.png（如有）：失败现场。

缺失文件或指标不可视为零错误，应按未验证处理。
`;
}

export function memoryChart(windows) {
  const width = 800, height = 300, margin = 40;
  const maxX = Math.max(1, ...windows.map((row) => row.at));
  const maxY = Math.max(1, ...windows.map((row) => row.total));
  const curve = (key, color) => `<polyline fill="none" stroke="${color}" stroke-width="2" points="${windows.map((row) => `${margin + row.at / maxX * (width - margin * 2)},${height - margin - row[key] / maxY * (height - margin * 2)}`).join(' ')}"/>`;
  return `<svg xmlns="http://www.w3.org/2000/svg" width="800" height="300" viewBox="0 0 800 300"><rect width="800" height="300" fill="white"/><text x="40" y="20">私有内存 MB：总计（蓝） Java（绿） Electron（橙）；窗口中位数</text><text x="0" y="50">${Math.round(maxY)}</text><text x="40" y="290">0 → ${(maxX / 3600000).toFixed(2)} 小时</text>${curve('total', '#2563eb')}${curve('java', '#15803d')}${curve('electron', '#ea580c')}</svg>`;
}

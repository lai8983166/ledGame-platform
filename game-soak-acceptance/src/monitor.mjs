const median = (values) => {
  const ordered = [...values].sort((a, b) => a - b); const middle = Math.floor(ordered.length / 2);
  return ordered.length % 2 ? ordered[middle] : (ordered[middle - 1] + ordered[middle]) / 2;
};

export class MemoryMonitor {
  #bucket = []; #bucketIndex = null; #windows = []; #lastAt = null;
  samples = 0; gaps = 0; peakMB = 0; peakWorkingSetMB = 0; javaPeakMB = 0; electronPeakMB = 0;
  constructor({ memoryLimitMB = null, memoryGrowthMBPerHour = null, warmupMillis = 600000, windowMillis = 600000 } = {}) {
    Object.assign(this, { memoryLimitMB, memoryGrowthMBPerHour, warmupMillis, windowMillis });
  }
  observe(at, processes) {
    if (!Number.isFinite(at) || at < 0 || (this.#lastAt !== null && at <= this.#lastAt)) throw new Error('内存采样时间非法');
    if (this.#lastAt !== null && at - this.#lastAt > 15000) this.gaps++;
    this.#lastAt = at;
    if (!Array.isArray(processes) || !processes.length || processes.some((p) =>
      !Number.isFinite(p.privateBytes) || p.privateBytes < 0 || !Number.isFinite(p.workingSetBytes) || p.workingSetBytes < 0)) {
      this.gaps++; return;
    }
    const sumMB = (group) => group.reduce((sum, p) => sum + p.privateBytes, 0) / 1048576;
    const total = sumMB(processes); this.samples++; this.peakMB = Math.max(this.peakMB, total);
    this.peakWorkingSetMB = Math.max(this.peakWorkingSetMB, processes.reduce((sum, p) => sum + p.workingSetBytes, 0) / 1048576);
    this.javaPeakMB = Math.max(this.javaPeakMB, sumMB(processes.filter((p) => p.role === 'java')));
    this.electronPeakMB = Math.max(this.electronPeakMB, sumMB(processes.filter((p) => p.role !== 'java')));
    if (at < this.warmupMillis) return;
    const index = Math.floor((at - this.warmupMillis) / this.windowMillis);
    if (this.#bucketIndex !== null && index !== this.#bucketIndex) this.#finishBucket();
    this.#bucketIndex = index;
    if (this.#bucket.length >= 120) { this.gaps++; return; }
    this.#bucket.push({ at, total, java: sumMB(processes.filter((p) => p.role === 'java')),
      electron: sumMB(processes.filter((p) => p.role !== 'java')) });
  }
  #finishBucket() {
    const rows = this.#bucket;
    if (rows.length >= Math.floor(this.windowMillis / 10000 * 0.8)) {
      this.#windows.push({ at: median(rows.map((r) => r.at)), total: median(rows.map((r) => r.total)),
        java: median(rows.map((r) => r.java)), electron: median(rows.map((r) => r.electron)) });
      if (this.#windows.length > 1024) { this.#windows.shift(); this.gaps++; }
    } else this.gaps++;
    this.#bucket = [];
  }
  summary() {
    const slope = (key) => {
      if (this.#windows.length < 3) return null;
      const rows = this.#windows; const avgX = rows.reduce((s, r) => s + r.at / 3600000, 0) / rows.length;
      const avgY = rows.reduce((s, r) => s + r[key], 0) / rows.length;
      return rows.reduce((s, r) => s + (r.at / 3600000 - avgX) * (r[key] - avgY), 0)
        / rows.reduce((s, r) => s + (r.at / 3600000 - avgX) ** 2, 0);
    };
    const growth = slope('total');
    let status = '未判定';
    if (this.memoryLimitMB !== null && this.peakMB > this.memoryLimitMB) status = '失败';
    else if (growth !== null && this.memoryGrowthMBPerHour !== null && growth > this.memoryGrowthMBPerHour) status = '失败';
    else if (!this.gaps && growth !== null && this.memoryLimitMB !== null && this.memoryGrowthMBPerHour !== null) status = '通过';
    return { status, samples: this.samples, gaps: this.gaps, peakMB: this.peakMB,
      peakWorkingSetMB: this.peakWorkingSetMB, javaPeakMB: this.javaPeakMB, electronPeakMB: this.electronPeakMB,
      growthMBPerHour: growth, javaGrowthMBPerHour: slope('java'), electronGrowthMBPerHour: slope('electron'),
      windows: [...this.#windows], limits: { memoryLimitMB: this.memoryLimitMB, memoryGrowthMBPerHour: this.memoryGrowthMBPerHour } };
  }
}

export class ProcessMonitor {
  #critical = new Map();
  observe(processes, { cleaning = false } = {}) {
    if (cleaning) return;
    if (!Array.isArray(processes) || !processes.some((p) => p.role === 'main') || !processes.some((p) => p.role === 'java')) throw new Error('游戏主进程或自有 Java 缺失');
    const current = new Set(processes.map((p) => `${p.pid}:${p.createdAt}`));
    for (const [identity, role] of this.#critical) if (!current.has(identity)) throw new Error(`关键进程退出或被替换：${role} ${identity}`);
    for (const p of processes) {
      if (!p.createdAt || !Number.isInteger(p.pid)) throw new Error('进程身份采样缺失');
      if (p.responding === false && ['main', 'renderer'].includes(p.role)) throw new Error(`窗口无响应：${p.pid}`);
      if (['main', 'renderer', 'java'].includes(p.role)) this.#critical.set(`${p.pid}:${p.createdAt}`, p.role);
    }
  }
}

export class CommunicationMonitor {
  #previous; resets = 0; gaps = 0;
  totals = {
    sendAttempts: 0, sendFailures: 0, receivedPackets: 0, receiveFailures: 0,
    parseRejected: 0, receiverStatusPackets: 0,
    searchAttempts: 0, searchResponseSamples: 0, searchTimeouts: 0, searchLatencyTotalMillis: 0,
  };
  observe(sample) {
    const keys = Object.keys(this.totals);
    const required = ['sendAttempts', 'sendFailures', 'receivedPackets', 'receiveFailures', 'parseRejected', 'receiverStatusPackets'];
    if (!sample?.epoch || required.some((k) => !Number.isSafeInteger(sample[k]) || sample[k] < 0)) { this.gaps++; return; }
    const normalized = { ...sample };
    for (const key of keys.slice(required.length)) {
      if (normalized[key] === undefined) normalized[key] = 0;
      if (!Number.isSafeInteger(normalized[key]) || normalized[key] < 0) { this.gaps++; return; }
    }
    if (this.#previous) {
      if (normalized.epoch !== this.#previous.epoch || keys.some((k) => normalized[k] < this.#previous[k])) this.resets++;
      else for (const key of keys) this.totals[key] += normalized[key] - this.#previous[key];
    }
    this.#previous = normalized;
  }
  summary(mode) {
    const fault = this.totals.sendFailures + this.totals.receiveFailures + this.totals.parseRejected;
    const unavailable = mode !== 'real' || this.gaps > 0 || this.resets > 0 || !this.#previous;
    const discovery = {
      attempts: this.totals.searchAttempts,
      responseSamples: this.totals.searchResponseSamples,
      timeouts: this.totals.searchTimeouts,
      latencyTotalMillis: this.totals.searchLatencyTotalMillis,
      averageLatencyMillis: this.totals.searchResponseSamples
        ? this.totals.searchLatencyTotalMillis / this.totals.searchResponseSamples : null,
      status: mode === 'real' && this.totals.searchResponseSamples > 0 ? '通过' : '未验证',
    };
    return { ...this.totals, gaps: this.gaps, resets: this.resets,
      socketLifecycle: { opened: this.#previous?.socketOpens ?? null, closed: this.#previous?.socketCloses ?? null },
      discovery,
      status: mode === 'real' && fault ? '失败' : unavailable || !this.totals.sendAttempts || !this.totals.receiverStatusPackets ? '未验证' : '通过',
      input: unavailable || !this.totals.receiverStatusPackets ? '未验证' : '已观察真实状态报文',
      ackOrPacketLoss: '协议不支持可靠判定', mode };
  }
}

<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { StatusTone, WristbandState } from "../types";

const emit = defineEmits<{ toast: [message: string] }>();
const API_BASE = "http://127.0.0.1:8090/api";
type UiWristband = { uid: string; state: WristbandState; durationMinutes: number | null; memberName: string | null; phone: string | null };

const wristbands = ref<UiWristband[]>([]);
const chargeUid = ref("");
const chargeMinutes = ref<number | null>(60);
const chargeError = ref("");
const loading = ref(false);
const connected = ref(false);
const statusFilter = ref<"all" | WristbandState>("all");
const actionError = ref("");

const stateMeta: Record<WristbandState, { label: string; description: string; tone: StatusTone }> = {
  empty: { label: "待充时", description: "店员可读取 UID 并录入购买时长", tone: "neutral" },
  charged: { label: "已充时待绑定", description: "等待自助端先确认会员，再刷卡绑定", tone: "warning" },
  ready: { label: "已绑定待游戏", description: "首次在游戏设备刷卡后开始计时", tone: "info" },
  active: { label: "计时中", description: "游戏时长已经开始计算", tone: "success" },
  expired: { label: "已到期", description: "请回收手环后重新办理", tone: "danger" },
};

const filteredWristbands = computed(() => wristbands.value.filter((item) => statusFilter.value === "all" || item.state === statusFilter.value));

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(`${API_BASE}${path}`, { headers: { "Content-Type": "application/json" }, ...init });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.error || `本机服务请求失败（HTTP ${response.status}）`);
  return body as T;
};

const mapWristband = (item: Record<string, unknown>): UiWristband => ({
  uid: String(item.uid),
  state: (String(item.status).toLowerCase() === "in_stock" ? "empty" : String(item.status).toLowerCase()) as WristbandState,
  durationMinutes: item.durationMinutes == null ? null : Number(item.durationMinutes),
  memberName: item.memberName == null ? null : String(item.memberName),
  phone: item.phone == null ? null : String(item.phone),
});

const loadWristbands = async () => {
  try {
    const rows = await request<Record<string, unknown>[]>("/wristbands");
    wristbands.value = rows.map(mapWristband);
    connected.value = true;
  } catch (error) {
    connected.value = false;
    chargeError.value = error instanceof Error ? error.message : "无法连接本机服务，请先启动后端";
  }
};

const normalizeUid = (value: string) => value.replace(/\D/g, "");
const chargeWristband = async () => {
  chargeError.value = "";
  const uid = normalizeUid(chargeUid.value);
  if (!uid) return void (chargeError.value = "请把手环放到读卡器上，等待 UID 输入完成");
  if (!Number.isInteger(chargeMinutes.value) || !chargeMinutes.value || chargeMinutes.value < 1 || chargeMinutes.value > 1440) return void (chargeError.value = "购买分钟数必须是 1 到 1440 的整数");
  loading.value = true;
  try {
    await request(`/wristbands/charge`, { method: "POST", body: JSON.stringify({ uid, durationMinutes: chargeMinutes.value }) });
    chargeUid.value = "";
    emit("toast", `手环 ${uid} 已充入 ${chargeMinutes.value} 分钟`);
    await loadWristbands();
  } catch (error) {
    chargeError.value = error instanceof Error ? error.message : "充时失败";
  } finally {
    loading.value = false;
  }
};

const clearBalance = async (wristband: UiWristband) => {
  actionError.value = "";
  try {
    await request(`/wristbands/clear`, { method: "POST", body: JSON.stringify({ uid: wristband.uid }) });
    emit("toast", `手环 ${wristband.uid} 的可用余额已清除`);
    await loadWristbands();
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : "清除余额失败";
  }
};

const unbind = async (wristband: UiWristband) => {
  actionError.value = "";
  try {
    await request(`/wristbands/unbind`, { method: "POST", body: JSON.stringify({ uid: wristband.uid }) });
    emit("toast", `手环 ${wristband.uid} 已解除绑定并回到库存`);
    await loadWristbands();
  } catch (error) {
    actionError.value = error instanceof Error ? error.message : "解除绑定失败";
  }
};

onMounted(loadWristbands);
</script>

<template>
  <section class="wristband-hero glass-panel">
    <div>
      <p class="section-eyebrow">CORE STORE FLOW</p>
      <h2>先充时，再由自助端查询会员并绑定手环</h2>
      <p>读卡器会把真实 UID（例如 2283055618）输入到当前聚焦的输入框，Enter 到达后立即提交。</p>
    </div>
    <div class="wristband-flow" aria-label="手环状态流程"><span v-for="state in ['empty','charged','ready','active','expired'] as WristbandState[]" :key="state"><i></i>{{ stateMeta[state].label }}</span></div>
  </section>

  <section class="process-card glass-panel">
    <header><span class="process-step">1</span><div><p class="section-eyebrow">CHARGE WRISTBAND</p><h2>柜台充时</h2><p>收款完成后，把实体手环放到读卡器并录入本次购买分钟数。</p></div></header>
    <div class="process-form">
      <label class="form-field"><span>读卡器 UID <b>*</b></span><input v-model="chargeUid" inputmode="numeric" autocomplete="off" maxlength="32" autofocus placeholder="请刷手环，例：2283055618" @input="chargeError = ''" @keydown.enter.prevent="chargeWristband" /><small>刷卡后读卡器会自动发送回车，无需手动输入 UID。</small></label>
      <label class="form-field"><span>购买时长 <b>*</b></span><div class="duration-input"><input v-model.number="chargeMinutes" type="number" min="1" max="1440" @input="chargeError = ''" /><span>分钟</span></div></label>
      <div class="quick-amounts quick-amounts--duration"><button v-for="duration in [30,45,60,90,120]" :key="duration" type="button" :class="{ active: chargeMinutes === duration }" @click="chargeMinutes = duration">{{ duration }} 分钟</button></div>
    </div>
    <p v-if="chargeError" class="form-error"><AppIcon name="alert" :size="16" />{{ chargeError }}</p>
    <footer><span>{{ connected ? "已连接本机后端" : "等待本机后端连接" }}</span><button class="primary-button" type="button" :disabled="loading" @click="chargeWristband"><AppIcon name="card" :size="17" />确认充时</button></footer>
  </section>

  <section class="process-card glass-panel"><header><span class="process-step process-step--green">2</span><div><p class="section-eyebrow">NEXT AT KIOSK</p><h2>自助端完成会员绑定</h2><p>顾客先输入手机号查询或创建会员，再刷已充时手环。这里不再重复绑定或伪造 UID。</p></div></header><footer><span>绑定后状态为“已绑定待游戏”</span><strong>首次游戏设备刷卡才开始计时</strong></footer></section>

  <section class="wristband-table-card glass-panel">
    <header class="wristband-table-header"><div><p class="section-eyebrow">SERVER DATA</p><h2>后端手环状态</h2><p>列表来自本机后端 SQLite，不是页面内置演示数据。</p></div><select v-model="statusFilter" class="select-control" aria-label="筛选手环状态"><option value="all">全部状态</option><option v-for="state in ['empty','charged','ready','active','expired'] as WristbandState[]" :key="state" :value="state">{{ stateMeta[state].label }}</option></select></header>
    <p v-if="actionError" class="form-error"><AppIcon name="alert" :size="16" />{{ actionError }}</p>
    <div class="data-table-wrap"><table class="data-table"><thead><tr><th>手环 UID</th><th>状态</th><th>本次时长</th><th>关联会员</th><th>状态说明</th><th>操作</th></tr></thead><tbody><tr v-for="wristband in filteredWristbands" :key="wristband.uid"><td><code>{{ wristband.uid }}</code></td><td><StatusBadge :tone="stateMeta[wristband.state].tone">{{ stateMeta[wristband.state].label }}</StatusBadge></td><td><strong>{{ wristband.durationMinutes ? `${wristband.durationMinutes} 分钟` : '—' }}</strong></td><td><template v-if="wristband.memberName"><strong>{{ wristband.memberName }}</strong><small class="cell-sub">{{ wristband.phone }}</small></template><span v-else>尚未绑定</span></td><td>{{ stateMeta[wristband.state].description }}</td><td><button v-if="wristband.state === 'charged'" class="secondary-button compact-button" type="button" @click="clearBalance(wristband)">清除可用余额</button><button v-else-if="wristband.state === 'ready'" class="secondary-button compact-button" type="button" @click="unbind(wristband)">解除绑定</button><span v-else>—</span></td></tr><tr v-if="!filteredWristbands.length"><td colspan="6">暂无后端数据。请先启动服务并为实体手环充时。</td></tr></tbody></table></div>
    <footer class="table-footer"><span>所有状态来自本机后端</span><strong>共 {{ filteredWristbands.length }} 只</strong></footer>
  </section>
</template>

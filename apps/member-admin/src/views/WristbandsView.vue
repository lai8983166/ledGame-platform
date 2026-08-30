<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { StatusTone, WristbandState } from "../types";
import { canClearWristbandBalance, canReclaimWristband, normalizeWristbandUid } from "../wristbandActions";
import { platformApi } from "../platformApi";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import {
  acceptChargeScan,
  beginChargeSubmit,
  cancelChargeSession,
  consumeChargeScanKey,
  createWristbandChargeSession,
  failChargeScan,
  failChargeSubmit,
  setChargeMinutes,
  startChargeSession,
  succeedChargeSubmit,
} from "../wristbandChargeSession";
import { operatorSession } from "../operatorSession";
import { canUseOperatorCapability } from "../operatorPolicy";

const emit = defineEmits<{ toast: [message: string] }>();
const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
type UiWristband = {
  uid: string;
  state: WristbandState;
  durationMinutes: number | null;
  remainingSeconds: number;
  startedAt: string | null;
  expiresAt: string | null;
  memberName: string | null;
  phone: string | null;
};
type WristbandActionKind = "clear" | "reclaim";
type PendingWristbandAction = {
  kind: WristbandActionKind;
  wristband: UiWristband;
  clearSourceInput: boolean;
};

const wristbands = ref<UiWristband[]>([]);
const chargeSession = reactive(createWristbandChargeSession());
const refreshing = ref(false);
const connected = ref(false);
const statusFilter = ref<"all" | WristbandState>("all");
const actionError = ref("");
const clearUid = ref("");
const reclaimUid = ref("");
const pendingWristbandAction = ref<PendingWristbandAction | null>(null);
const wristbandActionSubmitting = ref(false);
const canClearBalances = computed(() => canUseOperatorCapability(operatorSession.current.value, "clearWristbandBalance"));

const stateMeta: Record<WristbandState, { label: string; description: string; tone: StatusTone }> = {
  empty: { label: "待充时", description: "店员可读取 UID 并录入购买时长", tone: "neutral" },
  charged: { label: "已充时待绑定", description: "等待自助端先确认会员，再刷卡绑定", tone: "warning" },
  ready: { label: "已绑定待游戏", description: "首次在游戏设备刷卡后开始计时", tone: "info" },
  active: { label: "计时中", description: "游戏时长已经开始计算", tone: "success" },
  expired: { label: "已到期", description: "请回收手环后重新办理", tone: "danger" },
};

const filteredWristbands = computed(() => wristbands.value.filter((item) => statusFilter.value === "all" || item.state === statusFilter.value));

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  return await platformApi.request<T>(`/api${path}`, init) as T;
};

const mapWristband = (item: Record<string, unknown>): UiWristband => ({
  uid: String(item.uid),
  state: (String(item.status).toLowerCase() === "in_stock" ? "empty" : String(item.status).toLowerCase()) as WristbandState,
  durationMinutes: item.durationMinutes == null ? null : Number(item.durationMinutes),
  remainingSeconds: item.remainingSeconds == null ? 0 : Math.max(0, Number(item.remainingSeconds)),
  startedAt: item.startedAt == null ? null : String(item.startedAt),
  expiresAt: item.expiresAt == null ? null : String(item.expiresAt),
  memberName: item.memberName == null ? null : String(item.memberName),
  phone: item.phone == null ? null : String(item.phone),
});

const loadWristbands = async () => {
  refreshing.value = true;
  try {
    const rows = await request<Record<string, unknown>[]>("/wristbands");
    wristbands.value = rows.map(mapWristband);
    connected.value = true;
  } catch (error) {
    connected.value = false;
    actionError.value = error instanceof Error ? error.message : "无法连接本机服务，请先启动后端";
  } finally {
    refreshing.value = false;
  }
};

const formatRemaining = (wristband: UiWristband) => {
  if (wristband.state === "empty") return "—";
  const seconds = Math.max(0, Math.floor(wristband.remainingSeconds));
  const formatted = `${Math.floor(seconds / 60)} 分 ${String(seconds % 60).padStart(2, "0")} 秒`;
  if (wristband.state === "charged") return `${formatted}（待绑定）`;
  if (wristband.state === "ready") return `${formatted}（未开始）`;
  return formatted;
};

const inspectChargeWristband = async (token: { revision: number; uid: string }) => {
  try {
    const wristband = await request<Record<string, unknown>>(`/wristbands/${encodeURIComponent(token.uid)}`);
    acceptChargeScan(chargeSession, token, String(wristband.status));
  } catch (error) {
    if (error instanceof PlatformApiError && error.status === 404) {
      acceptChargeScan(chargeSession, token, null);
      return;
    }
    failChargeScan(chargeSession, token, error instanceof Error ? error.message : "手环状态查询失败");
  }
};

const onChargeScanKeydown = (event: KeyboardEvent) => {
  if (chargeSession.status !== "scanning") return;
  if (/^\d$/.test(event.key) || event.key === "Enter" || event.key === "Backspace") event.preventDefault();
  const token = consumeChargeScanKey(chargeSession, event.key);
  if (token) void inspectChargeWristband(token);
};

const chargeWristband = async () => {
  const charge = beginChargeSubmit(chargeSession);
  if (!charge) return;
  try {
    await request(`/wristbands/charge`, { method: "POST", body: JSON.stringify({ uid: charge.uid, durationMinutes: charge.minutes }) });
    if (!succeedChargeSubmit(chargeSession, charge)) return;
    emit("toast", `手环 ${charge.uid} 已充入 ${charge.minutes} 分钟`);
    await loadWristbands();
  } catch (error) {
    failChargeSubmit(chargeSession, charge, error instanceof Error ? error.message : "充时失败");
  }
};

const updateChargeMinutes = (event: Event) => {
  setChargeMinutes(chargeSession, Number((event.target as HTMLInputElement).value));
};

const clearBalance = (wristband: UiWristband, clearSourceInput = false) => {
  actionError.value = "";
  if (!canClearWristbandBalance(wristband.state)) {
    actionError.value = "只有未绑定的已充时手环可以清除可用余额";
    return;
  }
  pendingWristbandAction.value = { kind: "clear", wristband, clearSourceInput };
};

const clearBalanceFromUid = () => {
  actionError.value = "";
  const uid = normalizeWristbandUid(clearUid.value);
  if (!uid) {
    actionError.value = "请先刷手环，让读卡器输入真实 UID";
    return;
  }
  const wristband = wristbands.value.find((item) => item.uid === uid);
  if (!wristband) {
    actionError.value = "未找到该手环，请先刷新后端状态";
    return;
  }
  if (!canClearWristbandBalance(wristband.state)) {
    actionError.value = "只有未绑定的已充时手环可以清除可用余额；已绑定手环请先解除绑定";
    return;
  }
  clearBalance(wristband, true);
};

const reclaimWristband = (wristband: UiWristband, clearSourceInput = false) => {
  actionError.value = "";
  if (!canReclaimWristband(wristband.state)) {
    actionError.value = "只有已到期的手环可以回收";
    return;
  }
  pendingWristbandAction.value = { kind: "reclaim", wristband, clearSourceInput };
};

const reclaimFromUid = () => {
  actionError.value = "";
  const uid = normalizeWristbandUid(reclaimUid.value);
  if (!uid) {
    actionError.value = "请先刷手环，让读卡器输入真实 UID";
    return;
  }
  const wristband = wristbands.value.find((item) => item.uid === uid);
  if (!wristband) {
    actionError.value = "未找到该手环，请先刷新后端状态";
    return;
  }
  reclaimWristband(wristband, true);
};

const cancelWristbandAction = () => {
  if (wristbandActionSubmitting.value) return;
  pendingWristbandAction.value = null;
  actionError.value = "";
};

const confirmWristbandAction = async () => {
  const action = pendingWristbandAction.value;
  if (!action || wristbandActionSubmitting.value) return;
  wristbandActionSubmitting.value = true;
  actionError.value = "";
  try {
    const endpoint = action.kind === "clear" ? "/wristbands/clear" : "/wristbands/reclaim";
    await request(endpoint, { method: "POST", body: JSON.stringify({ uid: action.wristband.uid }) });
    if (action.kind === "clear") {
      if (action.clearSourceInput) clearUid.value = "";
      emit("toast", `手环 ${action.wristband.uid} 的可用余额已清除`);
    } else {
      if (action.clearSourceInput) reclaimUid.value = "";
      emit("toast", `手环 ${action.wristband.uid} 已回收到库存`);
    }
    pendingWristbandAction.value = null;
    await loadWristbands();
  } catch (error) {
    actionError.value = error instanceof Error
      ? error.message
      : action.kind === "clear" ? "清除余额失败" : "回收手环失败";
  } finally {
    wristbandActionSubmitting.value = false;
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

onMounted(() => {
  window.addEventListener("keydown", onChargeScanKeydown);
  void loadWristbands();
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onChargeScanKeydown);
  cancelChargeSession(chargeSession);
});
</script>

<template>
  <section class="wristband-workbench-grid" data-testid="admin-wristband-workbench">
  <section class="wristband-hero wristband-workbench-card glass-panel" data-testid="admin-wristband-flow-card">
    <div>
      <p class="section-eyebrow">CORE STORE FLOW</p>
      <h2>先充时，再由自助端查询会员并绑定手环</h2>
      <p>{{ text("wristbandFlowDescription") }}</p>
    </div>
    <div class="wristband-flow" aria-label="手环状态流程"><span v-for="state in ['empty','charged','ready','active','expired'] as WristbandState[]" :key="state"><i></i>{{ stateMeta[state].label }}</span></div>
  </section>

  <section class="process-card wristband-workbench-card glass-panel" data-testid="admin-wristband-charge-card">
    <header><span class="process-step">1</span><div><p class="section-eyebrow">CHARGE WRISTBAND</p><h2>柜台充时</h2><p>收款完成后，把实体手环放到读卡器并录入本次购买分钟数。</p></div></header>
    <p>{{ text("chargeStartDescription") }}</p>
    <footer><span data-testid="admin-platform-connection">{{ connected ? "已连接本机后端" : "等待本机后端连接" }}</span><button class="primary-button" data-testid="admin-charge-start" type="button" @click="startChargeSession(chargeSession)"><AppIcon name="card" :size="17" />{{ text("chargeStart") }}</button></footer>
  </section>

  <BaseModal v-if="chargeSession.status !== 'idle'" :title="text(chargeSession.status === 'scanning' || chargeSession.status === 'checking' ? 'chargeScanTitle' : 'chargeConfirmTitle')" :description="text(chargeSession.status === 'scanning' || chargeSession.status === 'checking' ? 'chargeScanDescription' : 'chargeConfirmDescription')" size="small" @close="cancelChargeSession(chargeSession)">
    <div data-testid="admin-charge-dialog">
      <div v-if="chargeSession.status === 'scanning' || chargeSession.status === 'checking'" class="charge-scan-prompt">
        <span><AppIcon name="card" :size="30" /></span>
        <strong>{{ text(chargeSession.status === "checking" ? "chargeChecking" : "chargeWaiting") }}</strong>
        <small>{{ text("chargeCaptureHint") }}</small>
      </div>
      <template v-else>
        <label class="form-field"><span>{{ text("chargeScannedUid") }}</span><strong class="charge-scanned-uid" data-testid="admin-charge-scanned-uid">{{ chargeSession.uid }}</strong></label>
        <label class="form-field"><span>购买时长 <b>*</b></span><div class="duration-input"><input :value="chargeSession.minutes" data-testid="admin-charge-minutes" type="number" min="1" max="1440" :disabled="chargeSession.status === 'submitting'" @input="updateChargeMinutes" /><span>分钟</span></div></label>
        <div class="quick-amounts quick-amounts--duration"><button v-for="duration in [30,45,60,90,120]" :key="duration" type="button" :disabled="chargeSession.status === 'submitting'" :class="{ active: chargeSession.minutes === duration }" @click="setChargeMinutes(chargeSession, duration)">{{ duration }} 分钟</button></div>
      </template>
      <p v-if="chargeSession.error" class="form-error" data-testid="admin-charge-error"><AppIcon name="alert" :size="16" />{{ chargeSession.error }}</p>
    </div>
    <template #footer><button class="ghost-button" data-testid="admin-charge-cancel" type="button" :disabled="chargeSession.status === 'submitting'" @click="cancelChargeSession(chargeSession)">取消</button><button v-if="chargeSession.status === 'details' || chargeSession.status === 'submitting'" class="primary-button" data-testid="admin-charge-submit" type="button" :disabled="chargeSession.status === 'submitting'" @click="chargeWristband">{{ chargeSession.status === "submitting" ? "充值中…" : text("chargeConfirm") }}</button></template>
  </BaseModal>

  <BaseModal
    v-if="pendingWristbandAction"
    :title="pendingWristbandAction.kind === 'clear' ? '确认清除可用余额' : '确认回收手环'"
    :description="pendingWristbandAction.kind === 'clear' ? '此操作不可恢复，请确认实体手环 UID。' : '回收后将清除旧状态并回到库存。'"
    size="small"
    @close="cancelWristbandAction"
  >
    <div data-testid="admin-wristband-action-confirm-dialog">
      <label class="form-field">
        <span>手环 UID</span>
        <strong class="charge-scanned-uid">{{ pendingWristbandAction.wristband.uid }}</strong>
      </label>
      <p v-if="actionError" class="form-error" data-testid="admin-wristband-action-confirm-error"><AppIcon name="alert" :size="16" />{{ actionError }}</p>
    </div>
    <template #footer>
      <button class="ghost-button" data-testid="admin-wristband-action-cancel" type="button" :disabled="wristbandActionSubmitting" @click="cancelWristbandAction">取消</button>
      <button class="primary-button" data-testid="admin-wristband-action-confirm" type="button" :disabled="wristbandActionSubmitting" @click="confirmWristbandAction">
        {{ wristbandActionSubmitting ? "处理中…" : "确认操作" }}
      </button>
    </template>
  </BaseModal>

  <section v-if="canClearBalances" class="process-card wristband-workbench-card glass-panel wristband-clear-card" data-testid="admin-wristband-clear-card">
    <header><span class="process-step process-step--danger">!</span><div><p class="section-eyebrow">OPERATOR ACTION</p><h2>主动清除手环可用余额</h2><p>需要作废尚未绑定的已充时手环时，直接刷真实 UID 后清除。已绑定或计时中的手环不会被误清。</p></div></header>
    <div class="process-form">
      <label class="form-field"><span>手环 UID <b>*</b></span><input v-model="clearUid" inputmode="numeric" autocomplete="off" maxlength="32" placeholder="请刷手环，例：2283055618" @input="actionError = ''" @keydown.enter.prevent="clearBalanceFromUid" /><small>读卡器会自动输入数字并发送回车。</small></label>
    </div>
    <p v-if="actionError" class="form-error" data-testid="admin-wristband-action-error"><AppIcon name="alert" :size="16" />{{ actionError }}</p>
    <footer><span>仅允许状态为“已充时待绑定”的手环</span><button class="secondary-button" type="button" @click="clearBalanceFromUid"><AppIcon name="trash" :size="17" />主动清除可用余额</button></footer>
  </section>

  <section class="process-card wristband-workbench-card glass-panel wristband-clear-card" data-testid="admin-wristband-reclaim-card">
    <header><span class="process-step process-step--danger">↺</span><div><p class="section-eyebrow">OPERATOR ACTION</p><h2>回收已到期手环</h2><p>游戏时间用完后，手环会进入 EXPIRED。确认回收后清除旧状态并回到库存，之后可以重新充时。</p></div></header>
    <div class="process-form">
      <label class="form-field"><span>手环 UID <b>*</b></span><input v-model="reclaimUid" inputmode="numeric" autocomplete="off" maxlength="32" placeholder="请刷已到期手环" @input="actionError = ''" @keydown.enter.prevent="reclaimFromUid" /><small>仅允许 EXPIRED 状态，ACTIVE、READY 手环不能回收。</small></label>
    </div>
    <footer><span>回收后状态变为“待充时”</span><button class="secondary-button" type="button" @click="reclaimFromUid"><AppIcon name="refresh" :size="17" />回收手环</button></footer>
  </section>
  </section>

  <section class="wristband-table-card glass-panel">
    <header class="wristband-table-header"><div><p class="section-eyebrow">SERVER DATA</p><h2>后端手环状态</h2><p>列表来自本机后端 SQLite，不是页面内置演示数据。</p></div><div class="wristband-table-actions"><button class="secondary-button compact-button" data-testid="admin-wristbands-refresh" type="button" :disabled="refreshing" @click="loadWristbands"><AppIcon name="refresh" :size="17" :class="{ spinning: refreshing }" />{{ refreshing ? "刷新中…" : "刷新数据" }}</button><select v-model="statusFilter" class="select-control" aria-label="筛选手环状态"><option value="all">全部状态</option><option v-for="state in ['empty','charged','ready','active','expired'] as WristbandState[]" :key="state" :value="state">{{ stateMeta[state].label }}</option></select></div></header>
    <p v-if="actionError" class="form-error"><AppIcon name="alert" :size="16" />{{ actionError }}</p>
    <div class="data-table-wrap"><table class="data-table"><thead><tr><th>手环 UID</th><th>状态</th><th>本次时长</th><th>当前剩余</th><th>关联会员</th><th>状态说明</th><th>操作</th></tr></thead><tbody><tr v-for="wristband in filteredWristbands" :key="wristband.uid" :data-testid="`admin-wristband-${wristband.uid}`"><td><code data-testid="admin-wristband-uid">{{ wristband.uid }}</code></td><td data-testid="admin-wristband-status" :data-status="wristband.state"><StatusBadge :tone="stateMeta[wristband.state].tone">{{ stateMeta[wristband.state].label }}</StatusBadge></td><td><strong data-testid="admin-wristband-duration">{{ wristband.durationMinutes ? `${wristband.durationMinutes} 分钟` : '—' }}</strong></td><td data-testid="admin-wristband-remaining"><strong>{{ formatRemaining(wristband) }}</strong></td><td data-testid="admin-wristband-member"><template v-if="wristband.memberName"><strong>{{ wristband.memberName }}</strong><small class="cell-sub">{{ wristband.phone }}</small></template><span v-else>尚未绑定</span></td><td>{{ stateMeta[wristband.state].description }}</td><td><button v-if="canClearBalances && wristband.state === 'charged'" class="secondary-button compact-button" data-testid="admin-wristband-clear" type="button" @click="clearBalance(wristband)">清除可用余额</button><button v-else-if="wristband.state === 'ready'" class="secondary-button compact-button" data-testid="admin-wristband-unbind" type="button" @click="unbind(wristband)">解除绑定</button><button v-else-if="wristband.state === 'expired'" class="secondary-button compact-button" data-testid="admin-wristband-reclaim" type="button" @click="reclaimWristband(wristband)">回收手环</button><span v-else>—</span></td></tr><tr v-if="!filteredWristbands.length"><td colspan="7">暂无后端数据。请先启动服务并为实体手环充时。</td></tr></tbody></table></div>
    <footer class="table-footer"><span>所有状态来自本机后端</span><strong>共 {{ filteredWristbands.length }} 只</strong></footer>
  </section>
</template>

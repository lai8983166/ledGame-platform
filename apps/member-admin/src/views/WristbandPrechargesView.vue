<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import { platformApi } from "../platformApi";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import StatusBadge from "../components/StatusBadge.vue";

type Precharge = { id: number; uid: string; durationMinutes: number; version: number; status: "PENDING" | "CLAIMED" | "CANCELLED"; preparedAt: string; operatorName: string | null; operatorUsername: string | null };
const props = defineProps<{ locale: PlatformLocale }>();
const emit = defineEmits<{ toast: [message: string] }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
const rows = ref<Precharge[]>([]);
const uidFilter = ref("");
const statusFilter = ref("PENDING");
const loading = ref(false);
const pageError = ref("");
const modal = ref<"prepare" | "edit" | "cancel" | null>(null);
const dialogTitle = computed(() => text(modal.value === "cancel" ? "prechargeCancel" : modal.value === "edit" ? "prechargeEdit" : "navPrecharges"));
const selected = ref<Precharge | null>(null);
const minutes = ref(60);
const scanning = ref(false);
const busy = ref(false);
const error = ref("");
const lastSuccess = ref("");
const successCount = ref(0);
let buffer = "";
let lastKeyAt = 0;
let disposed = false;
let loadRevision = 0;
let submitted: { uid: string; durationMinutes: number; requestId: string } | null = null;
const request = <T,>(path: string, init?: RequestInit) => platformApi.request<T>(`/api/wristband-precharges${path}`, init) as Promise<T>;
const statusLabel = (status: Precharge["status"]) => text(status === "PENDING" ? "prechargePending" : status === "CLAIMED" ? "prechargeClaimed" : "prechargeCancelled");
const formatTime = (value: string) => new Date(value).toLocaleString(props.locale);
const validMinutes = () => Number.isInteger(minutes.value) && minutes.value >= 1 && minutes.value <= 1440;

const load = async () => {
  const revision = ++loadRevision;
  loading.value = true;
  pageError.value = "";
  try {
    const query = new URLSearchParams();
    if (uidFilter.value.trim()) query.set("uid", uidFilter.value.trim());
    if (statusFilter.value) query.set("status", statusFilter.value);
    const result = await request<Precharge[]>(`?${query}`);
    if (!disposed && revision === loadRevision) rows.value = result;
  } catch (cause) {
    if (!disposed && revision === loadRevision) pageError.value = cause instanceof Error ? cause.message : text("prechargeLoadFailed");
  } finally { if (!disposed && revision === loadRevision) loading.value = false; }
};
const open = (action: "prepare" | "edit" | "cancel", row?: Precharge) => {
  modal.value = action;
  selected.value = row ? { ...row } : null;
  minutes.value = row?.durationMinutes ?? 60;
  scanning.value = false;
  error.value = "";
  lastSuccess.value = "";
  successCount.value = 0;
  submitted = null;
  buffer = "";
};
const close = () => {
  if (busy.value) return;
  modal.value = null;
  scanning.value = false;
  submitted = null;
  buffer = "";
};
const beginScan = () => {
  if (!validMinutes()) { error.value = text("prechargeInvalidMinutes"); return; }
  error.value = "";
  buffer = "";
  scanning.value = true;
};
const submitCard = async (uid?: string) => {
  if (busy.value || !scanning.value || modal.value !== "prepare") return;
  if (!submitted) {
    if (!uid || !/^\d{1,32}$/.test(uid)) return;
    submitted = { uid, durationMinutes: minutes.value, requestId: crypto.randomUUID() };
  }
  busy.value = true;
  error.value = "";
  const current = submitted;
  try {
    await request("", { method: "POST", body: JSON.stringify(current) });
    successCount.value++;
    lastSuccess.value = `${current.uid} · ${current.durationMinutes} ${text("prechargeMinutesUnit")}`;
    submitted = null;
    void load();
  } catch (cause) {
    // Keep the request identity after network/server failures: retry must not prepare twice.
    if (cause instanceof PlatformApiError && cause.status >= 400 && cause.status < 500) submitted = null;
    error.value = cause instanceof Error ? cause.message : text("prechargeSubmitFailed");
  } finally { busy.value = false; }
};
const saveChange = async () => {
  if (!selected.value || busy.value) return;
  if (modal.value === "edit" && !validMinutes()) { error.value = text("prechargeInvalidMinutes"); return; }
  busy.value = true;
  error.value = "";
  try {
    await request(`/${selected.value.id}/${modal.value}`, { method: "POST", body: JSON.stringify({ version: selected.value.version, durationMinutes: minutes.value }) });
    emit("toast", text(modal.value === "edit" ? "prechargeSaved" : "prechargeCancelSuccess"));
    modal.value = null;
    void load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : text("prechargeSubmitFailed"); }
  finally { busy.value = false; }
};
const onKeydown = (event: KeyboardEvent) => {
  if (!scanning.value || modal.value !== "prepare" || busy.value || submitted || event.ctrlKey || event.altKey || event.metaKey || event.repeat) return;
  if (/^\d$/.test(event.key)) {
    event.preventDefault();
    if (Date.now() - lastKeyAt > 1500) buffer = "";
    lastKeyAt = Date.now();
    if (buffer.length < 32) buffer += event.key;
  } else if (event.key === "Backspace") { event.preventDefault(); buffer = buffer.slice(0,-1); }
  else if (event.key === "Enter") {
    event.preventDefault();
    const uid = buffer;
    buffer = "";
    if (uid) void submitCard(uid);
  }
};
onMounted(() => { window.addEventListener("keydown", onKeydown); void load(); });
onBeforeUnmount(() => { disposed = true; loadRevision++; window.removeEventListener("keydown", onKeydown); });
</script>

<template>
  <section class="process-card glass-panel precharge-page" data-testid="precharge-page">
    <form class="precharge-toolbar" @submit.prevent="load">
      <button class="primary-button" type="button" data-testid="precharge-start" @click="open('prepare')"><AppIcon name="plus" />{{ text('navPrecharges') }}</button>
      <input v-model="uidFilter" class="select-control" v-bind="{ placeholder: text('cardUid'), 'aria-label': text('cardUid') }" inputmode="numeric" data-testid="precharge-search" />
      <select v-model="statusFilter" class="select-control" v-bind="{ 'aria-label': text('prechargeStatus') }" data-testid="precharge-status" @change="load">
        <option value="">{{ text('prechargeAll') }}</option>
        <option value="PENDING">{{ text('prechargePending') }}</option>
        <option value="CLAIMED">{{ text('prechargeClaimed') }}</option>
        <option value="CANCELLED">{{ text('prechargeCancelled') }}</option>
      </select>
      <button class="secondary-button" type="submit" :disabled="loading" data-testid="precharge-refresh"><AppIcon name="refresh" />{{ text('memberRefresh') }}</button>
    </form>
    <p v-if="pageError" class="form-error" role="alert">{{ pageError }}</p>
    <div class="data-table-wrap"><table class="data-table"><thead><tr>
      <th>{{ text('cardUid') }}</th><th>{{ text('prechargeMinutes') }}</th><th>{{ text('prechargeStatus') }}</th><th>{{ text('prechargePreparedAt') }}</th><th>{{ text('prechargeOperator') }}</th><th>{{ text('prechargeActions') }}</th>
    </tr></thead><tbody>
      <tr v-for="row in rows" :key="row.id" :data-testid="`precharge-row-${row.uid}`">
        <td>{{ row.uid }}</td><td>{{ row.durationMinutes }} {{ text('prechargeMinutesUnit') }}</td>
        <td><StatusBadge :tone="row.status === 'PENDING' ? 'warning' : row.status === 'CLAIMED' ? 'success' : 'neutral'">{{ statusLabel(row.status) }}</StatusBadge></td>
        <td>{{ formatTime(row.preparedAt) }}</td><td>{{ row.operatorName || row.operatorUsername || '—' }}</td>
        <td><div v-if="row.status === 'PENDING'" class="precharge-actions">
          <button class="secondary-button compact-button" type="button" data-testid="precharge-edit" @click="open('edit',row)">{{ text('prechargeEdit') }}</button>
          <button class="ghost-button compact-button" type="button" data-testid="precharge-cancel" @click="open('cancel',row)">{{ text('prechargeCancel') }}</button>
        </div><span v-else>—</span></td>
      </tr><tr v-if="!rows.length"><td colspan="6">{{ loading ? text('prechargeLoading') : text('prechargeEmpty') }}</td></tr>
    </tbody></table></div>
  </section>
  <BaseModal v-if="modal" v-bind="{ title: dialogTitle }" size="small" @close="close">
    <div data-testid="precharge-dialog">
      <template v-if="!scanning && modal !== 'cancel'">
        <p v-if="selected">{{ selected.uid }}</p>
        <label class="form-field"><span>{{ text('prechargeMinutes') }}</span><input v-model.number="minutes" type="number" min="1" max="1440" step="1" data-testid="precharge-minutes" :disabled="busy" @keydown.enter.prevent="modal === 'prepare' ? beginScan() : saveChange()" /></label>
        <div class="precharge-quick"><button v-for="value in [30,60]" :key="value" class="secondary-button" type="button" :disabled="busy" @click="minutes=value">{{ value }} {{ text('prechargeMinutesUnit') }}</button></div>
      </template>
      <div v-else-if="scanning" class="precharge-scanner" data-testid="precharge-scan-prompt">
        <AppIcon name="card" :size="48" /><strong>{{ busy ? text('memberSubmitting') : text('prechargeScan') }}</strong>
        <span>{{ minutes }} {{ text('prechargeMinutesUnit') }} · {{ text('prechargePreparedCount') }} {{ successCount }}</span>
        <span v-if="lastSuccess" class="precharge-success" data-testid="precharge-last-success">{{ lastSuccess }}</span>
      </div>
      <p v-else data-testid="precharge-cancel-confirm">{{ text('prechargeCancelConfirm') }} {{ selected?.uid }} · {{ selected?.durationMinutes }} {{ text('prechargeMinutesUnit') }}？</p>
      <p v-if="error" class="form-error" role="alert" data-testid="precharge-error">{{ error }}</p>
    </div>
    <template #footer>
      <button class="ghost-button" type="button" data-testid="precharge-close" :disabled="busy" @click="close">{{ text(scanning ? 'prechargeFinish' : 'memberCancel') }}</button>
      <button v-if="modal === 'prepare' && !scanning" class="primary-button" type="button" data-testid="precharge-begin-scan" @click="beginScan">{{ text('prechargeBeginScan') }}</button>
      <button v-else-if="modal !== 'prepare'" class="primary-button" type="button" :disabled="busy" data-testid="precharge-change-submit" @click="saveChange">{{ text('prechargeConfirm') }}</button>
      <button v-else-if="submitted" class="primary-button" type="button" :disabled="busy" data-testid="precharge-retry" @click="submitCard()">{{ text('prechargeRetry') }}</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.precharge-page { padding: 24px; }
.precharge-toolbar,.precharge-actions,.precharge-quick { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.precharge-toolbar { margin-bottom:20px; }
.precharge-toolbar input { flex:1; min-width:160px; max-width:340px; }
.precharge-quick { margin-top:12px; }
.precharge-scanner { min-height:180px; display:flex; flex-direction:column; align-items:center; justify-content:center; gap:14px; }
.precharge-scanner strong { font-size:24px; }
.precharge-success { color:var(--success,#16784c); overflow-wrap:anywhere; }
</style>

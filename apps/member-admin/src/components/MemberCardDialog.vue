<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import BaseModal from "./BaseModal.vue";
import AppIcon from "./AppIcon.vue";
import { platformApi } from "../platformApi";

const props = defineProps<{ action: "issue" | "return"; locale: PlatformLocale; presetMember?: { id: string; name: string; phone: string; remainingSeconds?: number } | null }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
const dialogTitle = computed(() => text(props.action === "issue" ? "cardIssue" : "cardReturn"));
const emit = defineEmits<{ close: []; done: [receipt: { returnedSeconds?: number; accountRemainingSeconds?: number }] }>();
const step = ref<"member" | "scan" | "confirm">(props.action === "issue" ? "member" : "scan");
const phone = ref("");
const name = ref("");
const member = ref<{ id: number; name: string; phone: string; remainingSeconds?: number } | null>(null);
const band = ref<{ uid: string; bindingId?: number; memberName?: string; phone?: string; remainingSeconds?: number; status?: string; durationMinutes?: number; prechargeId?: number; prechargeVersion?: number } | null>(null);
const isPrecharged = computed(() => props.action === "issue" && band.value?.status === "CHARGED");
const minutes = ref(0);
const registrationNeeded = ref(false);
const initialMinutes = ref(0);
const initialAmount = ref("");
const registrationRequestId = crypto.randomUUID();
const busy = ref(false);
const committing = ref(false);
const error = ref("");
let buffer = "";
let lastKeyAt = 0;
let closed = false;
let revision = 0;
let submitted: { uid: string; memberId: number; durationMinutes: number; requestId: string; prechargeId?: number; prechargeVersion?: number } | null = null;
const request = async <T,>(path: string, init?: RequestInit) => await platformApi.request<T>(`/api${path}`, init) as T;
const balance = (seconds = 0) => `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;

const close = () => {
  if (committing.value) return;
  closed = true;
  revision++;
  emit("close");
};
const chooseMember = async () => {
  if (busy.value || closed) return;
  const digits = phone.value.trim();
  if (!/^\d{7,15}$/.test(digits)) { error.value = "请输入有效手机号"; return; }
  busy.value = true;
  error.value = "";
  try {
    const rows = await request<Array<NonNullable<typeof member.value>>>(`/members?phone=${encodeURIComponent(digits)}`);
    if (closed) return;
    const existing = rows.find(row => row.phone === digits);
    if (!existing && !registrationNeeded.value) { registrationNeeded.value = true; return; }
    if (!existing && name.value.trim().length < 2) { error.value = "会员姓名至少需要 2 个字符"; return; }
    if (!existing && (!Number.isInteger(initialMinutes.value) || initialMinutes.value < 0 || initialMinutes.value > 1440)) { error.value = "初始充值分钟数无效"; return; }
    if (!existing && initialAmount.value && (!/^\d+(\.\d{1,2})?$/.test(initialAmount.value) || Number(initialAmount.value)>1_000_000 || initialMinutes.value===0)) { error.value = "请填写有效的初始充值和收款金额"; return; }
    member.value = existing ?? await request<NonNullable<typeof member.value>>("/members", {
      method: "POST", body: JSON.stringify({ phone: digits, name: name.value.trim(), avatarId: "avatar-01", createdBy: "member-admin", initialDurationMinutes: initialMinutes.value, amountCents: initialAmount.value === "" ? null : Math.round(Number(initialAmount.value)*100), requestId: registrationRequestId }),
    });
    if (!closed) step.value = "scan";
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "会员读取失败"; }
  finally { busy.value = false; }
};

const scan = async (uid: string) => {
  const currentRevision = ++revision;
  busy.value = true;
  error.value = "";
  try {
    let found: NonNullable<typeof band.value>;
    try { found = await request(`/wristbands/${encodeURIComponent(uid)}`); }
    catch (cause) {
      if (props.action === "issue" && cause instanceof PlatformApiError && cause.code === "WRISTBAND_NOT_FOUND") found = { uid, status: "IN_STOCK" };
      else throw cause;
    }
    if (closed || currentRevision !== revision) return;
    if (props.action === "issue" && !["IN_STOCK", "CHARGED"].includes(found.status ?? "")) throw new Error("此手环已发卡，请先还卡");
    if (props.action === "return" && (!found.bindingId || !["READY", "ACTIVE", "EXPIRED"].includes(found.status ?? ""))) throw new Error("此手环没有待归还的绑定");
    band.value = found;
    if (props.action === "issue" && found.status === "CHARGED") minutes.value = found.durationMinutes ?? 0;
    step.value = "confirm";
  } catch (cause) { if (!closed) error.value = cause instanceof Error ? cause.message : "手环读取失败"; }
  finally { busy.value = false; }
};
const onKeydown = (event: KeyboardEvent) => {
  if (step.value !== "scan" || busy.value || event.ctrlKey || event.altKey || event.metaKey || event.repeat) return;
  if (/^\d$/.test(event.key)) {
    event.preventDefault();
    if (Date.now() - lastKeyAt > 1500) buffer = "";
    lastKeyAt = Date.now();
    if (buffer.length <= 32) buffer += event.key;
  } else if (event.key === "Enter") {
    event.preventDefault();
    const uid = buffer;
    buffer = "";
    if (uid && /^\d{1,32}$/.test(uid)) void scan(uid);
    else if (uid) error.value = "手环 ID 必须为 1 到 32 位数字";
  }
};
const confirm = async () => {
  if (!band.value || busy.value) return;
  if (props.action === "issue" && (!Number.isInteger(minutes.value) || minutes.value < 1 || minutes.value > 1440)) { error.value = "本次发卡时长必须是 1 到 1440 的整数"; return; }
  busy.value = true;
  committing.value = true;
  error.value = "";
  try {
    let receipt: { returnedSeconds?: number; accountRemainingSeconds?: number };
    if (props.action === "issue") {
      submitted ??= { uid: band.value.uid, memberId: member.value!.id, durationMinutes: minutes.value, requestId: crypto.randomUUID(),
        ...(isPrecharged.value ? { prechargeId: band.value.prechargeId, prechargeVersion: band.value.prechargeVersion } : {}) };
      receipt = await request("/wristbands/issue", { method: "POST", body: JSON.stringify(submitted) });
    } else {
      receipt = await request("/wristbands/return", { method: "POST", body: JSON.stringify({ uid: band.value.uid, bindingId: band.value.bindingId }) });
    }
    emit("done", receipt);
  } catch (cause) {
    if (cause instanceof PlatformApiError && cause.status >= 400 && cause.status < 500) {
      submitted = null;
      if (cause.code === "PRECHARGE_CHANGED") { band.value = null; step.value = "scan"; buffer = ""; }
    }
    error.value = cause instanceof Error ? cause.message : "操作失败";
  }
  finally { busy.value = false; committing.value = false; }
};
onMounted(() => {
  window.addEventListener("keydown", onKeydown);
  if (props.action === "issue" && props.presetMember) {
    member.value = { ...props.presetMember, id: Number(props.presetMember.id) };
    step.value = "scan";
  }
});
onBeforeUnmount(() => { closed = true; revision++; window.removeEventListener("keydown", onKeydown); });
</script>

<template>
  <BaseModal v-bind="{ title: dialogTitle }" @close="close">
    <div v-if="step === 'member'" class="form-grid">
      <label class="form-field"><span>{{ text('cardPhone') }}</span><input v-model="phone" data-testid="card-member-phone" inputmode="numeric" :disabled="busy" @keydown.enter="chooseMember" /></label>
      <template v-if="registrationNeeded">
        <label class="form-field"><span>{{ text('cardName') }}</span><input v-model="name" data-testid="card-member-name" :disabled="busy" @keydown.enter="chooseMember" /></label>
        <label class="form-field"><span>{{ text('memberInitialMinutes') }}</span><input v-model.number="initialMinutes" type="number" min="0" max="1440" :disabled="busy" /></label>
        <label class="form-field"><span>{{ text('memberAmount') }}</span><input v-model="initialAmount" inputmode="decimal" :disabled="busy" /></label>
      </template>
    </div>
    <div v-else-if="step === 'scan'" class="card-scan" data-testid="card-scan-prompt"><AppIcon name="card" :size="56" /><strong>{{ busy ? '读取中…' : '请刷手环' }}</strong></div>
    <div v-else-if="band" class="form-grid" data-testid="card-confirmation">
      <dl class="detail-grid">
        <div><dt>{{ text('cardMember') }}</dt><dd>{{ member?.name ?? band.memberName }} · {{ member?.phone ?? band.phone }}</dd></div>
        <div><dt>{{ text('cardUid') }}</dt><dd>{{ band.uid }}</dd></div>
        <div><dt>{{ action === 'issue' ? '账户余额' : '本次剩余／退回账户' }}</dt><dd data-testid="card-balance">{{ balance(action === 'issue' ? member?.remainingSeconds : band.remainingSeconds) }}</dd></div>
      </dl>
      <template v-if="action === 'issue'">
        <label class="form-field"><span>{{ text(isPrecharged ? 'prechargeMinutes' : 'memberAllocationMinutes') }}</span><input v-model.number="minutes" data-testid="card-minutes" type="number" min="1" max="1440" step="1" :disabled="busy || !!submitted || isPrecharged" @keydown.enter="confirm" /></label>
        <div v-if="!isPrecharged" class="card-quick-values"><button v-for="value in [30, 60]" :key="value" class="secondary-button" type="button" :disabled="busy || !!submitted" @click="minutes = value">{{ value }} 分钟</button></div>
      </template>
    </div>
    <p v-if="error" class="form-error" data-testid="card-error">{{ error }}</p>
    <template #footer>
      <button class="ghost-button" type="button" data-testid="card-cancel" :disabled="committing" @click="close">取消</button>
      <button v-if="step === 'member'" class="primary-button" type="button" data-testid="card-member-next" :disabled="busy" @click="chooseMember">下一步</button>
      <button v-else-if="step === 'confirm'" class="primary-button" type="button" data-testid="card-submit" :disabled="busy" @click="confirm">{{ busy ? '提交中…' : action === 'issue' ? '确认发卡' : '确认还卡' }}</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.card-scan { display: flex; align-items: center; justify-content: center; gap: 20px; min-height: 160px; color: var(--text-main); font-size: 24px; }
.card-quick-values { display: flex; gap: 12px; }
.detail-grid { grid-column: 1 / -1; }
</style>

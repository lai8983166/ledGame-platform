<script setup lang="ts">
import { computed, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import { platformApi } from "../platformApi";
import BaseModal from "./BaseModal.vue";
import { readStoredLocale } from "@ledgame/platform-shared-ui";
import { MEMBER_ADMIN_LOCALE_STORAGE_KEY, memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";

const props = defineProps<{ member?: { id: string; name: string; phone: string; remainingSeconds?: number } | null }>();
const emit = defineEmits<{ close: []; done: [] }>();
const locale = readStoredLocale(window.localStorage, MEMBER_ADMIN_LOCALE_STORAGE_KEY);
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[locale][key];
const dialogTitle = computed(() => text(props.member ? "memberRecharge" : "memberCreate"));
const name = ref("");
const phone = ref("");
const minutes = ref(props.member ? 30 : 0);
const amount = ref("");
const busy = ref(false);
const error = ref("");
let submitted: { path: string; body: string } | null = null;
const balance = (seconds = 0) => `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`;
const close = () => { if (!busy.value) emit("close"); };
const save = async () => {
  if (busy.value) return;
  error.value = "";
  if (!submitted) {
    if (!Number.isInteger(minutes.value) || minutes.value < (props.member ? 1 : 0) || minutes.value > 1440) { error.value = "请输入有效的充值分钟数"; return; }
    if (!props.member && (!/^\d{7,15}$/.test(phone.value.trim()) || name.value.trim().length < 2)) { error.value = "请输入有效姓名和手机号"; return; }
    if (amount.value && (!/^\d+(\.\d{1,2})?$/.test(amount.value) || Number(amount.value) > 1_000_000)) { error.value = "收款金额最多保留两位小数"; return; }
    if (amount.value && minutes.value === 0) { error.value = "填写收款时请同时填写充值分钟数"; return; }
    const credit = { amountCents: amount.value === "" ? null : Math.round(Number(amount.value) * 100), requestId: crypto.randomUUID() };
    submitted = props.member
      ? { path: `/api/members/${props.member.id}/recharge`, body: JSON.stringify({ ...credit, durationMinutes: minutes.value }) }
      : { path: "/api/members", body: JSON.stringify({ ...credit, phone: phone.value.trim(), name: name.value.trim(), avatarId: "avatar-01", initialDurationMinutes: minutes.value, createdBy: "member-admin" }) };
  }
  busy.value = true;
  try {
    await platformApi.request(submitted.path, { method: "POST", body: submitted.body });
    emit("done");
  } catch (cause) {
    if (cause instanceof PlatformApiError && cause.status >= 400 && cause.status < 500) submitted = null;
    error.value = cause instanceof Error ? cause.message : "保存失败，请重试";
  } finally { busy.value = false; }
};
</script>

<template>
  <BaseModal v-bind="{ title: dialogTitle }" @close="close">
    <div class="form-grid" data-testid="member-recharge-dialog">
      <template v-if="member">
        <label class="form-field"><span>{{ text('cardMember') }}</span><strong>{{ member.name }} · {{ member.phone }}</strong></label>
        <label class="form-field"><span>{{ text('memberAccountBalance') }}</span><strong>{{ balance(member.remainingSeconds) }}</strong></label>
      </template>
      <template v-else>
        <label class="form-field"><span>{{ text('cardName') }} <b>*</b></span><input v-model="name" data-testid="member-create-name" :disabled="busy || !!submitted" @keydown.enter="save" /></label>
        <label class="form-field"><span>{{ text('cardPhone') }} <b>*</b></span><input v-model="phone" data-testid="member-create-phone" inputmode="numeric" :disabled="busy || !!submitted" @keydown.enter="save" /></label>
      </template>
      <label class="form-field"><span>{{ text(member ? 'memberRechargeMinutes' : 'memberInitialMinutes') }}</span><input v-model.number="minutes" data-testid="member-recharge-minutes" type="number" :min="member ? 1 : 0" max="1440" step="1" :disabled="busy || !!submitted" @keydown.enter="save" /></label>
      <label class="form-field"><span>{{ text('memberAmount') }}</span><input v-model="amount" data-testid="member-recharge-amount" inputmode="decimal" :disabled="busy || !!submitted" @keydown.enter="save" /></label>
      <div class="quick-values"><button v-for="value in [30,60,120]" :key="value" class="secondary-button" type="button" :disabled="busy || !!submitted" @click="minutes = value">{{ value }} 分钟</button></div>
    </div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <template #footer><button class="ghost-button" type="button" :disabled="busy" @click="close">{{ text('memberCancel') }}</button><button class="primary-button" data-testid="member-recharge-submit" type="button" :disabled="busy" @click="save">{{ text(busy ? 'memberSubmitting' : member ? 'memberConfirmRecharge' : 'memberSave') }}</button></template>
  </BaseModal>
</template>

<style scoped>
.quick-values { grid-column: 1 / -1; display: flex; flex-wrap: wrap; gap: 10px; }
</style>

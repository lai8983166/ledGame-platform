<script setup lang="ts">
import { computed, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import { platformApi } from "../platformApi";
import BaseModal from "./BaseModal.vue";

const props=defineProps<{ member: { id: string; name: string; phone: string; remainingSeconds?: number }; locale: PlatformLocale }>();
const emit=defineEmits<{ close: []; done: [] }>();
const text=(key: MemberAdminMessageKey)=>memberAdminCatalogs[props.locale][key];
const dialogTitle=computed(()=>text("memberAdjustmentTitle"));
const direction=ref<"ADD"|"DEDUCT">("ADD");
const minutes=ref(0);
const reason=ref("");
const busy=ref(false);
const error=ref("");
const submitted=ref<string|null>(null);
const validMinutes=computed(()=>Number.isInteger(minutes.value) && minutes.value>=1 && minutes.value<=1440);
const expectedSeconds=computed(()=>(props.member.remainingSeconds??0)+minutes.value*60*(direction.value==="ADD"?1:-1));
const balance=(seconds=0)=>`${Math.floor(seconds/60)} ${text('memberHistoryMinutes')} ${seconds%60} ${text('memberAdjustmentSeconds')}`;
const close=()=>{ if (!busy.value) emit("close"); };
const save=async()=>{
  if (busy.value) return;
  error.value="";
  if (!submitted.value) {
    if (!validMinutes.value) { error.value=text("memberAdjustmentInvalidMinutes"); return; }
    if (!reason.value.trim() || reason.value.trim().length>500) { error.value=text("memberAdjustmentInvalidReason"); return; }
    if (direction.value==="DEDUCT" && expectedSeconds.value<0) { error.value=text("memberAdjustmentInsufficient"); return; }
    submitted.value=JSON.stringify({direction:direction.value,durationMinutes:minutes.value,reason:reason.value.trim(),requestId:crypto.randomUUID()});
  }
  busy.value=true;
  try {
    await platformApi.request(`/api/members/${props.member.id}/time-adjustments`,{method:"POST",body:submitted.value});
    emit("done");
  } catch (cause) {
    if (cause instanceof PlatformApiError && cause.status>=400 && cause.status<500) submitted.value=null;
    error.value=cause instanceof Error?cause.message:text("memberAdjustmentFailed");
  } finally { busy.value=false; }
};
</script>

<template>
  <BaseModal v-bind="{title:dialogTitle}" @close="close">
    <div class="form-grid" data-testid="member-adjustment-dialog">
      <label class="form-field"><span>{{ text('cardMember') }}</span><strong>{{ member.name }} · {{ member.phone }}</strong></label>
      <label class="form-field"><span>{{ text('memberAdjustmentAccount') }}</span><strong>{{ balance(member.remainingSeconds) }}</strong></label>
      <label class="form-field"><span>{{ text('memberAdjustmentDirection') }}</span><select v-model="direction" data-testid="member-adjustment-direction" :disabled="busy || !!submitted"><option value="ADD">{{ text('memberAdjustmentAdd') }}</option><option value="DEDUCT">{{ text('memberAdjustmentDeduct') }}</option></select></label>
      <label class="form-field"><span>{{ text('memberAdjustmentMinutes') }} <b>*</b></span><input v-model.number="minutes" type="number" min="1" max="1440" step="1" data-testid="member-adjustment-minutes" :disabled="busy || !!submitted" /></label>
      <label class="form-field adjustment-reason"><span>{{ text('memberAdjustmentReason') }} <b>*</b></span><textarea v-model="reason" rows="3" maxlength="500" data-testid="member-adjustment-reason" :disabled="busy || !!submitted"></textarea></label>
      <label class="form-field adjustment-result"><span>{{ text('memberAdjustmentExpected') }}</span><strong data-testid="member-adjustment-expected" :class="{'form-error':validMinutes && expectedSeconds<0}">{{ validMinutes ? (expectedSeconds < 0 ? text('memberAdjustmentInsufficient') : balance(expectedSeconds)) : '—' }}</strong></label>
    </div>
    <p v-if="error" class="form-error" role="alert" data-testid="member-adjustment-error">{{ error }}</p>
    <template #footer>
      <button class="ghost-button" data-testid="member-adjustment-cancel" type="button" :disabled="busy" @click="close">{{ text('memberCancel') }}</button>
      <button class="primary-button" data-testid="member-adjustment-submit" type="button" :disabled="busy" @click="save">{{ text(busy ? 'memberSubmitting' : 'memberAdjustmentConfirm') }}</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.adjustment-reason,.adjustment-result { grid-column:1/-1; }
.adjustment-reason textarea { width:100%; resize:vertical; min-height:80px; }
</style>

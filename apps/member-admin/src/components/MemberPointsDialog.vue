<script setup lang="ts">
import { computed, ref } from "vue";
import { PlatformApiError } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import { platformApi } from "../platformApi";
import BaseModal from "./BaseModal.vue";

const props = defineProps<{ member: { id: string; name: string; phone: string; remainingPoints?: number; rankingPoints?: number }; locale: PlatformLocale }>();
const emit = defineEmits<{ close: []; done: [] }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
const dialogTitle = computed(() => text("pointsDeduct"));
const points = ref(0);
const reason = ref("");
const busy = ref(false);
const error = ref("");
const submitted = ref<string | null>(null);
const validPoints = computed(() => Number.isSafeInteger(points.value) && points.value > 0);
const expected = computed(() => (props.member.remainingPoints ?? 0) - points.value);
const close = () => { if (!busy.value) emit("close"); };
const save = async () => {
  if (busy.value) return;
  error.value = "";
  if (!submitted.value) {
    if (!validPoints.value) { error.value = text("pointsDeductInvalid"); return; }
    if (!reason.value.trim() || reason.value.trim().length > 500) { error.value = text("pointsDeductInvalidReason"); return; }
    if (expected.value < 0) { error.value = text("pointsDeductInsufficient"); return; }
    submitted.value = JSON.stringify({ points: points.value, reason: reason.value.trim(), requestId: crypto.randomUUID() });
  }
  busy.value = true;
  try {
    await platformApi.request(`/api/members/${props.member.id}/point-deductions`, { method: "POST", body: submitted.value });
    emit("done");
  } catch (cause) {
    // A lost response can follow a committed deduction: retry the same request, not a new charge.
    if (cause instanceof PlatformApiError && cause.status >= 400 && cause.status < 500) submitted.value = null;
    error.value = cause instanceof Error ? cause.message : text("pointsDeductFailed");
  } finally { busy.value = false; }
};
</script>

<template>
  <BaseModal v-bind="{ title: dialogTitle }" @close="close">
    <div class="form-grid" data-testid="member-points-dialog">
      <label class="form-field"><span>{{ text('cardMember') }}</span><strong>{{ member.name }} · {{ member.phone }}</strong></label>
      <label class="form-field"><span>{{ text('remainingPoints') }}</span><strong>{{ member.remainingPoints ?? 0 }}</strong></label>
      <label class="form-field"><span>{{ text('pointsDeductAmount') }} <b>*</b></span><input v-model.number="points" type="number" min="1" step="1" data-testid="member-points-amount" :disabled="busy || !!submitted" /></label>
      <label class="form-field"><span>{{ text('pointsDeductExpected') }}</span><strong data-testid="member-points-expected" :class="{ 'form-error': validPoints && expected < 0 }">{{ validPoints ? (expected < 0 ? text('pointsDeductInsufficient') : expected) : '—' }}</strong></label>
      <label class="form-field points-reason"><span>{{ text('pointsDeductReason') }} <b>*</b></span><textarea v-model="reason" rows="3" maxlength="500" data-testid="member-points-reason" :disabled="busy || !!submitted"></textarea></label>
    </div>
    <p v-if="error" class="form-error" role="alert" data-testid="member-points-error">{{ error }}</p>
    <template #footer>
      <button class="ghost-button" data-testid="member-points-cancel" type="button" :disabled="busy" @click="close">{{ text('memberCancel') }}</button>
      <button class="primary-button" data-testid="member-points-submit" type="button" :disabled="busy" @click="save">{{ text(busy ? 'memberSubmitting' : 'pointsDeductConfirm') }}</button>
    </template>
  </BaseModal>
</template>

<style scoped>
.points-reason { grid-column: 1 / -1; }
.points-reason textarea { width: 100%; resize: vertical; min-height: 80px; }
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import { platformApi } from "../platformApi";
import BaseModal from "./BaseModal.vue";

type Level = { id: number; name: string; minPoints: number; maxPoints: number };
const props = defineProps<{ locale: PlatformLocale; canDelete: boolean }>();
const emit = defineEmits<{ toast: [message: string] }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
const rows = ref<Level[]>([]);
const loading = ref(false);
const busy = ref(false);
const error = ref("");
const formError = ref("");
const editing = ref(false);
const selected = ref<Level | null>(null);
const pendingDelete = ref<Level | null>(null);
const form = reactive({ name: "", minPoints: 0, maxPoints: 0 });
const dialogTitle = computed(() => text(selected.value ? "memberLevelEdit" : "memberLevelCreate"));
const deleteTitle = computed(() => text("memberLevelDeleteConfirm"));

const load = async () => {
  loading.value = true;
  error.value = "";
  try { rows.value = await platformApi.request<Level[]>("/api/member-levels") ?? []; }
  catch (cause) { error.value = cause instanceof Error ? cause.message : text("memberLevelFailed"); }
  finally { loading.value = false; }
};
const open = (level: Level | null) => {
  selected.value = level;
  Object.assign(form, level ? { name: level.name, minPoints: level.minPoints, maxPoints: level.maxPoints } : { name: "", minPoints: 0, maxPoints: 0 });
  formError.value = "";
  editing.value = true;
};
const close = () => { if (!busy.value) { editing.value = false; pendingDelete.value = null; } };
const save = async () => {
  if (busy.value) return;
  const name = form.name.trim();
  formError.value = "";
  if (!name || name.length > 30 || rows.value.some(row => row.id !== selected.value?.id && row.name.toLowerCase() === name.toLowerCase())) {
    formError.value = text("memberLevelNameInvalid"); return;
  }
  if (!Number.isSafeInteger(form.minPoints) || !Number.isSafeInteger(form.maxPoints) || form.minPoints < 0 || form.minPoints > form.maxPoints) {
    formError.value = text("memberLevelRangeInvalid"); return;
  }
  if (rows.value.some(row => row.id !== selected.value?.id && row.minPoints <= form.maxPoints && row.maxPoints >= form.minPoints)) {
    formError.value = text("memberLevelOverlap"); return;
  }
  busy.value = true;
  try {
    await platformApi.request(`/api/member-levels${selected.value ? `/${selected.value.id}` : ""}`, {
      method: selected.value ? "PUT" : "POST", body: JSON.stringify({ name, minPoints: form.minPoints, maxPoints: form.maxPoints }),
    });
    editing.value = false;
    await load();
    emit("toast", text("memberLevelSaved"));
  } catch (cause) { formError.value = cause instanceof Error ? cause.message : text("memberLevelFailed"); }
  finally { busy.value = false; }
};
const remove = async () => {
  if (busy.value || !props.canDelete || !pendingDelete.value) return;
  busy.value = true;
  formError.value = "";
  try {
    await platformApi.request(`/api/member-levels/${pendingDelete.value.id}`, { method: "DELETE" });
    pendingDelete.value = null;
    await load();
    emit("toast", text("memberLevelDeleted"));
  } catch (cause) { formError.value = cause instanceof Error ? cause.message : text("memberLevelFailed"); }
  finally { busy.value = false; }
};
onMounted(load);
</script>

<template>
  <section class="settings-card glass-panel" data-testid="member-level-settings">
    <header class="level-header"><h2>{{ text('memberLevels') }}</h2><button class="secondary-button" type="button" data-testid="member-level-refresh" :disabled="loading || busy" @click="load">{{ text('memberRefresh') }}</button><button class="primary-button" type="button" data-testid="member-level-create" :disabled="loading || busy || !!error" @click="open(null)">{{ text('memberLevelCreate') }}</button></header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-else-if="loading">{{ text('memberHistoryLoading') }}</p>
    <p v-else-if="!rows.length" data-testid="member-level-empty">{{ text('memberLevelEmpty') }}</p>
    <div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>{{ text('memberLevelName') }}</th><th>{{ text('memberLevelMin') }}</th><th>{{ text('memberLevelMax') }}</th><th></th></tr></thead><tbody>
      <tr v-for="level in rows" :key="level.id" :data-testid="`member-level-row-${level.id}`"><td>{{ level.name }}</td><td>{{ level.minPoints }}</td><td>{{ level.maxPoints }}</td><td class="level-actions"><button class="secondary-button" type="button" data-testid="member-level-edit" :disabled="loading || busy" @click="open(level)">{{ text('memberLevelEdit') }}</button><button v-if="canDelete" class="danger-button" type="button" data-testid="member-level-delete" :disabled="loading || busy" @click="pendingDelete = level; formError = ''">{{ text('memberLevelDelete') }}</button></td></tr>
    </tbody></table></div>
  </section>
  <BaseModal v-if="editing" v-bind="{ title: dialogTitle }" @close="close">
    <div class="form-grid" data-testid="member-level-dialog">
      <label class="form-field level-name"><span>{{ text('memberLevelName') }} <b>*</b></span><input v-model="form.name" maxlength="30" data-testid="member-level-name" :disabled="busy" /></label>
      <label class="form-field"><span>{{ text('memberLevelMin') }} <b>*</b></span><input v-model.number="form.minPoints" type="number" min="0" step="1" data-testid="member-level-min" :disabled="busy" /></label>
      <label class="form-field"><span>{{ text('memberLevelMax') }} <b>*</b></span><input v-model.number="form.maxPoints" type="number" min="0" step="1" data-testid="member-level-max" :disabled="busy" /></label>
    </div>
    <p v-if="formError" class="form-error" role="alert" data-testid="member-level-error">{{ formError }}</p>
    <template #footer><button class="ghost-button" data-testid="member-level-cancel" type="button" :disabled="busy" @click="close">{{ text('memberCancel') }}</button><button class="primary-button" data-testid="member-level-save" type="button" :disabled="busy" @click="save">{{ text(busy ? 'memberSubmitting' : 'memberLevelSave') }}</button></template>
  </BaseModal>
  <BaseModal v-if="pendingDelete" v-bind="{ title: deleteTitle }" size="small" @close="close">
    <strong>{{ pendingDelete.name }} · {{ pendingDelete.minPoints }}–{{ pendingDelete.maxPoints }}</strong>
    <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>
    <template #footer><button class="ghost-button" type="button" data-testid="member-level-delete-cancel" :disabled="busy" @click="close">{{ text('memberCancel') }}</button><button class="danger-button" type="button" data-testid="member-level-delete-confirm" :disabled="busy" @click="remove">{{ text(busy ? 'memberSubmitting' : 'memberLevelDelete') }}</button></template>
  </BaseModal>
</template>

<style scoped>
.level-header { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 20px; }
.level-header h2 { margin: 0 auto 0 0; }
.level-actions { display: flex; gap: 10px; }
.level-name { grid-column: 1 / -1; }
</style>

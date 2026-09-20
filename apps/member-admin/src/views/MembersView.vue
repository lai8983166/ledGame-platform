<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { Member } from "../types";
import { platformApi, platformBaseUrl } from "../platformApi";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import {
  cancelMemberDeletion,
  createMemberDeletionState,
  openMemberDeletion,
  submitMemberDeletion,
} from "../memberDeletionState";
import { operatorSession } from "../operatorSession";
import { canUseOperatorCapability } from "../operatorPolicy";

const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];

const emit = defineEmits<{ toast: [message: string] }>();
const palette = ["#5b7cff", "#9b6dff", "#18b6a4", "#ff8a65", "#62758a"];
const members = ref<Member[]>([]);
const search = ref("");
const statusFilter = ref<"all" | "active" | "inactive">("all");
const selectedMember = ref<Member | null>(null);
const creating = ref(false);
const loading = ref(false);
const refreshing = ref(false);
const formError = ref("");
const connectionError = ref("");
const memberForm = ref({ name: "", phone: "" });
const editingMember = ref(false);
const editForm = ref({ name: "", phone: "", birthday: "", gender: "", avatarId: "" });
const editError = ref("");
const editLoading = ref(false);
type MemberChargeHistory = {
  id: number | string;
  uid?: string | null;
  durationMinutes?: number | null;
  unitPriceCents?: number | null;
  amountCents?: number | null;
  issuedAt?: string | null;
  chargedAt?: string | null;
  operatorLabel?: string | null;
};
type MemberGameHistory = {
  id: number | string;
  uid?: string | null;
  gameId?: string | null;
  gameName?: string | null;
  deviceId?: string | null;
  roomId?: string | null;
  status?: string | null;
  startedAt?: string | null;
  endedAt?: string | null;
  success?: boolean | null;
  terminationReason?: string | null;
  rawScore?: number | null;
  pointsAwarded?: number | null;
  scoringPolicy?: string | null;
};
type MemberHistory = { charges: MemberChargeHistory[]; plays: MemberGameHistory[] };
const memberHistory = ref<MemberHistory | null>(null);
const memberHistoryLoading = ref(false);
const memberHistoryError = ref("");
const historyModal = ref<"charges" | "plays" | null>(null);
let memberHistoryRequest = 0;
const deletion = reactive(createMemberDeletionState());
const canEditMembers = computed(() => canUseOperatorCapability(operatorSession.current.value, "memberManage"));
const canDeleteMembers = computed(() => canUseOperatorCapability(operatorSession.current.value, "deleteMember"));

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  return await platformApi.request<T>(`/api${path}`, init) as T;
};

type BackendMember = { id: number; phone: string; name: string; avatarId?: string | null; avatarUrl?: string | null; birthday?: string | null; gender?: string | null; status: string; createdAt?: string; pointsTotal: number; rank: number };
const mapMember = (item: BackendMember): Member => {
  const id = String(item.id);
  const initials = item.name.trim().slice(-2).toUpperCase();
  return { id, account: `DB-${id}`, name: item.name, initials, phone: item.phone, identityId: "未设置", status: item.status === "ACTIVE" ? "active" : "inactive", joinedAt: (item.createdAt ?? "").slice(0, 10) || "—", color: palette[item.id % palette.length], pointsTotal: Number(item.pointsTotal ?? 0), rank: Number(item.rank ?? 1), avatarId: item.avatarId ?? null, birthday: item.birthday ?? null, gender: item.gender ?? null };
};

const mapMemberWithAvatar = (item: BackendMember): Member => ({
  ...mapMember(item),
  avatarUrl: item.avatarUrl ? `${platformBaseUrl}${item.avatarUrl}` : null,
});

const loadMembers = async () => {
  refreshing.value = true;
  try {
    const rows = await request<BackendMember[]>("/members");
    members.value = rows.map(mapMemberWithAvatar);
    connectionError.value = "";
  } catch (error) {
    connectionError.value = error instanceof Error ? error.message : "无法连接本机服务";
  } finally {
    refreshing.value = false;
  }
};

const filteredMembers = computed(() => {
  const query = search.value.trim().toLowerCase();
  return members.value.filter((member) => {
    const matchesQuery = !query || [member.account, member.name, member.phone].some((value) => value.toLowerCase().includes(query));
    const matchesStatus = statusFilter.value === "all" || member.status === statusFilter.value;
    return matchesQuery && matchesStatus;
  });
});

const formatHistoryTime = (value?: string | null) => {
  if (!value) return "—";
  return String(value).slice(0, 19).replace("T", " ");
};

const loadMemberHistory = async (member: Member) => {
  const requestId = ++memberHistoryRequest;
  memberHistoryLoading.value = true;
  memberHistoryError.value = "";
  try {
    const result = await request<MemberHistory>(`/members/${member.id}/history`);
    if (requestId === memberHistoryRequest && selectedMember.value?.id === member.id) {
      memberHistory.value = {
        charges: Array.isArray(result.charges) ? result.charges : [],
        plays: Array.isArray(result.plays) ? result.plays : [],
      };
    }
  } catch (error) {
    if (requestId === memberHistoryRequest && selectedMember.value?.id === member.id) {
      memberHistoryError.value = error instanceof Error ? error.message : text("memberHistoryLoadFailed");
      memberHistory.value = { charges: [], plays: [] };
    }
  } finally {
    if (requestId === memberHistoryRequest) memberHistoryLoading.value = false;
  }
};

const openMember = (member: Member) => {
  selectedMember.value = member;
  historyModal.value = null;
  memberHistory.value = null;
  memberHistoryError.value = "";
  void loadMemberHistory(member);
};

const closeMember = () => {
  memberHistoryRequest += 1;
  historyModal.value = null;
  selectedMember.value = null;
  memberHistory.value = null;
  memberHistoryError.value = "";
  memberHistoryLoading.value = false;
};

const openHistoryModal = (kind: "charges" | "plays") => {
  if (memberHistoryLoading.value || memberHistoryError.value || !memberHistory.value) return;
  historyModal.value = kind;
};

const openCreate = () => {
  memberForm.value = { name: "", phone: "" };
  formError.value = "";
  creating.value = true;
};

const saveMember = async () => {
  const form = memberForm.value;
  if (form.name.trim().length < 2) return void (formError.value = "会员姓名至少需要 2 个字符");
  if (!/^\d{7,15}$/.test(form.phone.replace(/\D/g, ""))) return void (formError.value = "请输入有效手机号");
  loading.value = true;
  try {
    await request<BackendMember>("/members", { method: "POST", body: JSON.stringify({ name: form.name.trim(), phone: form.phone, createdBy: "member-admin" }) });
    creating.value = false;
    await loadMembers();
    emit("toast", "会员已保存到本机数据库");
  } catch (error) {
    formError.value = error instanceof Error ? error.message : "会员保存失败";
  } finally {
    loading.value = false;
  }
};

const openMemberEdit = (member: Member) => {
  editForm.value = {
    name: member.name,
    phone: member.phone,
    birthday: member.birthday ?? "",
    gender: member.gender ?? "",
    avatarId: member.avatarId ?? "",
  };
  editError.value = "";
  editingMember.value = true;
};

const saveMemberEdit = async () => {
  const form = editForm.value;
  const phone = form.phone.replace(/\D/g, "");
  if (form.name.trim().length < 2) return void (editError.value = "会员姓名至少需要 2 个字符");
  if (!/^\d{7,15}$/.test(phone)) return void (editError.value = "请输入有效手机号");
  if (form.birthday && !/^\d{4}-\d{2}-\d{2}$/.test(form.birthday)) return void (editError.value = "生日格式必须为 YYYY-MM-DD");
  if (!selectedMember.value) return;
  editLoading.value = true;
  editError.value = "";
  try {
    const updated = await platformApi.updateMember(Number(selectedMember.value.id), {
      name: form.name.trim(), phone, birthday: form.birthday || null, gender: form.gender || null,
      avatarId: form.avatarId || null,
    });
    const mapped = mapMemberWithAvatar(updated as unknown as BackendMember);
    const index = members.value.findIndex((item) => item.id === selectedMember.value?.id);
    if (index >= 0) members.value[index] = mapped;
    selectedMember.value = mapped;
    void loadMemberHistory(mapped);
    editingMember.value = false;
    emit("toast", "会员资料已保存");
  } catch (error) {
    editError.value = error instanceof Error ? error.message : "会员资料保存失败";
  } finally {
    editLoading.value = false;
  }
};

const askToDeleteMember = (member: Member) => {
  openMemberDeletion(deletion, { id: Number(member.id), name: member.name, phone: member.phone });
};

const closeMemberDeletion = () => {
  if (deletion.status !== "submitting") cancelMemberDeletion(deletion);
};

const confirmMemberDeletion = async () => {
  const deleted = await submitMemberDeletion(deletion, (id) => platformApi.deleteMember(id));
  if (!deleted) return;
  closeMember();
  await loadMembers();
  emit("toast", text("memberDeleteAction"));
};

onMounted(loadMembers);
</script>

<template>
  <section class="toolbar glass-panel">
    <button class="secondary-button" data-testid="admin-members-refresh" type="button" :disabled="refreshing" @click="loadMembers"><AppIcon name="refresh" :size="17" :class="{ spinning: refreshing }" />{{ refreshing ? "刷新中…" : "刷新数据" }}</button>
    <div class="search-field search-field--wide"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="查询会员" placeholder="查询姓名、数据库 ID 或手机号" /></div>
    <select v-model="statusFilter" class="select-control" aria-label="会员状态筛选"><option value="all">全部状态</option><option value="active">正常</option><option value="inactive">停用</option></select>
    <span class="result-count">共 {{ filteredMembers.length }} 位会员</span>
    <button class="primary-button toolbar__primary" data-testid="admin-member-create" type="button" @click="openCreate"><AppIcon name="plus" :size="18" /> 新增会员</button>
  </section>

  <section v-if="connectionError" class="notice-bar" data-testid="admin-members-error"><AppIcon name="alert" :size="18" /><div><strong>无法读取数据库会员</strong><p>{{ connectionError }}。请先启动本机后端。</p></div></section>

  <section class="table-card glass-panel">
    <div v-if="filteredMembers.length" class="data-table-wrap"><table class="data-table member-table"><thead><tr><th>会员</th><th>联系方式</th><th>数据库 ID</th><th>{{ text("points") }}</th><th>{{ text("rank") }}</th><th>加入日期</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="member in filteredMembers" :key="member.id" :data-testid="`admin-member-${member.id}`"><td><button class="member-cell" type="button" @click="openMember(member)"><span class="avatar" :style="{ background: member.color }"><img v-if="member.avatarUrl" :src="member.avatarUrl" alt="" class="avatar__image" @error="member.avatarUrl = null" /><span v-else>{{ member.initials }}</span></span><span><strong data-testid="admin-member-name">{{ member.name }}</strong><small>{{ member.account }}</small></span></button></td><td data-testid="admin-member-phone">{{ member.phone }}</td><td><code>{{ member.id }}</code></td><td data-testid="admin-member-points">{{ member.pointsTotal }}</td><td data-testid="admin-member-rank">#{{ member.rank }}</td><td>{{ member.joinedAt }}</td><td><StatusBadge :tone="member.status === 'active' ? 'success' : 'neutral'">{{ member.status === 'active' ? '正常' : '停用' }}</StatusBadge></td><td><button class="icon-button" type="button" aria-label="查看会员" @click="openMember(member)"><AppIcon name="eye" :size="17" /></button></td></tr></tbody></table></div>
    <div v-else class="empty-state empty-state--flat"><span><AppIcon name="search" :size="28" /></span><h2>没有找到会员</h2><p>当前列表来自本机 SQLite 数据库。</p><button class="secondary-button" type="button" @click="search = ''; statusFilter = 'all'">清除筛选</button></div>
    <footer class="table-footer"><span>当前显示数据库中的会员</span><strong>共 {{ filteredMembers.length }} 位</strong></footer>
  </section>

  <SideDrawer v-if="selectedMember" :title="selectedMember.name" :eyebrow="selectedMember.account" @close="closeMember"><div class="member-hero"><span class="avatar avatar--large" :style="{ background: selectedMember.color }"><img v-if="selectedMember.avatarUrl" :src="selectedMember.avatarUrl" alt="" class="avatar__image" @error="selectedMember.avatarUrl = null" /><span v-else>{{ selectedMember.initials }}</span></span><div><h3>{{ selectedMember.name }}</h3><p>{{ selectedMember.phone }}</p><StatusBadge :tone="selectedMember.status === 'active' ? 'success' : 'neutral'">{{ selectedMember.status === 'active' ? '正常会员' : '已停用' }}</StatusBadge></div></div><div class="drawer-actions"><button v-if="selectedMember.status === 'active' && canEditMembers" class="secondary-button" data-testid="admin-member-edit" type="button" @click="openMemberEdit(selectedMember)"><AppIcon name="edit" :size="17" /> 编辑会员资料</button></div><section class="drawer-section"><div class="drawer-section__title"><h3>数据库资料</h3></div><dl class="detail-grid"><div><dt>数据库 ID</dt><dd>{{ selectedMember.id }}</dd></div><div><dt>联系方式</dt><dd>{{ selectedMember.phone }}</dd></div><div><dt>加入日期</dt><dd>{{ selectedMember.joinedAt }}</dd></div><div><dt>生日</dt><dd>{{ selectedMember.birthday || '未设置' }}</dd></div><div><dt>性别</dt><dd>{{ selectedMember.gender || '未设置' }}</dd></div><div><dt>身份 ID</dt><dd>未设置</dd></div></dl></section><section class="drawer-section member-history-section" data-testid="admin-member-history"><div class="drawer-section__title"><h3>{{ text("memberChargeHistoryTitle") }}</h3><span>{{ memberHistory?.charges.length ?? 0 }}</span></div><p v-if="memberHistoryLoading" class="member-history-state">{{ text("memberHistoryLoading") }}</p><p v-else-if="memberHistoryError" class="member-history-state member-history-state--error">{{ memberHistoryError }}</p><p v-else-if="!memberHistory?.charges.length" class="member-history-state">{{ text("memberHistoryEmpty") }}</p><ul v-else class="member-history-list" data-testid="admin-member-charge-history"><li v-for="record in memberHistory.charges" :key="`charge-${record.id}`"><div><strong>{{ record.durationMinutes ?? 0 }} {{ text("memberHistoryMinutes") }}</strong><small>{{ formatHistoryTime(record.chargedAt || record.issuedAt) }} · {{ record.operatorLabel || '—' }}</small></div><strong>¥{{ ((record.amountCents ?? 0) / 100).toFixed(2) }}</strong></li></ul><p v-if="!memberHistoryLoading && !memberHistoryError && memberHistory?.charges.length" class="member-history-note">{{ text("memberHistoryHistoricalNote") }}</p></section><section class="drawer-section member-history-section"><div class="drawer-section__title"><h3>{{ text("memberGameHistoryTitle") }}</h3><span>{{ memberHistory?.plays.length ?? 0 }}</span></div><p v-if="memberHistoryLoading" class="member-history-state">{{ text("memberHistoryLoading") }}</p><p v-else-if="memberHistoryError" class="member-history-state member-history-state--error">{{ memberHistoryError }}</p><p v-else-if="!memberHistory?.plays.length" class="member-history-state">{{ text("memberHistoryEmpty") }}</p><ul v-else class="member-history-list" data-testid="admin-member-game-history"><li v-for="play in memberHistory.plays" :key="`play-${play.id}`"><div><strong>{{ play.gameName || play.gameId || '—' }}</strong><small>{{ formatHistoryTime(play.startedAt) }} · {{ play.roomId || play.deviceId || '—' }}</small></div><div class="member-history-list__result"><strong>{{ play.pointsAwarded ?? 0 }} {{ text("memberHistoryPoints") }}</strong><small>{{ play.status || '—' }} / {{ play.terminationReason || '—' }}</small></div></li></ul></section><div class="notice-bar"><AppIcon name="card" :size="18" /><div><strong>会员与手环分离</strong><p>请在“手环办理”查看具体手环的可用分钟数和绑定状态。</p></div></div><section v-if="canDeleteMembers" class="drawer-section member-danger-zone"><div class="drawer-section__title"><h3>{{ text("memberDeleteDangerTitle") }}</h3></div><p>{{ text("memberDeleteDangerBody") }}</p><button class="danger-button" data-testid="admin-member-delete" type="button" @click="askToDeleteMember(selectedMember)"><AppIcon name="trash" :size="17" />{{ text("memberDeleteAction") }}</button></section></SideDrawer>

  <BaseModal v-if="editingMember && selectedMember" title="编辑会员资料" description="修改后将保存到本机数据库。" size="large" @close="editingMember = false"><div class="form-grid"><label class="form-field"><span>会员姓名 <b>*</b></span><input v-model="editForm.name" data-testid="admin-member-edit-name" @input="editError = ''" /></label><label class="form-field"><span>联系方式 <b>*</b></span><input v-model="editForm.phone" data-testid="admin-member-edit-phone" inputmode="numeric" @input="editError = ''" /></label><label class="form-field"><span>生日</span><input v-model="editForm.birthday" type="date" data-testid="admin-member-edit-birthday" @input="editError = ''" /></label><label class="form-field"><span>性别</span><select v-model="editForm.gender" data-testid="admin-member-edit-gender"><option value="">未设置</option><option value="男">男</option><option value="女">女</option></select></label><label class="form-field"><span>头像标识</span><input v-model="editForm.avatarId" data-testid="admin-member-edit-avatar" placeholder="内置头像名称（可选）" /></label></div><p v-if="editError" class="form-error"><AppIcon name="alert" :size="16" />{{ editError }}</p><template #footer><button class="ghost-button" type="button" :disabled="editLoading" @click="editingMember = false">取消</button><button class="primary-button" data-testid="admin-member-edit-save" type="button" :disabled="editLoading" @click="saveMemberEdit">{{ editLoading ? '保存中…' : '保存资料' }}</button></template></BaseModal>

  <BaseModal v-if="deletion.target" :title="text('memberDeleteConfirmTitle')" :description="text('memberDeleteModalDescription')" size="small" @close="closeMemberDeletion"><div data-testid="admin-member-delete-dialog" class="danger-confirm"><span><AppIcon name="alert" :size="22" /></span><div><strong>{{ deletion.target.name }} · {{ deletion.target.phone }}</strong><p>{{ text("memberDeleteConsequences") }}</p></div></div><p v-if="deletion.error" class="form-error"><AppIcon name="alert" :size="16" />{{ deletion.error }}</p><template #footer><button class="ghost-button" data-testid="admin-member-delete-cancel" type="button" :disabled="deletion.status === 'submitting'" @click="closeMemberDeletion">取消</button><button class="danger-button danger-button--solid" data-testid="admin-member-delete-confirm" type="button" :disabled="deletion.status === 'submitting'" @click="confirmMemberDeletion">{{ deletion.status === "submitting" ? "删除中…" : text("memberDeleteAction") }}</button></template></BaseModal>

  <BaseModal v-if="creating" title="新增会员" description="资料会直接保存到本机 SQLite 数据库。" size="large" @close="creating = false"><div class="form-grid"><label class="form-field"><span>会员姓名 <b>*</b></span><input v-model="memberForm.name" data-testid="admin-member-name-input" placeholder="请输入姓名" @input="formError = ''" /></label><label class="form-field"><span>联系方式 <b>*</b></span><input v-model="memberForm.phone" data-testid="admin-member-phone-input" inputmode="numeric" placeholder="请输入手机号" @input="formError = ''" /></label></div><p v-if="formError" class="form-error"><AppIcon name="alert" :size="16" /> {{ formError }}</p><template #footer><button class="ghost-button" type="button" @click="creating = false">取消</button><button class="primary-button" data-testid="admin-member-save" type="button" :disabled="loading" @click="saveMember">保存到数据库</button></template></BaseModal>
  <Teleport to="body">
    <div v-if="selectedMember" class="member-history-floating-actions" data-testid="admin-member-history-actions">
      <button class="secondary-button" type="button" :disabled="memberHistoryLoading || !!memberHistoryError" data-testid="admin-member-charge-history-button" @click="openHistoryModal('charges')"><AppIcon name="wallet" :size="17" />{{ text("memberChargeHistoryTitle") }}<span>{{ memberHistory?.charges.length ?? 0 }}</span></button>
      <button class="secondary-button" type="button" :disabled="memberHistoryLoading || !!memberHistoryError" data-testid="admin-member-game-history-button" @click="openHistoryModal('plays')"><AppIcon name="game" :size="17" />{{ text("memberGameHistoryTitle") }}<span>{{ memberHistory?.plays.length ?? 0 }}</span></button>
    </div>
  </Teleport>

  <BaseModal v-if="historyModal && selectedMember" :title="historyModal === 'charges' ? text('memberChargeHistoryTitle') : text('memberGameHistoryTitle')" size="large" @close="historyModal = null">
    <p v-if="memberHistoryLoading" class="member-history-state">{{ text("memberHistoryLoading") }}</p>
    <p v-else-if="memberHistoryError" class="member-history-state member-history-state--error">{{ memberHistoryError }}</p>
    <template v-else-if="historyModal === 'charges'">
      <p v-if="!memberHistory?.charges.length" class="member-history-state">{{ text("memberHistoryEmpty") }}</p>
      <ul v-else class="member-history-list" data-testid="admin-member-charge-history-modal"><li v-for="record in memberHistory.charges" :key="`charge-modal-${record.id}`"><div><strong>{{ record.durationMinutes ?? 0 }} {{ text("memberHistoryMinutes") }}</strong><small>{{ formatHistoryTime(record.chargedAt || record.issuedAt) }} · {{ record.operatorLabel || '—' }}</small></div><strong>¥{{ ((record.amountCents ?? 0) / 100).toFixed(2) }}</strong></li></ul>
      <p v-if="memberHistory?.charges.length" class="member-history-note">{{ text("memberHistoryHistoricalNote") }}</p>
    </template>
    <template v-else>
      <p v-if="!memberHistory?.plays.length" class="member-history-state">{{ text("memberHistoryEmpty") }}</p>
      <ul v-else class="member-history-list" data-testid="admin-member-game-history-modal"><li v-for="play in memberHistory.plays" :key="`play-modal-${play.id}`"><div><strong>{{ play.gameName || play.gameId || '—' }}</strong><small>{{ formatHistoryTime(play.startedAt) }} · {{ play.roomId || play.deviceId || '—' }}</small></div><div class="member-history-list__result"><strong>{{ play.pointsAwarded ?? 0 }} {{ text("memberHistoryPoints") }}</strong><small>{{ play.status || '—' }} / {{ play.terminationReason || '—' }}</small></div></li></ul>
    </template>
  </BaseModal>
</template>

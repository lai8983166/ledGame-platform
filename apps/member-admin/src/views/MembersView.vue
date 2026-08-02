<script setup lang="ts">
import { computed, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { createMembers } from "../data";
import type { Member } from "../types";

const emit = defineEmits<{ toast: [message: string] }>();
const members = ref(createMembers());
const search = ref("");
const statusFilter = ref<"all" | "active" | "inactive">("all");
const selectedMember = ref<Member | null>(null);
const editingMember = ref<Member | null>(null);
const deletingMember = ref<Member | null>(null);
const rechargeMember = ref<Member | null>(null);
const rechargeAmount = ref<number | null>(null);
const rechargeError = ref("");
const formError = ref("");
const memberForm = ref({ account: "", name: "", phone: "", identityId: "", braceletMinutes: 60, rechargeAmount: 0 });

const filteredMembers = computed(() => {
  const query = search.value.trim().toLowerCase();
  return members.value.filter((member) => {
    const matchesQuery = !query || [member.account, member.name, member.phone, member.identityId].some((value) => value.toLowerCase().includes(query));
    const matchesStatus = statusFilter.value === "all" || member.status === statusFilter.value;
    return matchesQuery && matchesStatus;
  });
});

const openCreate = () => {
  editingMember.value = { id: "", account: "", name: "", initials: "", phone: "", identityId: "", rechargeAmount: 0, braceletMinutes: 60, status: "active", joinedAt: "2026-08-02", color: "#5b7cff" };
  memberForm.value = { account: `M2026${String(members.value.length + 19).padStart(4, "0")}`, name: "", phone: "", identityId: "", braceletMinutes: 60, rechargeAmount: 0 };
  formError.value = "";
};

const openEdit = (member: Member) => {
  editingMember.value = member;
  memberForm.value = { account: member.account, name: member.name, phone: member.phone, identityId: member.identityId, braceletMinutes: member.braceletMinutes, rechargeAmount: member.rechargeAmount };
  formError.value = "";
};

const saveMember = () => {
  const form = memberForm.value;
  if (!form.account.trim() || !form.name.trim() || !form.phone.trim() || !form.identityId.trim()) return void (formError.value = "请完整填写全部必填资料");
  if (!Number.isInteger(Number(form.braceletMinutes)) || Number(form.braceletMinutes) < 1 || Number(form.braceletMinutes) > 1440) return void (formError.value = "手环时长须为 1–1440 分钟的整数");
  if (members.value.some((member) => member.id !== editingMember.value?.id && member.account === form.account.trim())) return void (formError.value = "会员账号已存在");
  const initials = form.name.trim().slice(-2).toUpperCase();
  if (editingMember.value?.id) {
    Object.assign(editingMember.value, { ...form, account: form.account.trim(), name: form.name.trim(), initials });
    emit("toast", "会员资料已更新（演示状态）");
  } else {
    members.value.unshift({ id: `m-${Date.now()}`, ...form, account: form.account.trim(), name: form.name.trim(), initials, status: "active", joinedAt: "2026-08-02", color: "#5b7cff" });
    emit("toast", "新会员已加入演示列表");
  }
  editingMember.value = null;
};

const confirmDelete = () => {
  if (!deletingMember.value) return;
  members.value = members.value.filter((member) => member.id !== deletingMember.value?.id);
  deletingMember.value = null;
  selectedMember.value = null;
  emit("toast", "会员已从当前演示列表移除");
};

const openRecharge = (member: Member) => {
  rechargeMember.value = member;
  rechargeAmount.value = null;
  rechargeError.value = "";
};

const submitRecharge = () => {
  const amount = Number(rechargeAmount.value);
  if (!Number.isFinite(amount) || amount <= 0) return void (rechargeError.value = "请输入大于 0 的充值金额");
  if (amount > 10000) return void (rechargeError.value = "单次演示充值金额不能超过 ¥10,000");
  if (rechargeMember.value) rechargeMember.value.rechargeAmount += amount;
  rechargeMember.value = null;
  emit("toast", `模拟充值 ¥${amount.toFixed(2)} 已更新`);
};
</script>

<template>
  <section class="toolbar glass-panel">
    <div class="search-field search-field--wide"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="查询会员" placeholder="查询姓名、账号、联系方式或身份 ID" /></div>
    <select v-model="statusFilter" class="select-control" aria-label="会员状态筛选"><option value="all">全部状态</option><option value="active">正常</option><option value="inactive">停用</option></select>
    <span class="result-count">共 {{ filteredMembers.length }} 位会员</span>
    <button class="primary-button toolbar__primary" type="button" @click="openCreate"><AppIcon name="plus" :size="18" /> 新增会员</button>
  </section>

  <section class="table-card glass-panel">
    <div v-if="filteredMembers.length" class="data-table-wrap">
      <table class="data-table member-table">
        <thead><tr><th>会员</th><th>联系方式</th><th>身份 ID</th><th>累计充值</th><th>手环时长</th><th>状态</th><th><span class="sr-only">操作</span></th></tr></thead>
        <tbody>
          <tr v-for="member in filteredMembers" :key="member.id">
            <td><button class="member-cell" type="button" @click="selectedMember = member"><span class="avatar" :style="{ background: member.color }">{{ member.initials }}</span><span><strong>{{ member.name }}</strong><small>{{ member.account }}</small></span></button></td>
            <td>{{ member.phone }}</td><td><code>{{ member.identityId }}</code></td><td><strong>¥{{ member.rechargeAmount.toLocaleString() }}</strong></td><td>{{ member.braceletMinutes }} 分钟</td>
            <td><StatusBadge :tone="member.status === 'active' ? 'success' : 'neutral'">{{ member.status === 'active' ? '正常' : '停用' }}</StatusBadge></td>
            <td><div class="row-actions"><button class="icon-button" type="button" aria-label="查看会员" @click="selectedMember = member"><AppIcon name="eye" :size="17" /></button><button class="icon-button" type="button" aria-label="模拟充值" @click="openRecharge(member)"><AppIcon name="wallet" :size="17" /></button><button class="icon-button" type="button" aria-label="编辑会员" @click="openEdit(member)"><AppIcon name="edit" :size="17" /></button></div></td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="empty-state empty-state--flat"><span><AppIcon name="search" :size="28" /></span><h2>没有找到会员</h2><p>检查查询条件，或新增一位会员。</p><button class="secondary-button" type="button" @click="search = ''; statusFilter = 'all'">清除筛选</button></div>
    <footer v-if="filteredMembers.length" class="table-footer"><span>显示 1–{{ filteredMembers.length }} 条，共 {{ filteredMembers.length }} 条</span><div class="pagination"><button disabled type="button">上一页</button><button class="active" type="button">1</button><button disabled type="button">下一页</button></div></footer>
  </section>

  <SideDrawer v-if="selectedMember" :title="selectedMember.name" :eyebrow="selectedMember.account" @close="selectedMember = null">
    <div class="member-hero"><span class="avatar avatar--large" :style="{ background: selectedMember.color }">{{ selectedMember.initials }}</span><div><h3>{{ selectedMember.name }}</h3><p>{{ selectedMember.account }}</p><StatusBadge :tone="selectedMember.status === 'active' ? 'success' : 'neutral'">{{ selectedMember.status === 'active' ? '正常会员' : '已停用' }}</StatusBadge></div></div>
    <section class="drawer-section"><div class="drawer-section__title"><h3>会员资料</h3></div><dl class="detail-grid"><div><dt>会员账号</dt><dd>{{ selectedMember.account }}</dd></div><div><dt>联系方式</dt><dd>{{ selectedMember.phone }}</dd></div><div><dt>身份 ID</dt><dd>{{ selectedMember.identityId }}</dd></div><div><dt>加入日期</dt><dd>{{ selectedMember.joinedAt }}</dd></div><div><dt>累计充值</dt><dd class="highlight-value">¥{{ selectedMember.rechargeAmount.toLocaleString() }}</dd></div><div><dt>手环时长</dt><dd>{{ selectedMember.braceletMinutes }} 分钟</dd></div></dl></section>
    <div class="notice-bar"><AppIcon name="sparkles" :size="18" /><div><strong>UI 演示模式</strong><p>资料修改与充值仅影响当前页面，刷新后恢复预置数据。</p></div></div>
    <template #footer><button class="danger-button" type="button" @click="deletingMember = selectedMember"><AppIcon name="trash" :size="17" /> 删除</button><button class="secondary-button" type="button" @click="openEdit(selectedMember)"><AppIcon name="edit" :size="17" /> 编辑资料</button><button class="primary-button" type="button" @click="openRecharge(selectedMember)"><AppIcon name="wallet" :size="17" /> 充值</button></template>
  </SideDrawer>

  <BaseModal v-if="editingMember" :title="editingMember.id ? '编辑会员资料' : '新增会员'" description="带 * 的字段为必填项，提交后仅更新当前演示数据。" size="large" @close="editingMember = null">
    <div class="form-grid">
      <label class="form-field"><span>会员账号 <b>*</b></span><input v-model="memberForm.account" @input="formError = ''" /></label>
      <label class="form-field"><span>会员姓名 <b>*</b></span><input v-model="memberForm.name" placeholder="请输入姓名" @input="formError = ''" /></label>
      <label class="form-field"><span>联系方式 <b>*</b></span><input v-model="memberForm.phone" placeholder="如：138 0000 0000" @input="formError = ''" /></label>
      <label class="form-field"><span>身份 ID <b>*</b></span><input v-model="memberForm.identityId" placeholder="请输入身份标识" @input="formError = ''" /></label>
      <label class="form-field"><span>手环时长（分钟） <b>*</b></span><input v-model.number="memberForm.braceletMinutes" type="number" min="1" max="1440" @input="formError = ''" /></label>
      <label class="form-field"><span>累计充值金额</span><input v-model.number="memberForm.rechargeAmount" type="number" min="0" /></label>
    </div>
    <p v-if="formError" class="form-error"><AppIcon name="alert" :size="16" /> {{ formError }}</p>
    <template #footer><button class="ghost-button" type="button" @click="editingMember = null">取消</button><button class="primary-button" type="button" @click="saveMember">{{ editingMember.id ? '保存修改' : '创建会员' }}</button></template>
  </BaseModal>

  <BaseModal v-if="rechargeMember" title="会员充值" :description="`${rechargeMember.name} · ${rechargeMember.account}`" size="small" @close="rechargeMember = null">
    <div class="balance-panel"><span>当前累计充值</span><strong>¥{{ rechargeMember.rechargeAmount.toLocaleString() }}</strong></div>
    <label class="form-field"><span>充值金额 <b>*</b></span><div class="money-input"><span>¥</span><input v-model.number="rechargeAmount" type="number" min="0.01" step="0.01" placeholder="0.00" autofocus @input="rechargeError = ''" /></div><small v-if="rechargeError" class="field-error">{{ rechargeError }}</small></label>
    <div class="quick-amounts"><button v-for="amount in [50, 100, 200, 500]" :key="amount" type="button" @click="rechargeAmount = amount">¥{{ amount }}</button></div>
    <div class="notice-bar notice-bar--warning"><AppIcon name="alert" :size="18" /><div><strong>仅为 UI 演示</strong><p>不会产生真实资金交易，也不会保存充值结果。</p></div></div>
    <template #footer><button class="ghost-button" type="button" @click="rechargeMember = null">取消</button><button class="primary-button" type="button" @click="submitRecharge">确认模拟充值</button></template>
  </BaseModal>

  <BaseModal v-if="deletingMember" title="确认删除会员？" :description="`${deletingMember.name} · ${deletingMember.account}`" size="small" @close="deletingMember = null">
    <div class="danger-confirm"><span><AppIcon name="trash" /></span><p>该操作会将会员从当前演示列表中移除。刷新页面后预置数据会恢复。</p></div>
    <template #footer><button class="ghost-button" type="button" @click="deletingMember = null">取消</button><button class="danger-button danger-button--solid" type="button" @click="confirmDelete">确认删除</button></template>
  </BaseModal>
</template>

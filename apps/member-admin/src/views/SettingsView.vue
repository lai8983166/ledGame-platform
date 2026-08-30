<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import type { OperatorAccount } from "@ledgame/platform-api-client";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import StatusBadge from "../components/StatusBadge.vue";
import DesktopRuntimeCard from "../components/DesktopRuntimeCard.vue";
import { createFeatureSettings } from "../data";
import type { FeatureSetting } from "../types";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { platformApi } from "../platformApi";
import { createOperatorAccountManager } from "../operatorAccountState";
import { memberAdminMessage } from "../localization";

type SettingsTab = "accounts" | "basic" | "features" | "upload";
const emit = defineEmits<{ toast: [message: string] }>();
const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);
const activeTab = ref<SettingsTab>("basic");
const braceletMinutes = ref<number | null>(60);
const savedMinutes = ref(60);
const durationError = ref("");
const features = ref(createFeatureSettings());
const pendingFeature = ref<FeatureSetting | null>(null);
const online = ref(true);
const targetType = ref<"email" | "server">("email");
const targetAddress = ref("ops@ledgame.example");
const automaticUpload = ref(false);
const uploadError = ref("");
const testing = ref(false);
const lastResult = ref("今日 14:30 · 模拟任务完成");
const accountManager = reactive(createOperatorAccountManager(platformApi));
const accountDialog = ref<"create" | "edit" | "password" | null>(null);
const selectedAccount = ref<OperatorAccount | null>(null);
const accountForm = reactive({ username: "", displayName: "", password: "" });

const openCreateAccount = () => {
  selectedAccount.value = null;
  Object.assign(accountForm, { username: "", displayName: "", password: "" });
  accountManager.error = "";
  accountDialog.value = "create";
};

const openEditAccount = (account: OperatorAccount) => {
  selectedAccount.value = account;
  Object.assign(accountForm, { username: account.username, displayName: account.displayName, password: "" });
  accountManager.error = "";
  accountDialog.value = "edit";
};

const openPasswordReset = (account: OperatorAccount) => {
  selectedAccount.value = account;
  accountForm.password = "";
  accountManager.error = "";
  accountDialog.value = "password";
};

const submitAccountDialog = async () => {
  let succeeded = false;
  if (accountDialog.value === "create") {
    succeeded = await accountManager.create({ ...accountForm });
  } else if (accountDialog.value === "edit" && selectedAccount.value) {
    succeeded = await accountManager.update(selectedAccount.value.id, {
      username: accountForm.username,
      displayName: accountForm.displayName,
    });
  } else if (accountDialog.value === "password" && selectedAccount.value) {
    succeeded = await accountManager.resetPassword(selectedAccount.value.id, accountForm.password);
  }
  if (succeeded) {
    accountDialog.value = null;
    emit("toast", "操作账号已更新");
  }
};

const toggleAccount = async (account: OperatorAccount) => {
  if (await accountManager.setEnabled(account.id, !account.enabled)) {
    emit("toast", `账号 ${account.username} 已${account.enabled ? "停用" : "启用"}`);
  }
};

onMounted(() => void accountManager.load());

const addressPlaceholder = computed(() => targetType.value === "email" ? "name@example.com" : "https://server.example.com/upload");

const saveDuration = () => {
  const value = Number(braceletMinutes.value);
  if (!Number.isInteger(value) || value < 1 || value > 1440) return void (durationError.value = "请输入 1–1440 之间的整数分钟数");
  savedMinutes.value = value;
  emit("toast", `常用充时快捷值已设为 ${value} 分钟（演示状态）`);
};

const requestFeatureToggle = (feature: FeatureSetting) => {
  if (feature.critical) pendingFeature.value = feature;
  else { feature.enabled = !feature.enabled; emit("toast", `${feature.name}已${feature.enabled ? '启用' : '关闭'}（演示状态）`); }
};

const confirmFeatureToggle = () => {
  if (!pendingFeature.value) return;
  pendingFeature.value.enabled = !pendingFeature.value.enabled;
  emit("toast", `${pendingFeature.value.name}已${pendingFeature.value.enabled ? '启用' : '关闭'}（演示状态）`);
  pendingFeature.value = null;
};

const switchTarget = (type: "email" | "server") => {
  targetType.value = type;
  targetAddress.value = type === "email" ? "ops@ledgame.example" : "https://api.ledgame.example/device/upload";
  uploadError.value = "";
};

const validateAddress = () => {
  const value = targetAddress.value.trim();
  if (targetType.value === "email" && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return "请输入格式正确的邮箱地址";
  if (targetType.value === "server" && !/^https:\/\/[^\s]+$/i.test(value)) return "服务器地址须为有效的 HTTPS 地址";
  return "";
};

const saveUpload = () => {
  uploadError.value = validateAddress();
  if (uploadError.value) return;
  emit("toast", "上传设置已保存至当前演示状态");
};

const testUpload = () => {
  uploadError.value = validateAddress();
  if (uploadError.value || !online.value) return;
  testing.value = true;
  window.setTimeout(() => {
    testing.value = false;
    lastResult.value = "刚刚 · 模拟连接测试成功";
    emit("toast", "模拟连接测试成功，未发送真实数据");
  }, 700);
};
</script>

<template>
  <section class="settings-layout">
    <nav class="settings-nav glass-panel" aria-label="设置分类">
      <button v-for="item in [{id:'accounts',label:'操作账号',icon:'members',desc:'创建与停用次级账号'}, {id:'basic',label:'基础设置',icon:'clock',desc:'手环与计时规则'}, {id:'features',label:'功能开关',icon:'settings',desc:'启用或关闭功能'}, {id:'upload',label:'数据上传',icon:'upload',desc:'邮箱与服务器目标'}]" :key="item.id" type="button" :class="{ active: activeTab === item.id }" @click="activeTab = item.id as SettingsTab"><span><AppIcon :name="item.icon" /></span><div><strong>{{ item.label }}</strong><small>{{ item.desc }}</small></div><AppIcon name="chevron" :size="16" /></button>
    </nav>

    <div class="settings-content">
      <DesktopRuntimeCard :locale="locale" @toast="emit('toast', $event)" />
      <section v-if="activeTab === 'accounts'" class="settings-card glass-panel" data-testid="operator-account-management">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="members" /></span><div><p class="section-eyebrow">{{ text("operatorAccountsEyebrow") }}</p><h2>{{ text("operatorAccountsTitle") }}</h2><p>{{ text("operatorAccountsDescription") }}</p></div><button class="primary-button" data-testid="operator-account-create" type="button" @click="openCreateAccount"><AppIcon name="plus" :size="17" />{{ text("operatorAccountsCreate") }}</button></header>
        <p v-if="accountManager.error" class="form-error" data-testid="operator-account-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
        <div class="data-table-wrap"><table class="data-table"><thead><tr><th>{{ text("operatorAccountColumnAccount") }}</th><th>{{ text("operatorAccountColumnDisplayName") }}</th><th>{{ text("operatorAccountColumnType") }}</th><th>{{ text("operatorAccountColumnStatus") }}</th><th>{{ text("operatorAccountColumnActions") }}</th></tr></thead><tbody><tr v-for="account in accountManager.accounts" :key="account.id" :data-testid="`operator-account-${account.id}`"><td><strong>{{ account.username }}</strong></td><td>{{ account.displayName }}</td><td><StatusBadge :tone="account.accountType === 'FACTORY_ADMIN' ? 'info' : 'neutral'">{{ account.accountType === "FACTORY_ADMIN" ? "出厂管理员" : "操作员" }}</StatusBadge></td><td><StatusBadge :tone="account.enabled ? 'success' : 'danger'">{{ account.enabled ? "已启用" : "已停用" }}</StatusBadge></td><td><div class="wristband-table-actions"><template v-if="account.accountType === 'OPERATOR'"><button class="secondary-button compact-button" type="button" @click="openEditAccount(account)">{{ text("operatorAccountEdit") }}</button><button class="secondary-button compact-button" type="button" @click="openPasswordReset(account)">{{ text("operatorAccountResetPassword") }}</button><button class="secondary-button compact-button" type="button" :disabled="accountManager.submitting" @click="toggleAccount(account)">{{ account.enabled ? "停用" : "启用" }}</button></template><button v-else class="secondary-button compact-button" type="button" @click="openPasswordReset(account)">修改自己的密码</button></div></td></tr><tr v-if="!accountManager.accounts.length"><td colspan="5">{{ accountManager.loading ? "正在读取账号…" : "暂无账号" }}</td></tr></tbody></table></div>
        <footer class="settings-actions"><button class="secondary-button" type="button" :disabled="accountManager.loading" @click="accountManager.load"><AppIcon name="refresh" :size="17" />刷新账号列表</button></footer>
      </section>

      <section v-else-if="activeTab === 'basic'" class="settings-card glass-panel">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="clock" /></span><div><p class="section-eyebrow">DURATION SHORTCUT</p><h2>常用充时快捷值</h2><p>仅作为柜台录入本次购买时长时的快捷选择，不会自动写入所有手环。</p></div><StatusBadge tone="info">常用 {{ savedMinutes }} 分钟</StatusBadge></header>
        <div class="duration-editor">
          <label class="form-field"><span>常用分钟数 <b>*</b></span><div class="duration-input"><input v-model.number="braceletMinutes" type="number" min="1" max="1440" @input="durationError = ''" /><span>分钟</span></div><small v-if="durationError" class="field-error">{{ durationError }}</small><small v-else>每只手环仍可在办理时输入不同分钟数</small></label>
          <div class="quick-amounts quick-amounts--duration"><button v-for="duration in [30, 45, 60, 90, 120]" :key="duration" type="button" :class="{ active: braceletMinutes === duration }" @click="braceletMinutes = duration; durationError = ''">{{ duration }} 分钟</button></div>
        </div>
        <div class="rule-flow">
          <div><span>1</span><section><strong>自助系统激活</strong><p>激活后，玩家可看到 {{ savedMinutes }} 分钟有效时长。</p></section></div><AppIcon name="arrow" />
          <div><span>2</span><section><strong>游戏系统首次刷卡</strong><p>首次刷卡进入游戏后，游戏时长才开始计算。</p></section></div><AppIcon name="arrow" />
          <div><span>3</span><section><strong>未开始则不计时</strong><p>仅激活但未开始游戏，有效游戏时长不生效。</p></section></div>
        </div>
        <div class="notice-bar"><AppIcon name="sparkles" :size="18" /><div><strong>计时规则说明</strong><p>分钟数属于具体手环的本次购买周期；绑定会员后仍不计时，首次游戏刷卡才开始。</p></div></div>
        <footer class="settings-actions"><button class="primary-button" type="button" @click="saveDuration">保存时长设置</button></footer>
      </section>

      <section v-else-if="activeTab === 'features'" class="settings-card glass-panel">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="settings" /></span><div><p class="section-eyebrow">FEATURE SWITCHES</p><h2>功能开关</h2><p>按门店运营需要启用或关闭对应功能。</p></div></header>
        <div class="feature-list">
          <div v-for="feature in features" :key="feature.id" class="feature-item"><span class="feature-item__icon"><AppIcon :name="feature.id === 'ranking-screen' ? 'monitor' : feature.id === 'auto-upload' ? 'upload' : feature.id === 'room-alert' ? 'bell' : 'card'" /></span><div><strong>{{ feature.name }}</strong><p>{{ feature.description }}</p><small v-if="feature.critical"><AppIcon name="alert" :size="13" /> 更改此项会影响主要流程</small></div><button class="switch-control" :class="{ active: feature.enabled }" type="button" role="switch" :aria-checked="feature.enabled" :aria-label="`${feature.enabled ? '关闭' : '启用'}${feature.name}`" @click="requestFeatureToggle(feature)"><span></span></button></div>
        </div>
        <div class="notice-bar"><AppIcon name="sparkles" :size="18" /><div><strong>演示设置</strong><p>开关只改变当前页面状态，刷新后恢复默认配置。</p></div></div>
      </section>

      <section v-else class="settings-card glass-panel">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="upload" /></span><div><p class="section-eyebrow">DATA UPLOAD</p><h2>数据上传</h2><p>联网后将相关硬件信息发送至指定目标。</p></div><button class="network-status" :class="{ offline: !online }" type="button" @click="online = !online"><span></span>{{ online ? '互联网已连接' : '当前离线' }}</button></header>
        <div class="upload-status-panel"><span class="upload-status-panel__icon"><AppIcon :name="online ? 'cloud' : 'alert'" :size="28" /></span><div><strong>{{ online ? '连接状态正常' : '网络连接已断开' }}</strong><p>{{ online ? '可进行模拟连接测试；不会发送任何真实数据。' : '恢复联网状态后才可测试上传目标。' }}</p></div><StatusBadge :tone="online ? 'success' : 'danger'">{{ online ? '在线' : '离线' }}</StatusBadge></div>
        <div class="target-picker"><p>上传目标类型</p><div><button type="button" :class="{ active: targetType === 'email' }" @click="switchTarget('email')"><span><AppIcon name="records" /></span><strong>指定邮箱</strong><small>发送硬件信息摘要</small></button><button type="button" :class="{ active: targetType === 'server' }" @click="switchTarget('server')"><span><AppIcon name="database" /></span><strong>服务器地址</strong><small>上传至 HTTPS 接口</small></button></div></div>
        <label class="form-field"><span>{{ targetType === 'email' ? '目标邮箱' : '服务器地址' }} <b>*</b></span><div class="address-input"><AppIcon :name="targetType === 'email' ? 'records' : 'cloud'" :size="17" /><input v-model="targetAddress" :placeholder="addressPlaceholder" :class="{ invalid: uploadError }" @input="uploadError = ''" /></div><small v-if="uploadError" class="field-error">{{ uploadError }}</small><small v-else>仅保存到当前演示状态，不会连接外部服务</small></label>
        <div class="feature-item feature-item--compact"><span class="feature-item__icon"><AppIcon name="refresh" /></span><div><strong>联网后自动上传</strong><p>检测到互联网连接时自动执行上传任务。</p></div><button class="switch-control" :class="{ active: automaticUpload }" type="button" role="switch" :aria-checked="automaticUpload" aria-label="联网后自动上传" @click="automaticUpload = !automaticUpload"><span></span></button></div>
        <div class="last-upload"><span><AppIcon name="check" :size="17" /></span><div><strong>最近上传结果</strong><p>{{ lastResult }}</p></div><small>演示结果</small></div>
        <footer class="settings-actions"><button class="secondary-button" type="button" :disabled="!online || testing" :title="!online ? '当前离线，无法测试' : ''" @click="testUpload"><AppIcon :name="testing ? 'refresh' : 'wifi'" :size="17" :class="{ spinning: testing }" /> {{ testing ? '测试中…' : '测试连接' }}</button><button class="primary-button" type="button" @click="saveUpload">保存上传设置</button></footer>
      </section>
    </div>
  </section>

  <BaseModal v-if="accountDialog" :title="accountDialog === 'create' ? '新建操作员' : accountDialog === 'edit' ? '编辑操作员' : '重设账号密码'" description="账号修改后对下一次登录立即生效。" size="small" @close="accountDialog = null">
    <div v-if="accountDialog === 'create' || accountDialog === 'edit'" class="form-grid">
      <label class="form-field"><span>用户名 <b>*</b></span><input v-model="accountForm.username" data-testid="operator-account-username" autocomplete="off" maxlength="32" @input="accountManager.error = ''" /></label>
      <label class="form-field"><span>显示名称 <b>*</b></span><input v-model="accountForm.displayName" data-testid="operator-account-display-name" maxlength="40" @input="accountManager.error = ''" /></label>
    </div>
    <label v-if="accountDialog === 'create' || accountDialog === 'password'" class="form-field"><span>{{ accountDialog === "create" ? "初始密码" : "新密码" }} <b>*</b></span><input v-model="accountForm.password" data-testid="operator-account-password" type="password" autocomplete="new-password" @input="accountManager.error = ''" /><small>6 到 72 个字符</small></label>
    <p v-if="accountManager.error" class="form-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
    <template #footer><button class="ghost-button" type="button" :disabled="accountManager.submitting" @click="accountDialog = null">取消</button><button class="primary-button" data-testid="operator-account-submit" type="button" :disabled="accountManager.submitting" @click="submitAccountDialog">{{ accountManager.submitting ? "提交中…" : "确认保存" }}</button></template>
  </BaseModal>

  <BaseModal v-if="pendingFeature" :title="`${pendingFeature.enabled ? '关闭' : '启用'}${pendingFeature.name}？`" description="这是会影响主要流程的功能开关。" size="small" @close="pendingFeature = null"><div class="notice-bar notice-bar--warning"><AppIcon name="alert" :size="18" /><div><strong>确认功能影响</strong><p>{{ pendingFeature.description }}</p></div></div><p class="modal-copy">本次操作只更新当前演示界面，不会修改真实系统配置。</p><template #footer><button class="ghost-button" type="button" @click="pendingFeature = null">取消</button><button class="primary-button" type="button" @click="confirmFeatureToggle">确认{{ pendingFeature.enabled ? '关闭' : '启用' }}</button></template></BaseModal>
</template>

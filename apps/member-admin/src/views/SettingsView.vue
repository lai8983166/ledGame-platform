<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import type { DatabaseBackupCandidate, DatabaseBackupStatus, OperatorAccount, OperatorAccountType, StoreSettings } from "@ledgame/platform-api-client";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import StatusBadge from "../components/StatusBadge.vue";
import DesktopRuntimeCard from "../components/DesktopRuntimeCard.vue";
import MemberLevelsSettings from "../components/MemberLevelsSettings.vue";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { platformApi } from "../platformApi";
import { createOperatorAccountManager } from "../operatorAccountState";
import { memberAdminMessage } from "../localization";
import { operatorSession } from "../operatorSession";
import { canUseOperatorCapability } from "../operatorPolicy";

type SettingsTab = "accounts" | "levels" | "password" | "basic" | "features" | "upload" | "backup";
const emit = defineEmits<{ toast: [message: string]; backupChanged: []; logoutRequired: [] }>();
const props = defineProps<{ locale: PlatformLocale; backupStatus: DatabaseBackupStatus | null }>();
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);
const role = computed(() => operatorSession.current.value?.accountType);
const activeTab = ref<SettingsTab>(props.backupStatus?.state === "MAINTENANCE_LOGIN_REQUIRED"
  ? "backup" : role.value === "CLERK" ? "features" : "accounts");
const braceletMinutes = ref<number | null>(60);
const savedMinutes = ref(60);
const durationError = ref("");
const storeSettings = ref<StoreSettings | null>(null);
const storeSettingsForm = reactive({ appTitle: "LED GAME MEMBER ADMIN", unitPriceYuan: "1.00", secondaryDisplayEnabled: false, iconImageBase64: "", iconImageMimeType: "", clearIcon: false });
const storeSettingsLoading = ref(false);
const storeSettingsSaving = ref(false);
const storeSettingsError = ref("");
const childMode = ref(false);
const featureLoading = ref(false);
const featureSaving = ref(false);
const featureError = ref("");
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
const accountForm = reactive<{ username: string; displayName: string; password: string; accountType: Exclude<OperatorAccountType, "FACTORY_ADMIN"> }>({
  username: "", displayName: "", password: "", accountType: "CLERK",
});
const selfPassword = ref("");
const pendingDeleteAccount = ref<OperatorAccount | null>(null);
const isFactory = computed(() => operatorSession.current.value?.accountType === "FACTORY_ADMIN");
const canManageFeatureSettings = computed(() => canUseOperatorCapability(operatorSession.current.value, "featureSettings"));
const canManageAccounts = computed(() => role.value === "FACTORY_ADMIN" || role.value === "STORE_MANAGER");
const backupTab = { id: "backup", label: "数据库", icon: "database", desc: "" } as const;
const settingsTabs = computed(() => props.backupStatus?.state === "MAINTENANCE_LOGIN_REQUIRED"
  ? (isFactory.value ? [backupTab] : [])
  : [
  ...(canManageAccounts.value ? [{ id: "accounts", label: "操作账号", icon: "members", desc: role.value === "STORE_MANAGER" ? "管理店员账号" : "管理店长与店员账号" }] : []),
  ...(canManageAccounts.value ? [{ id: "levels", label: text("memberLevels"), icon: "ranking", desc: "" }] : []),
  { id: "features", label: "功能开关", icon: "settings", desc: "启用或关闭功能" },
  { id: "password", label: "修改密码", icon: "settings", desc: "修改当前登录账号密码" },
  ...(canManageFeatureSettings.value ? [
    { id: "basic", label: "品牌与收费", icon: "settings", desc: "应用品牌、充值规则与副屏" },
  ] : []),
  ...(isFactory.value ? [backupTab] : []),
] as Array<{ id: SettingsTab; label: string; icon: string; desc: string }>);
const backupPath = ref("");
const backupLoading = ref(false);
const backupError = ref("");
const pendingImport = ref<DatabaseBackupCandidate | null>(null);

const formatBackupTime = (value?: string | null) => value
  ? new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "medium" }).format(new Date(value)) : "暂无";
const desktopAvailable = Boolean(window.memberAdminDesktop);
const loadBackupPath = async () => {
  if (!isFactory.value) return;
  backupError.value = "";
  try {
    const response = await platformApi.request<{ path: string }>("/api/database-backup/location");
    backupPath.value = response?.path || "";
  } catch (error) { backupError.value = error instanceof Error ? error.message : "无法读取备份路径"; }
};
const exportDatabase = async () => {
  const operator = operatorSession.current.value;
  if (!operator || !window.memberAdminDesktop?.exportDatabase || backupLoading.value) return;
  backupLoading.value = true;
  backupError.value = "";
  try {
    const result = await window.memberAdminDesktop.exportDatabase(operator.id);
    if (result) emit("toast", "数据库已导出");
  } catch (error) { backupError.value = error instanceof Error ? error.message : "数据库导出失败"; }
  finally { backupLoading.value = false; }
};

const chooseExternalBackup = async () => {
  const operator = operatorSession.current.value;
  if (!operator || !window.memberAdminDesktop?.chooseBackupDatabase) return;
  try {
    const candidate = await window.memberAdminDesktop.chooseBackupDatabase(operator.id);
    if (candidate) {
      pendingImport.value = candidate;
    }
  } catch (error) { backupError.value = error instanceof Error ? error.message : "所选数据库无效"; }
};

const cancelImportSelection = async () => {
  const candidate = pendingImport.value;
  pendingImport.value = null;
  if (candidate) {
    try { await platformApi.request(`/api/database-backup/candidates/${encodeURIComponent(candidate.candidateId)}/cancel`, { method: "POST" }); }
    catch (error) { backupError.value = error instanceof Error ? error.message : "临时备份清理失败"; }
  }
};

const executeImport = async () => {
  const operator = operatorSession.current.value;
  const candidate = pendingImport.value;
  if (!operator || !candidate || !window.memberAdminDesktop?.importBackupDatabase) return;
  backupLoading.value = true;
  backupError.value = "";
  try {
    await window.memberAdminDesktop.importBackupDatabase(candidate.candidateId, operator.id);
    pendingImport.value = null;
    emit("backupChanged");
    emit("logoutRequired");
  } catch (error) { backupError.value = error instanceof Error ? error.message : "数据库导入失败"; }
  finally { backupLoading.value = false; }
};

const openCreateAccount = () => {
  selectedAccount.value = null;
  Object.assign(accountForm, { username: "", displayName: "", password: "", accountType: "CLERK" });
  accountManager.error = "";
  accountDialog.value = "create";
};

const openEditAccount = (account: OperatorAccount) => {
  selectedAccount.value = account;
  Object.assign(accountForm, { username: account.username, displayName: account.displayName, password: "", accountType: account.accountType === "STORE_MANAGER" ? "STORE_MANAGER" : "CLERK" });
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

const deleteAccount = async () => {
  const account = pendingDeleteAccount.value;
  if (!account) return;
  if (await accountManager.remove(account.id)) {
    pendingDeleteAccount.value = null;
    emit("toast", `账号 ${account.username} 已删除`);
  }
};

const updateOwnPassword = async () => {
  const current = operatorSession.current.value;
  if (!current) return;
  if (await accountManager.resetPassword(current.id, selfPassword.value)) {
    selfPassword.value = "";
    emit("toast", "当前账号密码已修改");
  }
};

const loadFeatureSettings = async () => {
  featureLoading.value = true;
  featureError.value = "";
  try { childMode.value = (await platformApi.getFeatureSettings()).childMode; }
  catch (error) { featureError.value = error instanceof Error ? error.message : "功能设置加载失败"; }
  finally { featureLoading.value = false; }
};

const loadStoreSettings = async () => {
  if (!canManageFeatureSettings.value) return;
  storeSettingsLoading.value = true;
  storeSettingsError.value = "";
  try {
    const value = await platformApi.getStoreSettings();
    storeSettings.value = value;
    storeSettingsForm.appTitle = value.appTitle;
    storeSettingsForm.unitPriceYuan = (value.unitPriceCents / 100).toFixed(2);
    storeSettingsForm.secondaryDisplayEnabled = value.secondaryDisplayEnabled;
    storeSettingsForm.iconImageBase64 = "";
    storeSettingsForm.iconImageMimeType = "";
    storeSettingsForm.clearIcon = false;
    await window.memberAdminDesktop?.applyBranding?.({ secondaryDisplayEnabled: value.secondaryDisplayEnabled });
  } catch (error) { storeSettingsError.value = error instanceof Error ? error.message : "门店设置加载失败"; }
  finally { storeSettingsLoading.value = false; }
};

const toggleSecondaryDisplay = async () => {
  if (storeSettingsSaving.value) return;
  const enabled = !storeSettingsForm.secondaryDisplayEnabled;
  storeSettingsForm.secondaryDisplayEnabled = enabled;
  storeSettingsError.value = "";
  try {
    await window.memberAdminDesktop?.applyBranding?.({ secondaryDisplayEnabled: enabled });
    emit("toast", enabled ? "副屏已打开" : "副屏已关闭");
  } catch (error) {
    storeSettingsForm.secondaryDisplayEnabled = !enabled;
    storeSettingsError.value = error instanceof Error ? error.message : "副屏窗口操作失败";
  }
};

const selectBrandIcon = (event: Event) => {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (file.size > 256 * 1024) { storeSettingsError.value = "应用图标不能超过 256 KB"; input.value = ""; return; }
  const reader = new FileReader();
  reader.onload = () => {
    storeSettingsForm.iconImageBase64 = String(reader.result || "");
    storeSettingsForm.iconImageMimeType = file.type;
    storeSettingsForm.clearIcon = false;
    storeSettingsError.value = "";
  };
  reader.onerror = () => { storeSettingsError.value = "应用图标读取失败"; };
  reader.readAsDataURL(file);
};

const saveStoreSettings = async () => {
  const title = storeSettingsForm.appTitle.trim();
  const unit = Number(storeSettingsForm.unitPriceYuan);
  if (!title || title.length > 60) return void (storeSettingsError.value = "应用标题必须是 1 到 60 个字符");
  if (!Number.isFinite(unit) || unit <= 0 || unit > 10000) return void (storeSettingsError.value = "每分钟金额必须是 0 到 10000 元之间的正数");
  const cents = Math.round(unit * 100);
  if (cents < 1 || cents > 1_000_000) return void (storeSettingsError.value = "每分钟金额最多精确到分");
  storeSettingsSaving.value = true;
  storeSettingsError.value = "";
  try {
    const saved = await platformApi.updateStoreSettings({
      appTitle: title, unitPriceCents: cents, secondaryDisplayEnabled: storeSettingsForm.secondaryDisplayEnabled,
      iconImageBase64: storeSettingsForm.iconImageBase64 || null, iconImageMimeType: storeSettingsForm.iconImageMimeType || null,
      clearIcon: storeSettingsForm.clearIcon,
    });
    storeSettings.value = saved;
    storeSettingsForm.iconImageBase64 = "";
    storeSettingsForm.iconImageMimeType = "";
    storeSettingsForm.clearIcon = false;
    await window.memberAdminDesktop?.applyBranding?.({ title: saved.appTitle, iconDataUrl: saved.appIconDataUrl ?? null, secondaryDisplayEnabled: saved.secondaryDisplayEnabled });
    emit("toast", "品牌、收费和副屏设置已保存");
  } catch (error) { storeSettingsError.value = error instanceof Error ? error.message : "门店设置保存失败"; }
  finally { storeSettingsSaving.value = false; }
};

onMounted(() => {
  if (canManageAccounts.value) void accountManager.load();
  void loadFeatureSettings();
  void loadStoreSettings();
});
watch(activeTab, (tab) => { if (tab === "backup") void loadBackupPath(); }, { immediate: true });

const addressPlaceholder = computed(() => targetType.value === "email" ? "name@example.com" : "https://server.example.com/upload");

const saveDuration = () => {
  const value = Number(braceletMinutes.value);
  if (!Number.isInteger(value) || value < 1 || value > 1440) return void (durationError.value = "请输入 1–1440 之间的整数分钟数");
  savedMinutes.value = value;
  emit("toast", `常用充时快捷值已设为 ${value} 分钟（演示状态）`);
};

const toggleChildMode = async () => {
  if (featureLoading.value || featureSaving.value) return;
  const enabled = !childMode.value;
  featureSaving.value = true;
  featureError.value = "";
  try {
    childMode.value = (await platformApi.setChildMode(enabled)).childMode;
    emit("toast", `儿童模式已${childMode.value ? "开启" : "关闭"}`);
  } catch (error) {
    featureError.value = error instanceof Error ? error.message : "儿童模式保存失败";
  } finally { featureSaving.value = false; }
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
  <BaseModal v-if="pendingImport" v-bind="{ title: text('importModalTitle') }" description="这是覆盖当前主数据库的维护操作。" size="small" @close="cancelImportSelection">
    <p class="modal-copy">要将版本为 {{ pendingImport.revision }}，最后修改时间为 {{ formatBackupTime(pendingImport.lastBusinessModifiedAt) }} 的数据库导入吗？</p>
    <div class="backup-detail-grid"><div><small>{{ text('importFactoryUsername') }}</small><strong>{{ pendingImport.factoryAdminUsername }}</strong></div><div><small>{{ text('importMemberCount') }}</small><strong>{{ pendingImport.memberCount }}</strong></div></div>
    <div class="notice-bar notice-bar--warning"><AppIcon name="alert" :size="18" /><div><strong>{{ text('importOverwriteTitle') }}</strong><p>{{ text('importOverwriteBody') }}</p></div></div>
    <p class="modal-copy">{{ text('importAccountWarning') }}</p>
    <template #footer><button class="ghost-button" type="button" :disabled="backupLoading" @click="cancelImportSelection">取消</button><button class="primary-button" data-testid="database-import-confirm" type="button" :disabled="backupLoading" @click="executeImport">{{ backupLoading ? "正在导入…" : "确认覆盖并导入" }}</button></template>
  </BaseModal>

  <section class="settings-layout">
    <nav class="settings-nav glass-panel" aria-label="设置分类">
      <button v-for="item in settingsTabs" :key="item.id" :data-testid="`settings-tab-${item.id}`" type="button" :class="{ active: activeTab === item.id }" @click="activeTab = item.id"><span><AppIcon :name="item.icon" /></span><div><strong>{{ item.label }}</strong><small>{{ item.desc }}</small></div><AppIcon name="chevron" :size="16" /></button>
    </nav>

    <div class="settings-content">
      <DesktopRuntimeCard v-if="isFactory && activeTab !== 'backup'" :locale="locale" @toast="emit('toast', $event)" />
      <section v-if="activeTab === 'accounts'" class="settings-card glass-panel" data-testid="operator-account-management">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="members" /></span><div><p class="section-eyebrow">{{ text("operatorAccountsEyebrow") }}</p><h2>{{ text("operatorAccountsTitle") }}</h2><p>{{ text("operatorAccountsDescription") }}</p></div><button class="primary-button" data-testid="operator-account-create" type="button" @click="openCreateAccount"><AppIcon name="plus" :size="17" />{{ text("operatorAccountsCreate") }}</button></header>
        <p v-if="accountManager.error" class="form-error" data-testid="operator-account-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
        <div class="data-table-wrap"><table class="data-table"><thead><tr><th>{{ text("operatorAccountColumnAccount") }}</th><th>{{ text("operatorAccountColumnDisplayName") }}</th><th>{{ text("operatorAccountColumnType") }}</th><th>{{ text("operatorAccountColumnStatus") }}</th><th>{{ text("operatorAccountColumnActions") }}</th></tr></thead><tbody><tr v-for="account in accountManager.accounts" :key="account.id" :data-testid="`operator-account-${account.id}`"><td><strong>{{ account.username }}</strong></td><td>{{ account.displayName }}</td><td><StatusBadge :tone="account.accountType === 'FACTORY_ADMIN' ? 'info' : account.accountType === 'STORE_MANAGER' ? 'purple' : 'neutral'">{{ account.accountType === "FACTORY_ADMIN" ? "出厂管理员" : account.accountType === "STORE_MANAGER" ? "店长" : "店员" }}</StatusBadge></td><td><StatusBadge :tone="account.enabled ? 'success' : 'danger'">{{ account.enabled ? "已启用" : "已停用" }}</StatusBadge></td><td><div class="wristband-table-actions"><template v-if="account.accountType !== 'FACTORY_ADMIN'"><button class="secondary-button compact-button" type="button" @click="openEditAccount(account)">{{ text("operatorAccountEdit") }}</button><button class="secondary-button compact-button" type="button" @click="openPasswordReset(account)">{{ text("operatorAccountResetPassword") }}</button><button class="secondary-button compact-button" type="button" :disabled="accountManager.submitting" @click="toggleAccount(account)">{{ account.enabled ? "停用" : "启用" }}</button><button class="danger-button compact-button" type="button" @click="pendingDeleteAccount = account">删除</button></template></div></td></tr><tr v-if="!accountManager.accounts.length"><td colspan="5">{{ accountManager.loading ? "正在读取账号…" : "暂无下级账号" }}</td></tr></tbody></table></div>
        <footer class="settings-actions"><button class="secondary-button" type="button" :disabled="accountManager.loading" @click="accountManager.load"><AppIcon name="refresh" :size="17" />刷新账号列表</button></footer>
      </section>

      <MemberLevelsSettings v-else-if="activeTab === 'levels' && canManageAccounts" :locale="locale" :can-delete="isFactory" @toast="emit('toast', $event)" />
      <section v-else-if="activeTab === 'password'" class="settings-card glass-panel" data-testid="operator-own-password">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="settings" /></span><div><p class="section-eyebrow">ACCOUNT SECURITY</p><h2>修改自己的密码</h2><p>修改后下次登录使用新密码。</p></div></header>
        <label class="form-field"><span>新密码 <b>*</b></span><input v-model="selfPassword" data-testid="operator-own-password-input" type="password" autocomplete="new-password" /><small>6 到 72 个字符</small></label>
        <p v-if="accountManager.error" class="form-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
        <footer class="settings-actions"><button class="primary-button" data-testid="operator-own-password-submit" type="button" :disabled="accountManager.submitting" @click="updateOwnPassword">保存新密码</button></footer>
      </section>

      <section v-else-if="activeTab === 'basic'" class="settings-card glass-panel" data-testid="store-settings">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="settings" /></span><div><p class="section-eyebrow">STORE SETTINGS</p><h2>品牌、收费与副屏</h2><p>修改会员管理端运行时品牌、手环收款规则和副屏排行榜。</p></div><StatusBadge tone="info">{{ storeSettingsLoading ? '读取中…' : '已持久化' }}</StatusBadge></header>
        <div class="form-grid brand-payment-grid">
          <label class="form-field"><span>应用标题 <b>*</b></span><input v-model="storeSettingsForm.appTitle" data-testid="store-settings-title" maxlength="60" :disabled="storeSettingsSaving" /><small>只影响运行时窗口和界面，不修改 EXE 编译图标。</small></label>
          <label class="form-field"><span>每分钟收款金额（元） <b>*</b></span><input v-model="storeSettingsForm.unitPriceYuan" data-testid="store-settings-unit-price" type="number" min="0.01" max="10000" step="0.01" :disabled="storeSettingsSaving" /><small>充值金额 = 分钟数 × 当前单价。</small></label>
          <label class="form-field"><span>应用图标</span><input data-testid="store-settings-icon" type="file" accept="image/png,image/jpeg,image/webp" :disabled="storeSettingsSaving" @change="selectBrandIcon" /><small>{{ storeSettingsForm.iconImageBase64 ? '已选择新图标，保存后生效' : storeSettings?.appIconPath ? '已配置图标' : '默认图标' }}</small></label>
          <div class="feature-item feature-item--compact"><span class="feature-item__icon"><AppIcon name="overview" /></span><div><strong>副屏排行榜</strong><p>点击开关立即显示或隐藏；保存后下次启动继续沿用。</p></div><button data-testid="secondary-display-toggle" class="switch-control" :class="{ active: storeSettingsForm.secondaryDisplayEnabled }" type="button" role="switch" :aria-checked="storeSettingsForm.secondaryDisplayEnabled" :disabled="storeSettingsSaving" @click="toggleSecondaryDisplay"><span></span></button></div>
        </div>
        <p v-if="storeSettingsError" class="form-error" data-testid="store-settings-error"><AppIcon name="alert" :size="16" />{{ storeSettingsError }} <button class="secondary-button compact-button" type="button" @click="loadStoreSettings">重试</button></p>
        <footer class="settings-actions"><button class="secondary-button" type="button" :disabled="storeSettingsLoading || storeSettingsSaving" @click="loadStoreSettings"><AppIcon name="refresh" :size="17" />读取设置</button><button class="primary-button" data-testid="store-settings-save" type="button" :disabled="storeSettingsLoading || storeSettingsSaving" @click="saveStoreSettings">{{ storeSettingsSaving ? '保存中…' : '保存品牌与收费设置' }}</button></footer>
        <div class="rule-flow">
          <div><span>1</span><section><strong>常用充时快捷值</strong><p>继续作为柜台录入本次购买时长时的快捷选择。</p></section></div><AppIcon name="arrow" />
          <div><span>2</span><section><strong>读取当前收费规则</strong><p>服务端会按保存后的每分钟金额计算交易。</p></section></div><AppIcon name="arrow" />
          <div><span>3</span><section><strong>副屏同步排行榜</strong><p>副屏打开后按门店时区显示日、月、年榜。</p></section></div>
        </div>
        <hr class="settings-divider" />
        <header class="settings-card__header settings-card__header--compact"><span class="settings-icon"><AppIcon name="clock" /></span><div><p class="section-eyebrow">DURATION SHORTCUT</p><h2>常用充时快捷值</h2><p>仅作为柜台录入本次购买时长时的快捷选择，不会自动写入所有手环。</p></div><StatusBadge tone="info">常用 {{ savedMinutes }} 分钟</StatusBadge></header>
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
          <div class="feature-item" data-testid="child-mode-setting"><span class="feature-item__icon"><AppIcon name="card" /></span><div><strong>儿童模式</strong><p>开启后，所有已连接游戏端将隐藏不适合儿童或仅供测试的游戏。当前隐藏测试专用游戏。</p><small><AppIcon name="alert" :size="13" /> 设置会同步到在线游戏端，离线游戏端重连后自动生效</small></div><button data-testid="child-mode-toggle" class="switch-control" :class="{ active: childMode }" type="button" role="switch" :aria-checked="childMode" :aria-label="`${childMode ? '关闭' : '开启'}儿童模式`" :disabled="featureLoading || featureSaving" @click="toggleChildMode"><span></span></button></div>
        </div>
        <p v-if="featureLoading" class="empty-copy">正在读取功能设置…</p>
        <p v-if="featureError" class="form-error" role="alert"><AppIcon name="alert" :size="16" />{{ featureError }} <button class="secondary-button compact-button" type="button" @click="loadFeatureSettings">重试</button></p>
      </section>

      <section v-else-if="activeTab === 'upload'" class="settings-card glass-panel">
        <header class="settings-card__header"><span class="settings-icon"><AppIcon name="upload" /></span><div><p class="section-eyebrow">DATA UPLOAD</p><h2>数据上传</h2><p>联网后将相关硬件信息发送至指定目标。</p></div><button class="network-status" :class="{ offline: !online }" type="button" @click="online = !online"><span></span>{{ online ? '互联网已连接' : '当前离线' }}</button></header>
        <div class="upload-status-panel"><span class="upload-status-panel__icon"><AppIcon :name="online ? 'cloud' : 'alert'" :size="28" /></span><div><strong>{{ online ? '连接状态正常' : '网络连接已断开' }}</strong><p>{{ online ? '可进行模拟连接测试；不会发送任何真实数据。' : '恢复联网状态后才可测试上传目标。' }}</p></div><StatusBadge :tone="online ? 'success' : 'danger'">{{ online ? '在线' : '离线' }}</StatusBadge></div>
        <div class="target-picker"><p>上传目标类型</p><div><button type="button" :class="{ active: targetType === 'email' }" @click="switchTarget('email')"><span><AppIcon name="records" /></span><strong>指定邮箱</strong><small>发送硬件信息摘要</small></button><button type="button" :class="{ active: targetType === 'server' }" @click="switchTarget('server')"><span><AppIcon name="database" /></span><strong>服务器地址</strong><small>上传至 HTTPS 接口</small></button></div></div>
        <label class="form-field"><span>{{ targetType === 'email' ? '目标邮箱' : '服务器地址' }} <b>*</b></span><div class="address-input"><AppIcon :name="targetType === 'email' ? 'records' : 'cloud'" :size="17" /><input v-model="targetAddress" :placeholder="addressPlaceholder" :class="{ invalid: uploadError }" @input="uploadError = ''" /></div><small v-if="uploadError" class="field-error">{{ uploadError }}</small><small v-else>仅保存到当前演示状态，不会连接外部服务</small></label>
        <div class="feature-item feature-item--compact"><span class="feature-item__icon"><AppIcon name="refresh" /></span><div><strong>联网后自动上传</strong><p>检测到互联网连接时自动执行上传任务。</p></div><button class="switch-control" :class="{ active: automaticUpload }" type="button" role="switch" :aria-checked="automaticUpload" aria-label="联网后自动上传" @click="automaticUpload = !automaticUpload"><span></span></button></div>
        <div class="last-upload"><span><AppIcon name="check" :size="17" /></span><div><strong>最近上传结果</strong><p>{{ lastResult }}</p></div><small>演示结果</small></div>
        <footer class="settings-actions"><button class="secondary-button" type="button" :disabled="!online || testing" :title="!online ? '当前离线，无法测试' : ''" @click="testUpload"><AppIcon :name="testing ? 'refresh' : 'wifi'" :size="17" :class="{ spinning: testing }" /> {{ testing ? '测试中…' : '测试连接' }}</button><button class="primary-button" type="button" @click="saveUpload">保存上传设置</button></footer>
      </section>

      <section v-else class="settings-card glass-panel" data-testid="database-backup-management">
        <label class="form-field"><span>保存备份的路径</span><input :value="backupPath || '未找到可用异盘'" data-testid="database-backup-path" readonly /></label>
        <div class="settings-actions">
          <button class="secondary-button" data-testid="database-export" type="button" :disabled="backupLoading || !desktopAvailable" @click="exportDatabase"><AppIcon name="download" :size="17" />导出数据库</button>
          <button class="primary-button" data-testid="database-import" type="button" :disabled="backupLoading || !desktopAvailable" @click="chooseExternalBackup"><AppIcon name="upload" :size="17" />导入数据</button>
        </div>
        <p v-if="backupLoading" role="status">正在处理…</p>
        <p v-if="backupError" class="form-error" role="alert"><AppIcon name="alert" :size="16" />{{ backupError }}</p>
      </section>
    </div>
  </section>

  <BaseModal v-if="accountDialog" :title="accountDialog === 'create' ? '新建操作员' : accountDialog === 'edit' ? '编辑操作员' : '重设账号密码'" description="账号修改后对下一次登录立即生效。" size="small" @close="accountDialog = null">
    <div v-if="accountDialog === 'create' || accountDialog === 'edit'" class="form-grid">
      <label class="form-field"><span>用户名 <b>*</b></span><input v-model="accountForm.username" data-testid="operator-account-username" autocomplete="off" maxlength="32" @input="accountManager.error = ''" /></label>
      <label class="form-field"><span>显示名称 <b>*</b></span><input v-model="accountForm.displayName" data-testid="operator-account-display-name" maxlength="40" @input="accountManager.error = ''" /></label>
    </div>
    <label v-if="accountDialog === 'create' && isFactory" class="form-field"><span>账号角色 <b>*</b></span><select v-model="accountForm.accountType" data-testid="operator-account-role" class="select-control"><option value="STORE_MANAGER">店长</option><option value="CLERK">店员</option></select></label>
    <label v-if="accountDialog === 'create' || accountDialog === 'password'" class="form-field"><span>{{ accountDialog === "create" ? "初始密码" : "新密码" }} <b>*</b></span><input v-model="accountForm.password" data-testid="operator-account-password" type="password" autocomplete="new-password" @input="accountManager.error = ''" /><small>6 到 72 个字符</small></label>
    <p v-if="accountManager.error" class="form-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
    <template #footer><button class="ghost-button" type="button" :disabled="accountManager.submitting" @click="accountDialog = null">取消</button><button class="primary-button" data-testid="operator-account-submit" type="button" :disabled="accountManager.submitting" @click="submitAccountDialog">{{ accountManager.submitting ? "提交中…" : "确认保存" }}</button></template>
  </BaseModal>

  <BaseModal v-if="pendingDeleteAccount" title="删除操作账号" description="账号将立即无法登录，历史操作记录会保留。" size="small" @close="pendingDeleteAccount = null">
    <p class="modal-copy">确认删除账号 {{ pendingDeleteAccount.displayName }}（{{ pendingDeleteAccount.username }}）吗？</p>
    <p v-if="accountManager.error" class="form-error"><AppIcon name="alert" :size="16" />{{ accountManager.error }}</p>
    <template #footer><button class="ghost-button" type="button" @click="pendingDeleteAccount = null">取消</button><button class="danger-button danger-button--solid" data-testid="operator-account-delete-confirm" type="button" :disabled="accountManager.submitting" @click="deleteAccount">确认删除</button></template>
  </BaseModal>

</template>

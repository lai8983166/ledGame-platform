<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "./components/AppIcon.vue";
import ToastMessage from "./components/ToastMessage.vue";
import WristbandsView from "./views/WristbandsView.vue";
import DashboardView from "./views/DashboardView.vue";
import LeaderboardView from "./views/LeaderboardView.vue";
import MembersView from "./views/MembersView.vue";
import RecordsView from "./views/RecordsView.vue";
import RoomsView from "./views/RoomsView.vue";
import SettingsView from "./views/SettingsView.vue";
import LoginView from "./views/LoginView.vue";
import type { DatabaseBackupStatus, OperatorProfile } from "@ledgame/platform-api-client";
import { operatorSession } from "./operatorSession";
import { platformApi } from "./platformApi";
import { canUseOperatorCapability } from "./operatorPolicy";
import type { PageId } from "./types";
import {
  PLATFORM_LOCALES,
  applyDocumentLocale,
  persistLocale,
  readStoredLocale,
  type PlatformLocale,
} from "@ledgame/platform-shared-ui";
import {
  MEMBER_ADMIN_LOCALE_STORAGE_KEY,
  memberAdminCatalogs,
  type MemberAdminMessageKey,
} from "./localization";
import { localeFlagUrls } from "./localeFlags";

const activePage = ref<PageId>("wristbands");
const mobileNavOpen = ref(false);
const languageOpen = ref(false);
const locale = ref<PlatformLocale>(readStoredLocale(window.localStorage, MEMBER_ADMIN_LOCALE_STORAGE_KEY));
const toastMessage = ref("");
const currentOperator = operatorSession.current;
let toastTimer: number | undefined;
let backupPollTimer: number | undefined;
const backupStatus = ref<DatabaseBackupStatus | null>(null);
const concurrencyTestRunId = ref<string | null>(null);

const copy = computed(() => memberAdminCatalogs[locale.value]);
const text = (key: MemberAdminMessageKey) => copy.value[key];
const navDefinitions: Array<{ id: PageId; labelKey: MemberAdminMessageKey; icon: string }> = [
  { id: "wristbands", labelKey: "navWristbands", icon: "card" },
  { id: "overview", labelKey: "navOverview", icon: "overview" },
  { id: "rooms", labelKey: "navRooms", icon: "rooms" },
  { id: "members", labelKey: "navMembers", icon: "members" },
  { id: "records", labelKey: "navRecords", icon: "records" },
  { id: "ranking", labelKey: "navRanking", icon: "ranking" },
  { id: "settings", labelKey: "navSettings", icon: "settings" },
];
const descriptionKeys: Record<PageId, MemberAdminMessageKey> = {
  wristbands: "descWristbands",
  overview: "descOverview",
  rooms: "descRooms",
  members: "descMembers",
  records: "descRecords",
  ranking: "descRanking",
  settings: "descSettings",
};
const maintenanceMode = computed(() => backupStatus.value?.state === "MAINTENANCE_LOGIN_REQUIRED");
const navItems = computed(() => navDefinitions
  .filter((item) => !maintenanceMode.value || item.id === "settings")
  .filter((item) => item.id !== "settings" || canUseOperatorCapability(currentOperator.value, "settings"))
  .map((item) => ({ ...item, label: text(item.labelKey) })));
const currentMeta = computed(() => ({
  title: navItems.value.find((item) => item.id === activePage.value)?.label ?? "",
  description: text(descriptionKeys[activePage.value]),
}));

const selectLocale = (value: PlatformLocale) => {
  locale.value = persistLocale(window.localStorage, MEMBER_ADMIN_LOCALE_STORAGE_KEY, value);
  applyDocumentLocale(document.documentElement, locale.value);
  languageOpen.value = false;
};

const navigate = (page: PageId) => {
  activePage.value = page;
  mobileNavOpen.value = false;
  window.scrollTo({ top: 0, behavior: "smooth" });
};

const showToast = (message: string) => {
  toastMessage.value = message;
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (toastMessage.value = ""), 2600);
};

const refreshBackupStatus = async () => {
  if (!currentOperator.value) return;
  try { backupStatus.value = await platformApi.getDatabaseBackupStatus(); }
  catch { /* The runtime card and backend errors provide the retry path. */ }
};

const completeLogin = async (profile: OperatorProfile) => {
  operatorSession.login(profile);
  await refreshBackupStatus();
  activePage.value = maintenanceMode.value ? "settings" : "wristbands";
  mobileNavOpen.value = false;
  languageOpen.value = false;
};

const logout = () => {
  activePage.value = "wristbands";
  mobileNavOpen.value = false;
  languageOpen.value = false;
  toastMessage.value = "";
  operatorSession.logout();
  backupStatus.value = null;
};

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    mobileNavOpen.value = false;
    languageOpen.value = false;
  }
};

onMounted(() => {
  applyDocumentLocale(document.documentElement, locale.value);
  window.addEventListener("keydown", onKeydown);
  backupPollTimer = window.setInterval(() => void refreshBackupStatus(), 5000);
  void window.memberAdminDesktop?.diagnostics().then((value) => {
    concurrencyTestRunId.value = value.concurrencyTestMode ? value.concurrencyTestRunId ?? null : null;
  });
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKeydown);
  if (toastTimer) window.clearTimeout(toastTimer);
  if (backupPollTimer) window.clearInterval(backupPollTimer);
});
</script>

<template>
  <div v-if="concurrencyTestRunId" class="concurrency-test-banner" data-testid="concurrency-test-banner">
    并发测试模式 · {{ concurrencyTestRunId }}
  </div>
  <div class="app-surface" aria-hidden="true">
    <span class="ambient ambient--one"></span>
    <span class="ambient ambient--two"></span>
    <span class="ambient ambient--three"></span>
  </div>

  <LoginView v-if="!currentOperator" :locale="locale" @authenticated="completeLogin" />

  <div v-else class="admin-layout" data-testid="operator-authenticated-app">
    <div v-if="mobileNavOpen" class="mobile-nav-backdrop" aria-hidden="true" @click="mobileNavOpen = false"></div>
    <aside class="sidebar glass-panel" :class="{ 'sidebar--open': mobileNavOpen }">
      <div class="brand">
        <div class="brand__mark" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
        <div>
          <strong>LED GAME</strong>
          <small>MEMBER ADMIN</small>
        </div>
        <button class="icon-button sidebar__close" type="button" :aria-label="text('closeNavigation')" @click="mobileNavOpen = false">
          <AppIcon name="close" />
        </button>
      </div>

      <nav class="main-nav" aria-label="主要功能">
        <p class="nav-label">{{ text('mainFunctions') }}</p>
        <button
          v-for="item in navItems"
          :key="item.id"
          :data-testid="`admin-nav-${item.id}`"
          class="nav-item"
          :class="{ 'nav-item--active': activePage === item.id }"
          type="button"
          :aria-current="activePage === item.id ? 'page' : undefined"
          @click="navigate(item.id)"
        >
          <span class="nav-item__icon"><AppIcon :name="item.icon" /></span>
          <span>{{ item.label }}</span>
        </button>
      </nav>

      <div class="sidebar__status">
        <div class="admin-profile">
          <span class="avatar avatar--admin">{{ currentOperator.displayName.slice(0, 2).toUpperCase() }}</span>
          <div><strong data-testid="current-operator-name">{{ currentOperator.displayName }}</strong><small>{{ currentOperator.username }}</small></div>
          <button class="icon-button" data-testid="operator-logout" type="button" :aria-label="text('logout')" @click="logout"><AppIcon name="logout" :size="18" /></button>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div class="topbar__title">
          <button class="icon-button mobile-menu" type="button" :aria-label="text('openNavigation')" :aria-expanded="mobileNavOpen" @click="mobileNavOpen = true">
            <AppIcon name="menu" />
          </button>
          <div>
            <h1>{{ currentMeta.title }}</h1>
            <p>{{ currentMeta.description }}</p>
          </div>
        </div>
        <div class="topbar__actions">
          <div class="language-switcher">
            <button
              class="language-button"
              type="button"
              :aria-label="text('chooseLanguage')"
              :aria-expanded="languageOpen"
              @click="languageOpen = !languageOpen"
            >
              <span aria-hidden="true">🌐</span>
              <strong>{{ PLATFORM_LOCALES.find((item) => item.code === locale)?.label }}</strong>
              <small>{{ locale }}</small>
            </button>
            <div v-if="languageOpen" class="language-popover glass-panel" role="dialog" :aria-label="text('chooseLanguage')">
              <header><strong>{{ text('chooseLanguage') }}</strong><button type="button" :aria-label="text('close')" @click="languageOpen = false">×</button></header>
              <div class="language-list" role="listbox" :aria-label="text('chooseLanguage')">
                <button
                  v-for="item in PLATFORM_LOCALES"
                  :key="item.code"
                  type="button"
                  :class="{ active: item.code === locale }"
                  :aria-selected="item.code === locale"
                  role="option"
                  @click="selectLocale(item.code)"
                ><img :src="localeFlagUrls[item.flagCode]" alt="" /><strong>{{ item.label }}</strong><small>{{ item.code }}</small></button>
              </div>
            </div>
          </div>
        </div>
      </header>

      <div v-if="backupStatus && !backupStatus.protectedData" class="backup-warning" data-testid="database-backup-warning">
        <AppIcon name="alert" :size="18" />
        <div><strong>{{ maintenanceMode ? "数据库需要出厂账号处理" : "数据库异盘备份当前不可用" }}</strong><p>{{ backupStatus.message }}</p></div>
        <button v-if="currentOperator.accountType === 'FACTORY_ADMIN'" type="button" @click="navigate('settings')">{{ text('backupWarningDetail') }}</button>
      </div>

      <div class="page-stage" :data-testid="`admin-page-${activePage}`">
        <section v-if="maintenanceMode && currentOperator.accountType !== 'FACTORY_ADMIN'" class="settings-card glass-panel">
          <div class="notice-bar notice-bar--warning"><AppIcon name="alert" :size="18" /><div><strong>{{ text('maintenanceOnlyTitle') }}</strong><p>{{ text('maintenanceOnlyBody') }}</p></div></div>
        </section>
        <WristbandsView v-else-if="activePage === 'wristbands'" :locale="locale" @toast="showToast" />
        <DashboardView v-else-if="activePage === 'overview'" :locale="locale" @navigate="navigate" />
        <RoomsView v-else-if="activePage === 'rooms'" :locale="locale" @toast="showToast" />
        <MembersView v-else-if="activePage === 'members'" :locale="locale" @toast="showToast" />
        <RecordsView v-else-if="activePage === 'records'" :locale="locale" />
        <LeaderboardView v-else-if="activePage === 'ranking'" :locale="locale" />
        <SettingsView v-else :locale="locale" :backup-status="backupStatus" @backup-changed="refreshBackupStatus" @logout-required="logout" @toast="showToast" />
      </div>
    </main>
  </div>

  <div v-if="toastMessage" data-testid="admin-toast"><ToastMessage :message="toastMessage" /></div>
</template>

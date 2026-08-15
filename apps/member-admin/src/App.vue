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
let toastTimer: number | undefined;

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
const navItems = computed(() => navDefinitions.map((item) => ({ ...item, label: text(item.labelKey) })));
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

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") {
    mobileNavOpen.value = false;
    languageOpen.value = false;
  }
};

onMounted(() => {
  applyDocumentLocale(document.documentElement, locale.value);
  window.addEventListener("keydown", onKeydown);
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKeydown);
  if (toastTimer) window.clearTimeout(toastTimer);
});
</script>

<template>
  <div class="app-surface" aria-hidden="true">
    <span class="ambient ambient--one"></span>
    <span class="ambient ambient--two"></span>
    <span class="ambient ambient--three"></span>
  </div>

  <div class="admin-layout">
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
          <span v-if="item.id === 'rooms'" class="nav-item__count">3</span>
        </button>
      </nav>

      <div class="sidebar__status">
        <div class="connection-card">
          <span class="connection-card__icon"><AppIcon name="cloud" :size="19" /></span>
          <div><strong>{{ text('systemHealthy') }}</strong><small><i></i> {{ text('roomsConnected') }}</small></div>
        </div>
        <div class="admin-profile">
          <span class="avatar avatar--admin">AD</span>
          <div><strong>{{ text('storeManager') }}</strong><small>LED GAME</small></div>
          <button class="icon-button" type="button" aria-label="退出演示账号"><AppIcon name="logout" :size="18" /></button>
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
          <div class="live-chip"><span></span> {{ text('demoData') }}</div>
          <button class="icon-button notification-button" type="button" :aria-label="text('notifications')">
            <AppIcon name="bell" />
            <span class="notification-button__dot"></span>
          </button>
          <div class="today"><strong>08月02日</strong><small>星期日 · 15:36</small></div>
        </div>
      </header>

      <div class="page-stage" :data-testid="`admin-page-${activePage}`">
        <WristbandsView v-if="activePage === 'wristbands'" @toast="showToast" />
        <DashboardView v-else-if="activePage === 'overview'" @navigate="navigate" />
        <RoomsView v-else-if="activePage === 'rooms'" @toast="showToast" />
        <MembersView v-else-if="activePage === 'members'" :locale="locale" @toast="showToast" />
        <RecordsView v-else-if="activePage === 'records'" :locale="locale" />
        <LeaderboardView v-else-if="activePage === 'ranking'" />
        <SettingsView v-else :locale="locale" @toast="showToast" />
      </div>
    </main>
  </div>

  <div v-if="toastMessage" data-testid="admin-toast"><ToastMessage :message="toastMessage" /></div>
</template>

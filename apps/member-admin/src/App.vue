<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "./components/AppIcon.vue";
import ToastMessage from "./components/ToastMessage.vue";
import DashboardView from "./views/DashboardView.vue";
import LeaderboardView from "./views/LeaderboardView.vue";
import MembersView from "./views/MembersView.vue";
import RecordsView from "./views/RecordsView.vue";
import RoomsView from "./views/RoomsView.vue";
import SettingsView from "./views/SettingsView.vue";
import type { PageId } from "./types";

const navItems: Array<{ id: PageId; label: string; icon: string }> = [
  { id: "overview", label: "运营总览", icon: "overview" },
  { id: "rooms", label: "房间管理", icon: "rooms" },
  { id: "members", label: "会员管理", icon: "members" },
  { id: "records", label: "记录与数据", icon: "records" },
  { id: "ranking", label: "副屏排行", icon: "ranking" },
  { id: "settings", label: "系统设置", icon: "settings" },
];

const pageMeta: Record<PageId, { title: string; description: string }> = {
  overview: { title: "运营总览", description: "门店今天的关键数据与实时状态" },
  rooms: { title: "房间管理", description: "掌握房间进度、临时积分与硬件状态" },
  members: { title: "会员管理", description: "查询和维护会员资料与充值信息" },
  records: { title: "记录与数据", description: "集中查看发卡、游玩、交易与游戏数据" },
  ranking: { title: "副屏排行", description: "预览日、月、年度积分排行榜" },
  settings: { title: "系统设置", description: "配置手环规则、功能开关与数据上传" },
};

const activePage = ref<PageId>("overview");
const mobileNavOpen = ref(false);
const toastMessage = ref("");
let toastTimer: number | undefined;

const currentMeta = computed(() => pageMeta[activePage.value]);

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
  if (event.key === "Escape") mobileNavOpen.value = false;
};

onMounted(() => window.addEventListener("keydown", onKeydown));
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
        <button class="icon-button sidebar__close" type="button" aria-label="关闭导航" @click="mobileNavOpen = false">
          <AppIcon name="close" />
        </button>
      </div>

      <nav class="main-nav" aria-label="主要功能">
        <p class="nav-label">管理中心</p>
        <button
          v-for="item in navItems"
          :key="item.id"
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
          <div><strong>系统运行正常</strong><small><i></i> 6 个房间已连接</small></div>
        </div>
        <div class="admin-profile">
          <span class="avatar avatar--admin">AD</span>
          <div><strong>门店管理员</strong><small>上海旗舰店</small></div>
          <button class="icon-button" type="button" aria-label="退出演示账号"><AppIcon name="logout" :size="18" /></button>
        </div>
      </div>
    </aside>

    <main class="main-content">
      <header class="topbar">
        <div class="topbar__title">
          <button class="icon-button mobile-menu" type="button" aria-label="打开导航" :aria-expanded="mobileNavOpen" @click="mobileNavOpen = true">
            <AppIcon name="menu" />
          </button>
          <div>
            <h1>{{ currentMeta.title }}</h1>
            <p>{{ currentMeta.description }}</p>
          </div>
        </div>
        <div class="topbar__actions">
          <div class="live-chip"><span></span> 演示数据</div>
          <button class="icon-button notification-button" type="button" aria-label="查看通知">
            <AppIcon name="bell" />
            <span class="notification-button__dot"></span>
          </button>
          <div class="today"><strong>08月02日</strong><small>星期日 · 15:36</small></div>
        </div>
      </header>

      <div class="page-stage">
        <DashboardView v-if="activePage === 'overview'" @navigate="navigate" />
        <RoomsView v-else-if="activePage === 'rooms'" @toast="showToast" />
        <MembersView v-else-if="activePage === 'members'" @toast="showToast" />
        <RecordsView v-else-if="activePage === 'records'" />
        <LeaderboardView v-else-if="activePage === 'ranking'" />
        <SettingsView v-else @toast="showToast" />
      </div>
    </main>
  </div>

  <ToastMessage v-if="toastMessage" :message="toastMessage" />
</template>

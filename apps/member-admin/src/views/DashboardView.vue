<script setup lang="ts">
import type { DashboardOverview } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { memberAdminMessage } from "../localization";
import { platformApi } from "../platformApi";
import { mapRoomStatus } from "../roomStatus";
import type { PageId, Room, StatusTone } from "../types";

const props = defineProps<{ locale: PlatformLocale }>();
const emit = defineEmits<{ navigate: [page: PageId] }>();
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);

const overview = ref<DashboardOverview>({ totalMembers: 0, newMembersToday: 0, wristbandsChargedToday: 0, revenueTodayCents: 0, periodStart: "", periodEnd: "", generatedAt: "" });
const rooms = ref<Room[]>([]);
const loading = ref(false);
const loadError = ref(false);

const roomCounts = computed(() => ({
  playing: rooms.value.filter((room) => room.online && room.status === "playing").length,
  online: rooms.value.filter((room) => room.online).length,
}));
const formatCurrency = (cents: number) => new Intl.NumberFormat(props.locale, { style: "currency", currency: "CNY" }).format(cents / 100);
const stats = computed<Array<{ testId: string; label: string; value: string; icon: string; tone: StatusTone; helper: string }>>(() => [
  { testId: "admin-dashboard-total-members", label: text("dashboardTotalMembers"), value: overview.value.totalMembers.toLocaleString(), icon: "members", tone: "info", helper: text("dashboardActiveMembers") },
  { testId: "admin-dashboard-new-members-today", label: text("dashboardNewMembers"), value: overview.value.newMembersToday.toLocaleString(), icon: "sparkles", tone: "purple", helper: text("dashboardToday") },
  { testId: "admin-dashboard-wristbands-charged-today", label: text("dashboardChargedWristbands"), value: overview.value.wristbandsChargedToday.toLocaleString(), icon: "card", tone: "success", helper: text("dashboardSuccessfulCharges") },
  { testId: "admin-dashboard-revenue-today", label: text("dashboardRevenue"), value: formatCurrency(overview.value.revenueTodayCents), icon: "wallet", tone: "warning", helper: text("dashboardRevenueRule") },
]);

async function loadDashboard() {
  loading.value = true;
  loadError.value = false;
  const [overviewResult, roomsResult] = await Promise.allSettled([platformApi.getDashboardOverview(), platformApi.listRooms()]);
  if (overviewResult.status === "fulfilled") overview.value = overviewResult.value;
  if (roomsResult.status === "fulfilled") rooms.value = roomsResult.value.map(mapRoomStatus);
  loadError.value = overviewResult.status === "rejected" || roomsResult.status === "rejected";
  loading.value = false;
}

onMounted(() => void loadDashboard());
</script>

<template>
  <section class="welcome-strip glass-panel">
    <div>
      <p class="section-eyebrow"><AppIcon name="sparkles" :size="15" /> {{ text("dashboardEyebrow") }}</p>
      <p>{{ text("dashboardRoomPrefix") }} {{ roomCounts.playing }} {{ text("dashboardPlayingRooms") }}，{{ roomCounts.online }} {{ text("dashboardOnlineRooms") }}。</p>
    </div>
    <div class="welcome-strip__actions">
      <button data-testid="admin-dashboard-refresh" class="secondary-button dashboard-refresh-button" type="button" :disabled="loading" @click="loadDashboard">
        <AppIcon name="refresh" :size="17" /> {{ loading ? text("dashboardRefreshing") : text("dashboardRefresh") }}
      </button>
      <div class="welcome-strip__visual" aria-hidden="true">
        <div class="orbital orbital--one"></div><div class="orbital orbital--two"></div>
        <div class="pulse-core"><AppIcon name="game" :size="36" /></div>
      </div>
    </div>
  </section>

  <div v-if="loadError" data-testid="admin-dashboard-error" class="inline-error dashboard-error">{{ text("dashboardError") }}</div>

  <section class="stat-grid" :aria-label="text('dashboardCoreStats')">
    <article v-for="stat in stats" :key="stat.testId" :data-testid="stat.testId" class="stat-card glass-panel">
      <div class="stat-card__top">
        <span class="metric-icon" :class="`metric-icon--${stat.tone}`"><AppIcon :name="stat.icon" /></span>
      </div>
      <p>{{ stat.label }}</p><strong>{{ stat.value }}</strong><small>{{ stat.helper }}</small>
    </article>
  </section>

  <div class="dashboard-grid dashboard-grid--single">
    <section class="content-card glass-panel">
      <header class="card-header">
        <div><p class="section-eyebrow">ROOM STATUS</p><h2>{{ text("dashboardRoomStatus") }}</h2></div>
        <button class="text-button" type="button" @click="emit('navigate', 'rooms')">{{ text("dashboardViewAll") }} <AppIcon name="arrow" :size="16" /></button>
      </header>
      <div v-if="rooms.length" class="room-summary-list">
        <button v-for="(room, index) in rooms.slice(0, 6)" :key="room.id" class="room-summary" type="button" @click="emit('navigate', 'rooms')">
          <span class="room-summary__number">{{ String(index + 1).padStart(2, "0") }}</span>
          <span class="room-summary__info"><strong>{{ room.name }}</strong><small>{{ room.ip }} · {{ room.status === 'playing' ? room.gameName || text("dashboardInGame") : text("dashboardWaiting") }}</small></span>
          <StatusBadge :tone="!room.online ? 'danger' : room.status === 'playing' ? 'purple' : 'success'">{{ !room.online ? text("dashboardOffline") : room.status === 'playing' ? text("dashboardInGame") : text("dashboardIdle") }}</StatusBadge>
          <span class="room-summary__time" :class="{ 'room-summary__time--idle': room.status !== 'playing' }">{{ text("dashboardQueue") }} {{ room.queueLength || 0 }}</span>
          <AppIcon name="chevron" :size="16" />
        </button>
      </div>
      <div v-else data-testid="admin-dashboard-empty-rooms" class="empty-state dashboard-empty-state">
        <AppIcon name="rooms" :size="28" /><strong>{{ text("dashboardNoRoomsTitle") }}</strong><p>{{ text("dashboardNoRoomsBody") }}</p>
      </div>
    </section>
  </div>
</template>

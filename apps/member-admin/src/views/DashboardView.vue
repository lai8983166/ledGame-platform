<script setup lang="ts">
import type { DashboardOverview } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import { memberAdminMessage } from "../localization";
import { platformApi } from "../platformApi";
import type { StatusTone } from "../types";

const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);

const overview = ref<DashboardOverview>({ totalMembers: 0, newMembersToday: 0, wristbandsChargedToday: 0, revenueTodayCents: 0, periodStart: "", periodEnd: "", generatedAt: "" });
const loading = ref(false);
const loadError = ref(false);

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
  try { overview.value = await platformApi.getDashboardOverview(); }
  catch { loadError.value = true; }
  finally { loading.value = false; }
}

onMounted(() => void loadDashboard());
</script>

<template>
  <section class="toolbar glass-panel">
      <button data-testid="admin-dashboard-refresh" class="secondary-button dashboard-refresh-button" type="button" :disabled="loading" @click="loadDashboard">
        <AppIcon name="refresh" :size="17" /> {{ loading ? text("dashboardRefreshing") : text("dashboardRefresh") }}
      </button>
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

</template>

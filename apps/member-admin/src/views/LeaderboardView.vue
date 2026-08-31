<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import type { LeaderboardEntry, LeaderboardPeriod } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import AppIcon from "../components/AppIcon.vue";
import { platformApi } from "../platformApi";
import { createLeaderboardState, loadLeaderboard } from "../leaderboardState";
import { memberAdminMessage, type MemberAdminMessageKey } from "../localization";

const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: MemberAdminMessageKey) => memberAdminMessage(props.locale, key);

const state = reactive(createLeaderboardState());
const preview = ref(false);
const secondaryScreenPreviewEnabled = false;
const colors = ["#18b6a4", "#5b7cff", "#9b6dff", "#ff8a65", "#31a6d8", "#e06ca8"];
const periodItems: Array<{ id: LeaderboardPeriod; label: string }> = [
  { id: "day", label: "日榜" },
  { id: "month", label: "月榜" },
  { id: "year", label: "年度榜" },
];
const periodLabels: Record<LeaderboardPeriod, string> = { day: "今日榜", month: "本月榜", year: "年度榜" };

const initials = (name: string) => Array.from(name.trim()).slice(0, 2).join("").toUpperCase() || "?";
const colorFor = (memberId: number) => colors[Math.abs(memberId) % colors.length];
const entries = computed(() => (state.data?.entries ?? []).map((entry) => ({
  ...entry,
  initials: initials(entry.memberName),
  color: colorFor(entry.memberId),
})));
const podium = computed(() => [entries.value[1], entries.value[0], entries.value[2]]
  .map((entry, index) => entry ? ({ ...entry, podiumPosition: [2, 1, 3][index] }) : null)
  .filter((entry): entry is LeaderboardEntry & { initials: string; color: string; podiumPosition: number } => entry !== null));
const periodDate = computed(() => {
  const start = state.data?.periodStart ?? "";
  if (state.period === "day") return start.slice(0, 10);
  if (state.period === "month") return start.slice(0, 7);
  return start.slice(0, 4);
});
const updatedAt = computed(() => state.data?.generatedAt
  ? new Date(state.data.generatedAt).toLocaleString("zh-CN", { hour12: false })
  : "—");

const refresh = () => loadLeaderboard(state, state.period, (period) => platformApi.getLeaderboard(period));
const selectPeriod = (period: LeaderboardPeriod) => {
  if (period === state.period && state.status !== "error") return;
  void loadLeaderboard(state, period, (value) => platformApi.getLeaderboard(value));
};
const closePreview = () => (preview.value = false);
const onKeydown = (event: KeyboardEvent) => { if (event.key === "Escape") closePreview(); };

onMounted(() => {
  window.addEventListener("keydown", onKeydown);
  void refresh();
});
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body" :disabled="!preview">
    <div class="ranking-screen" :class="{ 'ranking-screen--preview': preview }">
      <div v-if="!preview" class="ranking-toolbar glass-panel">
        <div class="segmented-control segmented-control--large" aria-label="排行榜周期">
          <button v-for="item in periodItems" :key="item.id" type="button" :class="{ active: state.period === item.id }" @click="selectPeriod(item.id)">{{ item.label }}</button>
        </div>
        <button class="secondary-button ranking-toolbar__refresh" data-testid="admin-leaderboard-refresh" type="button" :disabled="state.status === 'loading'" @click="refresh"><AppIcon name="refresh" :size="18" /> {{ state.status === 'loading' ? '加载中' : '刷新' }}</button>
        <button v-if="secondaryScreenPreviewEnabled" class="primary-button" type="button" @click="preview = true"><AppIcon name="monitor" :size="18" /> 进入副屏预览</button>
      </div>
      <button v-else class="preview-close" type="button" @click="closePreview"><AppIcon name="close" /> 退出预览 <kbd>Esc</kbd></button>

      <section class="leaderboard glass-panel">
        <header class="leaderboard__header">
          <div class="leaderboard__brand"><span class="brand__mark brand__mark--small" aria-hidden="true"><i></i><i></i><i></i><i></i></span><div><strong>LED GAME</strong><small>SCORE LEADERBOARD</small></div></div>
          <div class="leaderboard__title"><p>{{ periodDate || '实时数据' }}</p><h2>{{ periodLabels[state.period] }} · 积分排行榜</h2></div>
          <div class="preview-label"><span></span> UI PREVIEW</div>
        </header>

        <div class="leaderboard-state" data-testid="admin-leaderboard-state" :data-status="state.status">
          <div v-if="state.status === 'loading'" class="leaderboard-message"><AppIcon name="refresh" :size="28" /><strong>{{ text('leaderboardLoading') }}</strong><span>{{ text('leaderboardLoadingHint') }}</span></div>
          <div v-else-if="state.status === 'error'" class="leaderboard-message leaderboard-message--error"><AppIcon name="warning" :size="28" /><strong>{{ text('leaderboardError') }}</strong><span>{{ state.error }}</span><button class="primary-button" type="button" @click="refresh">{{ text('leaderboardRetry') }}</button></div>
          <div v-else-if="state.status === 'success' && entries.length === 0" class="leaderboard-message"><AppIcon name="ranking" :size="32" /><strong>{{ text('leaderboardEmptyTitle') }}</strong><span>{{ text('leaderboardEmptyBody') }}</span></div>

          <template v-else-if="state.status === 'success'">
            <div class="podium">
              <article v-for="entry in podium" :key="entry.memberId" class="podium-card" :class="`podium-card--${entry.podiumPosition}`">
                <div class="podium-crown" v-if="entry.rank === 1"><AppIcon name="ranking" /></div>
                <span class="avatar podium-card__avatar" :style="{ background: entry.color }">{{ entry.initials }}</span>
                <div class="podium-card__rank">{{ entry.rank }}</div>
                <h3>{{ entry.memberName }}</h3><p>会员 #{{ entry.memberId }}</p><strong>{{ entry.points.toLocaleString() }}<small>PTS</small></strong>
              </article>
            </div>

            <div class="ranking-list">
              <div v-for="entry in entries.slice(3)" :key="entry.memberId" class="ranking-row">
                <strong class="ranking-row__number">{{ String(entry.rank).padStart(2, '0') }}</strong><span class="avatar" :style="{ background: entry.color }">{{ entry.initials }}</span><div class="ranking-row__member"><strong>{{ entry.memberName }}</strong><small>会员 #{{ entry.memberId }} · {{ entry.completedGames }} 场已结算游戏</small></div><strong class="ranking-row__score">{{ entry.points.toLocaleString() }}<small>PTS</small></strong>
              </div>
            </div>
          </template>
        </div>
        <footer class="leaderboard__footer"><span>更新时间 {{ updatedAt }}</span></footer>
      </section>
    </div>
  </Teleport>
</template>

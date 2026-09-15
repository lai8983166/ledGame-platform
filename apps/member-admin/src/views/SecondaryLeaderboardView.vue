<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive } from "vue";
import type { LeaderboardResponse } from "@ledgame/platform-api-client";
import { platformApi } from "../platformApi";

defineProps<{ locale?: string }>();

const state = reactive<{ status: "loading" | "success" | "error"; data: Record<string, LeaderboardResponse | null>; error: string; updatedAt: string }>({
  status: "loading", data: { day: null, month: null, year: null }, error: "", updatedAt: "",
});
let timer: number | undefined;
const periods = [
  { key: "day", label: "今日榜" },
  { key: "month", label: "本月榜" },
  { key: "year", label: "年度榜" },
] as const;

const refresh = async () => {
  state.status = "loading";
  try {
    const result = await platformApi.getLeaderboardSummary();
    state.data = { day: result.day, month: result.month, year: result.year };
    state.updatedAt = new Date().toLocaleString("zh-CN", { hour12: false });
    state.error = "";
    state.status = "success";
  } catch (error) {
    state.error = error instanceof Error ? error.message : "排行榜连接失败，请检查本机服务";
    state.status = "error";
  }
};

onMounted(() => {
  void refresh();
  timer = window.setInterval(() => void refresh(), 30000);
  window.addEventListener("online", refresh);
});
onBeforeUnmount(() => {
  if (timer) window.clearInterval(timer);
  window.removeEventListener("online", refresh);
});
</script>

<template>
  <main class="secondary-leaderboard-screen" data-testid="secondary-leaderboard-screen">
    <header class="secondary-leaderboard-header"><div class="secondary-brand-mark"><i></i><i></i><i></i><i></i></div><div><strong>LED GAME</strong><small>积分排行榜</small></div><span>更新时间 {{ state.updatedAt || '读取中…' }}</span></header>
    <div v-if="state.status === 'error'" class="secondary-leaderboard-error"><strong>排行榜暂时无法读取</strong><p>{{ state.error }}</p><button type="button" @click="refresh">重试</button></div>
    <section class="secondary-leaderboard-columns">
      <article v-for="period in periods" :key="period.key" class="secondary-leaderboard-column"><header><h2>{{ period.label }}</h2><small>{{ state.data[period.key]?.periodStart?.slice(0, period.key === 'day' ? 10 : period.key === 'month' ? 7 : 4) || '—' }}</small></header><div v-if="state.status === 'loading'" class="secondary-leaderboard-empty">正在加载…</div><div v-else-if="!(state.data[period.key]?.entries?.length)" class="secondary-leaderboard-empty">暂无数据</div><ol v-else><li v-for="entry in state.data[period.key]?.entries.slice(0, 10)" :key="entry.memberId"><b>#{{ entry.rank }}</b><span>{{ entry.memberName }}</span><strong>{{ entry.points.toLocaleString() }} 分</strong></li></ol></article>
    </section>
  </main>
</template>

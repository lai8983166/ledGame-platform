<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import { rankings } from "../data";
import type { RankingPeriod } from "../types";

const period = ref<RankingPeriod>("day");
const preview = ref(false);
const periodMeta = { day: { label: "今日榜", date: "2026.08.02" }, month: { label: "本月榜", date: "2026.08" }, year: { label: "年度榜", date: "2026" } };
const entries = computed(() => rankings[period.value]);
const podium = computed(() => [entries.value[1], entries.value[0], entries.value[2]].filter(Boolean));

const closePreview = () => (preview.value = false);
const onKeydown = (event: KeyboardEvent) => { if (event.key === "Escape") closePreview(); };
onMounted(() => window.addEventListener("keydown", onKeydown));
onBeforeUnmount(() => window.removeEventListener("keydown", onKeydown));
</script>

<template>
  <Teleport to="body" :disabled="!preview">
    <div class="ranking-screen" :class="{ 'ranking-screen--preview': preview }">
      <div v-if="!preview" class="ranking-toolbar glass-panel">
        <div class="segmented-control segmented-control--large" aria-label="排行榜周期"><button v-for="item in [{id:'day',label:'日榜'}, {id:'month',label:'月榜'}, {id:'year',label:'年度榜'}]" :key="item.id" type="button" :class="{ active: period === item.id }" @click="period = item.id as RankingPeriod">{{ item.label }}</button></div>
        <div class="ranking-toolbar__note"><AppIcon name="monitor" :size="17" /> 当前为 UI 预览，尚未连接电视设备</div>
        <button class="primary-button" type="button" @click="preview = true"><AppIcon name="monitor" :size="18" /> 进入副屏预览</button>
      </div>
      <button v-else class="preview-close" type="button" @click="closePreview"><AppIcon name="close" /> 退出预览 <kbd>Esc</kbd></button>

      <section class="leaderboard glass-panel">
        <header class="leaderboard__header"><div class="leaderboard__brand"><span class="brand__mark brand__mark--small" aria-hidden="true"><i></i><i></i><i></i><i></i></span><div><strong>LED GAME</strong><small>SCORE LEADERBOARD</small></div></div><div class="leaderboard__title"><p>{{ periodMeta[period].date }}</p><h2>{{ periodMeta[period].label }} · 积分排行榜</h2><span>每一次跃动，都值得被看见</span></div><div class="preview-label"><span></span> UI PREVIEW</div></header>

        <div class="podium">
          <article v-for="entry in podium" :key="entry.account" class="podium-card" :class="`podium-card--${entry.rank}`">
            <div class="podium-crown" v-if="entry.rank === 1"><AppIcon name="ranking" /></div>
            <span class="avatar podium-card__avatar" :style="{ background: entry.color }">{{ entry.initials }}</span>
            <div class="podium-card__rank">{{ entry.rank }}</div>
            <h3>{{ entry.memberName }}</h3><p>{{ entry.account }}</p><strong>{{ entry.score.toLocaleString() }}<small>PTS</small></strong>
          </article>
        </div>

        <div class="ranking-list">
          <div v-for="entry in entries.slice(3)" :key="entry.account" class="ranking-row">
            <strong class="ranking-row__number">{{ String(entry.rank).padStart(2, '0') }}</strong><span class="avatar" :style="{ background: entry.color }">{{ entry.initials }}</span><div class="ranking-row__member"><strong>{{ entry.memberName }}</strong><small>{{ entry.account }} · {{ entry.games }} 场游戏</small></div><span class="trend" :class="{ 'trend--down': entry.trend < 0, 'trend--flat': entry.trend === 0 }">{{ entry.trend > 0 ? '↑' : entry.trend < 0 ? '↓' : '—' }} {{ Math.abs(entry.trend) || '' }}</span><strong class="ranking-row__score">{{ entry.score.toLocaleString() }}<small>PTS</small></strong>
          </div>
        </div>
        <footer class="leaderboard__footer"><span>数据为界面演示 · 未连接真实副屏</span><span>更新时间 15:36</span></footer>
      </section>
    </div>
  </Teleport>
</template>

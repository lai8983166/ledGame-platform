<script setup lang="ts">
import { computed } from "vue";
import AppIcon from "../components/AppIcon.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { createRooms } from "../data";
import type { PageId, StatusTone } from "../types";

const emit = defineEmits<{ navigate: [page: PageId] }>();
const rooms = createRooms();

const roomCounts = computed(() => ({
  playing: rooms.filter((room) => room.status === "playing").length,
  idle: rooms.filter((room) => room.status === "idle").length,
  warning: rooms.filter((room) => room.hardware.some((device) => device.status !== "online")).length,
}));

const stats: Array<{ label: string; value: string; change: string; icon: string; tone: StatusTone; helper: string }> = [
  { label: "总会员数", value: "1,286", change: "+12.8%", icon: "members", tone: "info", helper: "较上月" },
  { label: "今日新增", value: "18", change: "+6", icon: "sparkles", tone: "purple", helper: "较昨日" },
  { label: "今日发卡", value: "42", change: "+9.4%", icon: "card", tone: "success", helper: "较昨日" },
  { label: "今日收款", value: "¥ 6,840", change: "+15.2%", icon: "wallet", tone: "warning", helper: "较昨日" },
];

const formatTime = (seconds = 0) => `${String(Math.floor(seconds / 60)).padStart(2, "0")}:${String(seconds % 60).padStart(2, "0")}`;
</script>

<template>
  <section class="welcome-strip glass-panel">
    <div>
      <p class="section-eyebrow"><AppIcon name="sparkles" :size="15" /> 下午好，管理员</p>
      <h2>门店状态尽在掌握</h2>
      <p>当前有 {{ roomCounts.playing }} 个房间正在游戏，{{ roomCounts.warning }} 项硬件状态需要留意。</p>
    </div>
    <div class="welcome-strip__visual" aria-hidden="true">
      <div class="orbital orbital--one"></div><div class="orbital orbital--two"></div>
      <div class="pulse-core"><AppIcon name="game" :size="36" /></div>
    </div>
  </section>

  <section class="stat-grid" aria-label="今日核心统计">
    <article v-for="stat in stats" :key="stat.label" class="stat-card glass-panel">
      <div class="stat-card__top">
        <span class="metric-icon" :class="`metric-icon--${stat.tone}`"><AppIcon :name="stat.icon" /></span>
        <StatusBadge :tone="stat.tone" :dot="false">{{ stat.change }}</StatusBadge>
      </div>
      <p>{{ stat.label }}</p>
      <strong>{{ stat.value }}</strong>
      <small>{{ stat.helper }}</small>
    </article>
  </section>

  <div class="dashboard-grid">
    <section class="content-card glass-panel">
      <header class="card-header">
        <div><p class="section-eyebrow">ROOM STATUS</p><h2>房间实时概况</h2></div>
        <button class="text-button" type="button" @click="emit('navigate', 'rooms')">查看全部 <AppIcon name="arrow" :size="16" /></button>
      </header>
      <div class="room-summary-list">
        <button v-for="room in rooms.slice(0, 4)" :key="room.id" class="room-summary" type="button" @click="emit('navigate', 'rooms')">
          <span class="room-summary__number">{{ room.code.slice(-2) }}</span>
          <span class="room-summary__info"><strong>{{ room.name }}</strong><small>{{ room.status === 'playing' ? room.gameName : '等待玩家刷卡' }}</small></span>
          <StatusBadge :tone="room.status === 'playing' ? 'purple' : 'success'">{{ room.status === 'playing' ? '游戏中' : '空闲' }}</StatusBadge>
          <span v-if="room.status === 'playing'" class="room-summary__time"><AppIcon name="clock" :size="15" /> {{ formatTime(room.remainingSeconds) }}</span>
          <span v-else class="room-summary__time room-summary__time--idle">可使用</span>
          <AppIcon name="chevron" :size="16" />
        </button>
      </div>
    </section>

    <aside class="content-card glass-panel health-card">
      <header class="card-header"><div><p class="section-eyebrow">SYSTEM HEALTH</p><h2>设备健康</h2></div><span class="health-score">96%</span></header>
      <div class="health-ring" aria-label="设备健康度 96%"><div><strong>96</strong><small>健康度</small></div></div>
      <div class="health-stats">
        <div><span class="status-dot status-dot--success"></span><p><strong>{{ roomCounts.idle + roomCounts.playing }}</strong><small>房间在线</small></p></div>
        <div><span class="status-dot status-dot--warning"></span><p><strong>{{ roomCounts.warning }}</strong><small>需要留意</small></p></div>
        <div><span class="status-dot status-dot--info"></span><p><strong>31</strong><small>硬件设备</small></p></div>
      </div>
      <button class="secondary-button secondary-button--full" type="button" @click="emit('navigate', 'rooms')"><AppIcon name="rooms" :size="17" /> 查看房间硬件</button>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { Room } from "../types";
import { mapRoomStatus, roomGameTimeText } from "../roomStatus";
import { platformApi } from "../platformApi";
import { memberAdminMessage } from "../localization";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { operatorSession } from "../operatorSession";
import { canUseOperatorCapability } from "../operatorPolicy";

const emit = defineEmits<{ toast: [message: string] }>();
const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);
const rooms = ref<Room[]>([]);
const loading = ref(false);
const loadError = ref("");
let refreshTimer: number | undefined;
let clockTimer: number | undefined;
const clockNow = ref(Date.now());
const filter = ref<"all" | "playing" | "idle" | "warning">("all");
const search = ref("");
const selectedRoomId = ref<string | null>(null);
const selectedRoom = computed(() => rooms.value.find((room) => room.id === selectedRoomId.value) ?? null);
const editingRoom = ref<Room | null>(null);
const editName = ref("");
const editError = ref("");
const canRenameRooms = computed(() => canUseOperatorCapability(operatorSession.current.value, "renameRoom"));

const filteredRooms = computed(() => rooms.value.filter((room) => {
  const matchesSearch = room.name.toLowerCase().includes(search.value.trim().toLowerCase()) || room.code.toLowerCase().includes(search.value.trim().toLowerCase());
  const hasWarning = room.online === false || room.hardware.some((device) => device.status !== "online");
  const matchesFilter = filter.value === "all" || room.status === filter.value || (filter.value === "warning" && hasWarning);
  return matchesSearch && matchesFilter;
}));

const roomTimeText = (room: Room) => roomGameTimeText(room, clockNow.value, text("roomUnlimited"));
const formatEventTime = (value?: string | null) => value ? new Date(value).toLocaleTimeString() : "--";
const roomTone = (room: Room) => !room.online ? "danger" : room.status === "playing" ? "purple" : "success";
const roomConnectionLabel = (room: Room) => {
  if (!room.ip) return "UNKNOWN IP";
  if (!room.online) return "OFFLINE";
  if (!room.lastEventAt) return "ONLINE / NO SNAPSHOT";
  const timestamp = Date.parse(room.lastEventAt);
  return Number.isFinite(timestamp) && Date.now() - timestamp > 60_000 ? "STALE" : "ONLINE";
};
const roomConnectionTone = (room: Room) => roomConnectionLabel(room) === "STALE" ? "warning" : room.online ? "success" : "danger";
const hardwareTone = (status: string) => status === "online" ? "success" : status === "warning" ? "warning" : "danger";
const hardwareLabel = (status: string) => status === "online" ? "在线" : status === "warning" ? "异常" : "离线";

const loadRooms = async () => {
  loading.value = true;
  try {
    rooms.value = (await platformApi.listRooms()).map(mapRoomStatus);
    loadError.value = "";
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : "无法读取房间状态";
  } finally {
    loading.value = false;
  }
};

onMounted(() => {
  void loadRooms();
  refreshTimer = window.setInterval(() => void loadRooms(), 2000);
  clockTimer = window.setInterval(() => { clockNow.value = Date.now(); }, 1000);
});

onBeforeUnmount(() => {
  if (refreshTimer) window.clearInterval(refreshTimer);
  if (clockTimer) window.clearInterval(clockTimer);
});

const openEdit = (room: Room) => {
  editingRoom.value = room;
  editName.value = room.name;
  editError.value = "";
};

const saveRoomName = async () => {
  const name = editName.value.trim();
  if (!name) return void (editError.value = "请输入房间名称");
  if (name.length > 12) return void (editError.value = "房间名称最多 12 个字符");
  if (rooms.value.some((room) => room.id !== editingRoom.value?.id && room.name === name)) return void (editError.value = "该房间名称已存在");
  if (!editingRoom.value?.ip) return void (editError.value = "该房间缺少 IP，无法保存");
  try {
    const updated = await platformApi.renameRoom(editingRoom.value.ip, name);
    const mapped = mapRoomStatus(updated);
    const index = rooms.value.findIndex((room) => room.id === editingRoom.value?.id);
    if (index >= 0) rooms.value[index] = mapped;
    editingRoom.value = null;
    emit("toast", "房间名称已更新");
  } catch (error) {
    editError.value = error instanceof Error ? error.message : "房间名称保存失败";
  }
};
</script>

<template>
  <section class="toolbar glass-panel">
    <div class="search-field"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="搜索房间" placeholder="搜索房间名称或编号" /></div>
    <div class="segmented-control" aria-label="房间状态筛选">
      <button v-for="item in [{id:'all',label:'全部'}, {id:'playing',label:'游戏中'}, {id:'idle',label:'空闲'}, {id:'warning',label:'需留意'}]" :key="item.id" type="button" :class="{ active: filter === item.id }" @click="filter = item.id as typeof filter">{{ item.label }}</button>
    </div>
    <div class="toolbar__meta"><span class="live-dot"></span> 临时状态实时预览</div>
  </section>

  <div v-if="loadError" class="notice-bar notice-bar--warning" data-testid="admin-rooms-error">{{ loadError }}</div>
  <div v-if="loading && !rooms.length" class="empty-state glass-panel"><p>正在读取真实房间连接...</p></div>
  <div v-else-if="filteredRooms.length" class="room-grid">
    <article v-for="room in filteredRooms" :key="room.id" class="room-card glass-panel" :class="{ 'room-card--playing': room.status === 'playing' }" :data-testid="`admin-room-${room.ip}`">
      <div class="room-connection-meta"><span data-testid="admin-room-ip">{{ room.ip || 'UNKNOWN_IP' }}</span><span data-testid="admin-room-queue-length">Queue {{ room.queueLength ?? 0 }}</span><span>{{ room.lastEventType || 'NO_EVENT' }} @ {{ formatEventTime(room.lastEventAt) }}</span><span>Seq {{ room.lastSequence ?? 0 }}</span><StatusBadge data-testid="admin-room-connection" :data-online="room.online" :tone="roomConnectionTone(room)">{{ roomConnectionLabel(room) }}</StatusBadge></div>
      <header class="room-card__header">
        <div class="room-code"><span>{{ room.code.slice(-2) }}</span><small>{{ room.code }}</small></div>
        <StatusBadge data-testid="admin-room-status" :data-status="room.status" :tone="roomTone(room)">{{ room.status === "playing" ? "游戏中" : "空闲" }}</StatusBadge>
      </header>
      <div class="room-card__title"><div><h2>{{ room.name }}</h2><p>{{ room.status === 'playing' ? room.gameName : '等待玩家刷卡开始' }}</p></div><button v-if="canRenameRooms" class="icon-button" type="button" aria-label="编辑房间名称" @click="openEdit(room)"><AppIcon name="edit" :size="17" /></button></div>
      <div v-if="room.status === 'playing'" class="room-timer"><span><AppIcon name="clock" :size="18" /> {{ text("roomGameRemaining") }}</span><strong data-testid="admin-room-game-time">{{ roomTimeText(room) }}</strong><small>{{ room.phase }}</small></div>
      <div v-else class="idle-state"><span class="idle-state__icon"><AppIcon name="sparkles" /></span><div><strong>房间已就绪</strong><small>刷卡后开始计算游戏时长</small></div></div>
      <div class="room-card__stats">
        <span><AppIcon name="members" :size="16" /> {{ room.players.length }} 位玩家</span>
        <span :class="{ 'text-warning': room.hardware.some((item) => item.status !== 'online') }"><AppIcon :name="room.hardware.some((item) => item.status !== 'online') ? 'alert' : 'check'" :size="16" /> {{ room.hardware.some((item) => item.status !== 'online') ? '硬件需留意' : '硬件正常' }}</span>
      </div>
      <button class="secondary-button secondary-button--full" type="button" @click="selectedRoomId = room.id">查看实时详情 <AppIcon name="arrow" :size="16" /></button>
    </article>
  </div>
  <div v-else class="empty-state glass-panel"><span><AppIcon name="search" :size="28" /></span><h2>没有匹配的房间</h2><p>尝试调整搜索词或状态筛选。</p></div>

  <BaseModal v-if="editingRoom" title="修改房间名称" :description="`${editingRoom.code} · 当前名称：${editingRoom.name}`" size="small" @close="editingRoom = null">
    <label class="form-field"><span>房间名称 <b>*</b></span><input v-model="editName" maxlength="13" :class="{ invalid: editError }" autofocus @input="editError = ''" @keyup.enter="saveRoomName" /><small v-if="editError" class="field-error">{{ editError }}</small><small v-else>{{ editName.length }}/12 个字符</small></label>
    <template #footer><button class="ghost-button" type="button" @click="editingRoom = null">取消</button><button class="primary-button" type="button" @click="saveRoomName">保存名称</button></template>
  </BaseModal>

  <SideDrawer v-if="selectedRoom" :title="selectedRoom.name" :eyebrow="`${selectedRoom.code} · 实时详情`" @close="selectedRoomId = null">
    <div class="notice-bar notice-bar--warning"><AppIcon name="alert" :size="18" /><div><strong>临时数据 · 断电不保存</strong><p>积分、排名、游戏与硬件状态仅用于当前实时监控。</p></div></div>
    <div class="drawer-room-status">
      <div><StatusBadge :tone="roomTone(selectedRoom)">{{ selectedRoom.status === 'playing' ? '游戏中' : '空闲' }}</StatusBadge><h3>{{ selectedRoom.status === 'playing' ? selectedRoom.gameName : '等待游戏开始' }}</h3><p>{{ selectedRoom.status === 'playing' ? selectedRoom.phase : '玩家首次在游戏系统刷卡后开始计时' }}</p></div>
      <strong v-if="selectedRoom.status === 'playing'" data-testid="admin-room-detail-game-time">{{ roomTimeText(selectedRoom) }}<small>{{ text("roomGameRemaining") }}</small></strong>
    </div>
    <section class="drawer-section">
      <div class="drawer-section__title"><h3>实时积分与排名</h3><span>{{ selectedRoom.players.length }} 位玩家</span></div>
      <div v-if="selectedRoom.players.length" class="live-ranking-list">
        <div v-for="player in selectedRoom.players" :key="player.id" class="live-ranking-item"><strong class="rank-number">{{ player.rank }}</strong><span class="avatar" :style="{ background: player.color }">{{ player.initials }}</span><div><strong>{{ player.name }}</strong><small>实时排名 #{{ player.rank }}</small></div><b>{{ player.score.toLocaleString() }}<small>分</small></b></div>
      </div>
      <div v-else class="mini-empty"><AppIcon name="members" /><p>游戏尚未开始，暂无实时积分。</p></div>
    </section>
    <section class="drawer-section">
      <div class="drawer-section__title"><h3>硬件临时状态</h3><span>{{ selectedRoom.hardware.length }} 个设备</span></div>
      <div class="hardware-list">
        <div v-for="device in selectedRoom.hardware" :key="device.id" class="hardware-item"><span class="hardware-item__icon" :class="`tone-${hardwareTone(device.status)}`"><AppIcon :name="device.status === 'online' ? 'check' : 'alert'" :size="17" /></span><div><strong>{{ device.name }}</strong><small>{{ device.location }} · {{ device.detail }}</small></div><StatusBadge :tone="hardwareTone(device.status)">{{ hardwareLabel(device.status) }}</StatusBadge></div>
      </div>
    </section>
  </SideDrawer>
</template>

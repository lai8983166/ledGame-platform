<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import BaseModal from "../components/BaseModal.vue";
import MemberCardDialog from "../components/MemberCardDialog.vue";
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
const cardAction = ref<"issue" | "return" | null>(null);
const canViewRooms = computed(() => canUseOperatorCapability(operatorSession.current.value, "operationsView"));
const canRenameRooms = computed(() => canUseOperatorCapability(operatorSession.current.value, "renameRoom"));

const filteredRooms = computed(() => rooms.value.filter((room) => {
  const matchesSearch = room.name.toLowerCase().includes(search.value.trim().toLowerCase()) || (room.ip ?? "").toLowerCase().includes(search.value.trim().toLowerCase());
  const hasWarning = room.online === false || room.hardware.some((device) => device.status !== "online");
  const matchesFilter = filter.value === "all" || room.status === filter.value || (filter.value === "warning" && hasWarning);
  return matchesSearch && matchesFilter;
}));

const roomTimeText = (room: Room) => room.online ? roomGameTimeText(room, clockNow.value, text("roomUnlimited")) ?? "--" : "--";
const roomTone = (room: Room) => !room.online ? "danger" : room.status === "playing" ? "purple" : "success";
const roomLabel = (room: Room) => text(!room.online ? "dashboardOffline" : room.status === "playing" ? "dashboardInGame" : "dashboardIdle");
const hardwareTone = (status: string) => status === "online" ? "success" : status === "warning" || status === "unknown" ? "warning" : "danger";
const hardwareLabel = (status: string) => status === "online" ? "在线" : status === "warning" ? "异常" : status === "unknown" ? "未检测" : "离线";

const loadRooms = async () => {
  if (!canViewRooms.value) return;
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
  if (!canViewRooms.value) return;
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
    <template v-if="canViewRooms">
      <div class="search-field"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="搜索房间" placeholder="搜索房间名称或编号" /></div>
      <div class="segmented-control" aria-label="房间状态筛选">
        <button v-for="item in [{id:'all',label:'全部'}, {id:'playing',label:'游戏中'}, {id:'idle',label:'空闲'}, {id:'warning',label:'需留意'}]" :key="item.id" type="button" :class="{ active: filter === item.id }" @click="filter = item.id as typeof filter">{{ item.label }}</button>
      </div>
    </template>
    <button class="primary-button toolbar__primary" data-testid="admin-card-issue" type="button" @click="cardAction = 'issue'"><AppIcon name="card" :size="18" /> {{ text("cardIssue") }}</button>
    <button class="secondary-button" data-testid="admin-card-return" type="button" @click="cardAction = 'return'"><AppIcon name="card" :size="18" /> {{ text("cardReturn") }}</button>
  </section>

  <template v-if="canViewRooms">
    <div v-if="loadError" class="notice-bar notice-bar--warning" data-testid="admin-rooms-error">{{ loadError }}</div>
    <div v-if="loading && !rooms.length" class="empty-state glass-panel"><p>正在读取真实房间连接...</p></div>
    <div v-else-if="filteredRooms.length" class="room-grid">
      <article v-for="room in filteredRooms" :key="room.id" class="room-card room-card--compact glass-panel"
        :class="{ 'room-card--playing': room.status === 'playing' }" :data-testid="`admin-room-${room.ip}`"
        role="button" tabindex="0" v-bind="{ 'aria-label': room.name }"
        @click="selectedRoomId = room.id" @keydown.enter="selectedRoomId = room.id" @keydown.space.prevent="selectedRoomId = room.id">
        <header class="room-card__header">
          <h2 data-testid="admin-room-name">{{ room.name }}</h2>
          <StatusBadge data-testid="admin-room-status" :data-status="room.status" :data-online="room.online" :tone="roomTone(room)">{{ roomLabel(room) }}</StatusBadge>
        </header>
        <p class="room-ip" data-testid="admin-room-ip">{{ room.ip || '--' }}</p>
        <div class="room-timer"><span><AppIcon name="clock" :size="18" /> {{ text("roomGameRemaining") }}</span><strong data-testid="admin-room-game-time">{{ roomTimeText(room) }}</strong></div>
      </article>
    </div>
    <div v-else class="empty-state glass-panel"><span><AppIcon name="search" :size="28" /></span><h2>没有匹配的房间</h2></div>
  </template>

  <MemberCardDialog v-if="cardAction" :action="cardAction" :locale="locale" @close="cardAction = null" @done="cardAction = null" />

  <BaseModal v-if="editingRoom" title="修改房间名称" size="small" @close="editingRoom = null">
    <label class="form-field"><span>房间名称 <b>*</b></span><input v-model="editName" maxlength="13" :class="{ invalid: editError }" autofocus @input="editError = ''" @keyup.enter="saveRoomName" /><small v-if="editError" class="field-error">{{ editError }}</small></label>
    <template #footer><button class="ghost-button" type="button" @click="editingRoom = null">取消</button><button class="primary-button" type="button" @click="saveRoomName">保存名称</button></template>
  </BaseModal>

  <SideDrawer v-if="selectedRoom" :title="selectedRoom.name" @close="selectedRoomId = null">
    <div class="drawer-actions"><button v-if="canRenameRooms" class="secondary-button" data-testid="admin-room-rename" type="button" @click="openEdit(selectedRoom)"><AppIcon name="edit" :size="17" />{{ text("roomRename") }}</button></div>
    <dl class="detail-grid"><div><dt>{{ text("dashboardQueue") }}</dt><dd data-testid="admin-room-queue-length">{{ selectedRoom.queueLength ?? 0 }}</dd></div></dl>
    <section class="drawer-section" data-testid="admin-room-hardware">
      <div class="drawer-section__title"><h3>{{ text("roomHardware") }}</h3></div>
      <div class="hardware-list">
        <div v-for="device in selectedRoom.hardware" :key="device.id" class="hardware-item"><span class="hardware-item__icon" :class="`tone-${hardwareTone(device.status)}`"><AppIcon :name="device.status === 'online' ? 'check' : 'alert'" :size="17" /></span><div><strong>{{ device.name }}</strong><small>{{ device.detail }}</small></div><StatusBadge :tone="hardwareTone(device.status)">{{ hardwareLabel(device.status) }}</StatusBadge></div>
      </div>
    </section>
  </SideDrawer>
</template>

<style scoped>
.room-card--compact { cursor: pointer; }
.room-card--compact:focus-visible { outline: 2px solid var(--primary, #5b7cff); outline-offset: 3px; }
.room-card__header h2 { margin: 0; font-size: 18px; overflow-wrap: anywhere; }
.room-ip { margin: 10px 0 18px; color: var(--muted); }
.room-timer { margin-bottom: 0; }
</style>

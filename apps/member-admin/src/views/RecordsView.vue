<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusBadge from "../components/StatusBadge.vue";
import type { Member } from "../types";
import { platformApi } from "../platformApi";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";

const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];

type RecordTab = "cards" | "plays" | "transactions" | "members";
type BindingRecord = {
  id: string;
  braceletId: string;
  memberId: string;
  memberName: string;
  phone: string;
  durationMinutes: number;
  boundAt: string;
  startedAt: string | null;
  endedAt: string | null;
  status: string;
};
type ChargeRecord = {
  id: string;
  braceletId: string;
  durationMinutes: number;
  unitPriceCents: number;
  amountCents: number;
  chargedAt: string;
};
type RealPlay = {
  id: string;
  memberName: string;
  braceletId: string;
  roomName: string;
  rawScore: number;
  pointsAwarded: number;
  scoringPolicy: string;
  terminationReason: string;
  startedAt: string;
  endedAt: string | null;
  status: string;
};

const activeTab = ref<RecordTab>("cards");
const search = ref("");
const dateFilter = ref("all");
const selectedMember = ref<Member | null>(null);
const members = ref<Member[]>([]);
const bindingRecords = ref<BindingRecord[]>([]);
const chargeRecords = ref<ChargeRecord[]>([]);
const plays = ref<RealPlay[]>([]);
const refreshing = ref(false);
const loadError = ref("");

const tabs: Array<{ id: RecordTab; label: string; icon: string }> = [
  { id: "cards", label: "发卡记录", icon: "card" },
  { id: "plays", label: "游玩记录", icon: "game" },
  { id: "transactions", label: "交易记录", icon: "wallet" },
  { id: "members", label: "会员数据", icon: "members" },
];

const formatTime = (value: unknown) => {
  if (value == null || String(value).trim() === "") return "—";
  return String(value).slice(0, 19).replace("T", " ");
};

const inSelectedPeriod = (value: string | null) => {
  if (dateFilter.value === "all") return true;
  if (!value) return false;
  const timestamp = new Date(value);
  if (Number.isNaN(timestamp.getTime())) return false;
  const now = new Date();
  if (dateFilter.value === "today") {
    return timestamp.getFullYear() === now.getFullYear()
      && timestamp.getMonth() === now.getMonth()
      && timestamp.getDate() === now.getDate();
  }
  if (dateFilter.value === "week") {
    return timestamp.getTime() >= now.getTime() - 7 * 24 * 60 * 60 * 1000;
  }
  return timestamp.getFullYear() === now.getFullYear() && timestamp.getMonth() === now.getMonth();
};

const loadRecords = async () => {
  refreshing.value = true;
  loadError.value = "";
  try {
    const [memberRows, playRows, bindingRows, chargeRows] = await Promise.all([
      platformApi.request<Array<{ id: number; phone: string; name: string; status: string; createdAt?: string; pointsTotal: number; rank: number }>>("/api/members"),
      platformApi.request<Array<Record<string, unknown>>>("/api/game-plays"),
      platformApi.request<Array<Record<string, unknown>>>("/api/records/wristband-bindings"),
      platformApi.request<Array<Record<string, unknown>>>("/api/records/wristband-charges"),
    ]);
    members.value = (memberRows ?? []).map((item) => ({
      id: String(item.id), account: `DB-${item.id}`, name: item.name,
      initials: item.name.slice(-2).toUpperCase(), phone: item.phone, identityId: "未设置",
      status: item.status === "ACTIVE" ? "active" : "inactive",
      joinedAt: (item.createdAt ?? "").slice(0, 10) || "—",
      color: ["#5b7cff", "#9b6dff", "#18b6a4", "#ff8a65"][item.id % 4],
      pointsTotal: Number(item.pointsTotal ?? 0), rank: Number(item.rank ?? 1),
    }));
    plays.value = (playRows ?? []).map((item) => ({
      id: `PLAY-${item.id}`, memberName: String(item.memberName ?? item.memberId ?? "—"),
      braceletId: String(item.uid ?? "—"), roomName: String(item.roomId ?? item.deviceId ?? "—"),
      rawScore: Number(item.rawScore ?? 0), pointsAwarded: Number(item.pointsAwarded ?? 0),
      scoringPolicy: String(item.scoringPolicy ?? "—"), terminationReason: String(item.terminationReason ?? "—"),
      startedAt: String(item.startedAt ?? ""), endedAt: item.endedAt == null ? null : String(item.endedAt),
      status: String(item.status ?? "UNKNOWN"),
    }));
    bindingRecords.value = (bindingRows ?? []).map((item) => ({
      id: `BIND-${item.id}`, braceletId: String(item.uid ?? "—"), memberId: `DB-${item.memberId}`,
      memberName: String(item.memberName ?? "—"), phone: String(item.phone ?? "—"),
      durationMinutes: Number(item.durationMinutes ?? 0), boundAt: String(item.boundAt ?? ""),
      startedAt: item.startedAt == null ? null : String(item.startedAt),
      endedAt: item.endedAt == null ? null : String(item.endedAt), status: String(item.status ?? "UNKNOWN"),
    }));
    chargeRecords.value = (chargeRows ?? []).map((item) => ({
      id: `TX-${item.id}`, braceletId: String(item.uid ?? "—"),
      durationMinutes: Number(item.durationMinutes ?? 0), unitPriceCents: Number(item.unitPriceCents ?? 0),
      amountCents: Number(item.amountCents ?? 0), chargedAt: String(item.chargedAt ?? ""),
    }));
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : "记录加载失败";
  } finally {
    refreshing.value = false;
  }
};

const query = computed(() => search.value.trim().toLowerCase());
const filteredCards = computed(() => bindingRecords.value.filter((item) => inSelectedPeriod(item.boundAt)
  && [item.id, item.braceletId, item.memberName, item.memberId, item.phone].some((value) => value.toLowerCase().includes(query.value))));
const filteredPlays = computed(() => plays.value.filter((item) => inSelectedPeriod(item.startedAt)
  && [item.id, item.braceletId, item.memberName, item.roomName].some((value) => value.toLowerCase().includes(query.value))));
const filteredTransactions = computed(() => chargeRecords.value.filter((item) => inSelectedPeriod(item.chargedAt)
  && [item.id, item.braceletId, String(item.durationMinutes)].some((value) => value.toLowerCase().includes(query.value))));
const filteredMembers = computed(() => members.value.filter((item) => [item.name, item.account, item.phone, item.identityId]
  .some((value) => value.toLowerCase().includes(query.value))));
const visibleCount = computed(() => ({ cards: filteredCards.value.length, plays: filteredPlays.value.length,
  transactions: filteredTransactions.value.length, members: filteredMembers.value.length })[activeTab.value]);
const dataSourceLabel = computed(() => ({ cards: "来自 SQLite 手环绑定记录", plays: "来自 SQLite 游玩记录",
  transactions: "来自 SQLite 充时流水", members: "来自 SQLite 会员数据" })[activeTab.value]);
const searchPlaceholder = computed(() => activeTab.value === "members" ? "搜索会员资料"
  : activeTab.value === "transactions" ? "搜索流水、手环或分钟数" : "搜索编号、会员、房间或手环");
const bindingStatusLabel = (status: string) => ({ READY: "待游戏", ACTIVE: "计时中", EXPIRED: "已到期", RETURNED: "已归还" }[status] ?? status);
const bindingStatusTone = (status: string) => status === "ACTIVE" ? "success" : status === "READY" ? "warning" : "neutral";

onMounted(() => void loadRecords());
</script>

<template>
  <section class="records-tabs glass-panel" aria-label="记录与数据分类">
    <button v-for="tab in tabs" :key="tab.id" :data-testid="`admin-record-tab-${tab.id}`" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id; search = ''"><AppIcon :name="tab.icon" :size="18" /> {{ tab.label }}</button>
  </section>
  <section class="toolbar glass-panel">
    <div class="search-field search-field--wide"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="搜索记录" :placeholder="searchPlaceholder" /></div>
    <select v-if="activeTab !== 'members'" v-model="dateFilter" class="select-control" aria-label="时间范围"><option value="all">全部时间</option><option value="today">今天</option><option value="week">近 7 天</option><option value="month">本月</option></select>
    <button class="secondary-button compact-button" data-testid="admin-records-refresh" type="button" :disabled="refreshing" @click="loadRecords"><AppIcon name="refresh" :size="16" :class="{ spinning: refreshing }" />{{ refreshing ? "刷新中…" : "刷新" }}</button>
    <span class="result-count"><AppIcon name="database" :size="15" /> {{ dataSourceLabel }}</span>
  </section>
  <p v-if="loadError" class="form-error"><AppIcon name="alert" :size="16" />{{ loadError }}</p>

  <section class="table-card glass-panel">
    <div class="data-table-wrap">
      <table v-if="activeTab === 'cards'" class="data-table" data-testid="admin-binding-records"><thead><tr><th>发卡编号</th><th>手环 UID</th><th>关联会员</th><th>绑定时间</th><th>首次游戏 / 结束</th><th>有效时长</th><th>状态</th></tr></thead><tbody><tr v-for="item in filteredCards" :key="item.id"><td><code>{{ item.id }}</code></td><td><strong>{{ item.braceletId }}</strong></td><td>{{ item.memberName }}<small class="cell-sub">{{ item.memberId }} · {{ item.phone }}</small></td><td>{{ formatTime(item.boundAt) }}</td><td>{{ item.startedAt ? formatTime(item.startedAt) : '尚未开始' }}<small v-if="item.endedAt" class="cell-sub">结束：{{ formatTime(item.endedAt) }}</small></td><td>{{ item.durationMinutes }} 分钟</td><td><StatusBadge :tone="bindingStatusTone(item.status)">{{ bindingStatusLabel(item.status) }}</StatusBadge></td></tr><tr v-if="!filteredCards.length"><td colspan="7">暂无符合条件的真实发卡记录</td></tr></tbody></table>
      <table v-else-if="activeTab === 'plays'" class="data-table" data-testid="admin-play-records"><thead><tr><th>记录编号</th><th>会员 / 手环</th><th>游玩房间</th><th>{{ text("rawScore") }}</th><th>{{ text("memberPoints") }}</th><th>{{ text("statusReason") }}</th><th>{{ text("startEndTime") }}</th></tr></thead><tbody><tr v-for="item in filteredPlays" :key="item.id" :data-testid="`admin-play-${item.id}`" :data-status="item.status"><td><code>{{ item.id }}</code></td><td>{{ item.memberName }}<small class="cell-sub">{{ item.braceletId }}</small></td><td><strong>{{ item.roomName }}</strong></td><td data-testid="admin-play-raw-score"><strong class="score-value">{{ item.rawScore.toLocaleString() }}</strong></td><td data-testid="admin-play-points"><strong class="score-value">{{ item.pointsAwarded.toLocaleString() }}</strong><small class="cell-sub">{{ item.scoringPolicy }}</small></td><td data-testid="admin-play-termination"><StatusBadge :tone="item.status === 'COMPLETED' ? 'success' : item.status === 'ABORTED' ? 'warning' : 'purple'">{{ item.status }}</StatusBadge><small class="cell-sub">{{ item.terminationReason }}</small></td><td>{{ formatTime(item.startedAt) }}<small class="cell-sub">{{ item.endedAt ? formatTime(item.endedAt) : '进行中' }}</small></td></tr><tr v-if="!filteredPlays.length"><td colspan="7">暂无符合条件的真实游玩记录</td></tr></tbody></table>
      <table v-else-if="activeTab === 'transactions'" class="data-table" data-testid="admin-charge-records"><thead><tr><th>交易流水</th><th>手环 UID</th><th>购买时长</th><th>分钟单价</th><th>交易金额</th><th>交易时间</th><th>状态</th></tr></thead><tbody><tr v-for="item in filteredTransactions" :key="item.id"><td><code>{{ item.id }}</code></td><td><strong>{{ item.braceletId }}</strong></td><td>{{ item.durationMinutes }} 分钟</td><td>¥{{ (item.unitPriceCents / 100).toFixed(2) }}</td><td><strong class="money-value">+ ¥{{ (item.amountCents / 100).toFixed(2) }}</strong></td><td>{{ formatTime(item.chargedAt) }}</td><td><StatusBadge tone="success">成功</StatusBadge></td></tr><tr v-if="!filteredTransactions.length"><td colspan="7">暂无符合条件的真实交易记录</td></tr></tbody></table>
      <table v-else class="data-table"><thead><tr><th>会员账号</th><th>会员</th><th>联系方式</th><th>身份 ID</th><th>加入日期</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filteredMembers" :key="item.id"><td><code>{{ item.account }}</code></td><td><span class="member-inline"><span class="avatar avatar--small" :style="{ background: item.color }">{{ item.initials }}</span><strong>{{ item.name }}</strong></span></td><td>{{ item.phone }}</td><td>{{ item.identityId }}</td><td>{{ item.joinedAt }}</td><td><StatusBadge :tone="item.status === 'active' ? 'success' : 'neutral'">{{ item.status === 'active' ? '正常' : '停用' }}</StatusBadge></td><td><button class="text-button" type="button" @click="selectedMember = item">详情 <AppIcon name="arrow" :size="14" /></button></td></tr><tr v-if="!filteredMembers.length"><td colspan="7">暂无符合条件的真实会员数据</td></tr></tbody></table>
    </div>
    <footer class="table-footer"><span>{{ dataSourceLabel }}</span><strong>共 {{ visibleCount }} 条</strong></footer>
  </section>

  <SideDrawer v-if="selectedMember" :title="selectedMember.name" eyebrow="会员数据详情" @close="selectedMember = null"><div class="member-hero"><span class="avatar avatar--large" :style="{ background: selectedMember.color }">{{ selectedMember.initials }}</span><div><h3>{{ selectedMember.name }}</h3><p>{{ selectedMember.account }}</p><StatusBadge tone="success">{{ selectedMember.status === 'active' ? '正常会员' : '已停用' }}</StatusBadge></div></div><section class="drawer-section"><div class="drawer-section__title"><h3>完整数据</h3></div><dl class="detail-grid"><div><dt>会员账号</dt><dd>{{ selectedMember.account }}</dd></div><div><dt>联系方式</dt><dd>{{ selectedMember.phone }}</dd></div><div><dt>身份 ID</dt><dd>{{ selectedMember.identityId }}</dd></div><div><dt>头像标识</dt><dd>{{ selectedMember.initials }}</dd></div><div><dt>加入日期</dt><dd>{{ selectedMember.joinedAt }}</dd></div></dl></section><div class="notice-bar"><AppIcon name="card" :size="18" /><div><strong>购买时长不属于会员资料</strong><p>请前往“手环办理”查看具体手环的分钟数和状态。</p></div></div></SideDrawer>
</template>

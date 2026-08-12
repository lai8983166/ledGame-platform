<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import AppIcon from "../components/AppIcon.vue";
import SideDrawer from "../components/SideDrawer.vue";
import StatusBadge from "../components/StatusBadge.vue";
import { cardIssueRecords, gameConfigs, transactionRecords } from "../data";
import type { GameConfig, Member } from "../types";
import { platformApiBase } from "../platformApi";

type RecordTab = "cards" | "plays" | "transactions" | "members" | "games";
const activeTab = ref<RecordTab>("cards");
const search = ref("");
const dateFilter = ref("today");
const selectedMember = ref<Member | null>(null);
const selectedGame = ref<GameConfig | null>(null);
const members = ref<Member[]>([]);
type RealPlay = { id: string; memberName: string; braceletId: string; roomName: string; score: number; startedAt: string; endedAt: string; status: string };
const plays = ref<RealPlay[]>([]);

const loadMembers = async () => {
  try {
    const response = await fetch(`${platformApiBase}/members`);
    const rows = await response.json() as Array<{ id: number; phone: string; name: string; status: string; createdAt?: string }>;
    members.value = rows.map((item) => ({ id: String(item.id), account: `DB-${item.id}`, name: item.name, initials: item.name.slice(-2).toUpperCase(), phone: item.phone, identityId: "未设置", status: item.status === "ACTIVE" ? "active" : "inactive", joinedAt: (item.createdAt ?? "").slice(0, 10) || "—", color: ["#5b7cff", "#9b6dff", "#18b6a4", "#ff8a65"][item.id % 4] }));
  } catch {
    members.value = [];
  }
};

const loadPlays = async () => {
  try {
    const response = await fetch(`${platformApiBase}/game-plays`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const rows = await response.json() as Array<Record<string, unknown>>;
    plays.value = rows.map((item) => ({
      id: `PLAY-${item.id}`,
      memberName: String(item.memberName ?? item.memberId ?? "—"),
      braceletId: String(item.uid ?? "—"),
      roomName: String(item.roomId ?? item.deviceId ?? "—"),
      score: Number(item.rawScore ?? 0),
      startedAt: String(item.startedAt ?? "—").slice(0, 19).replace("T", " "),
      endedAt: item.endedAt == null ? "进行中" : String(item.endedAt).slice(0, 19).replace("T", " "),
      status: String(item.status ?? "UNKNOWN"),
    }));
  } catch {
    plays.value = [];
  }
};

const tabs: Array<{ id: RecordTab; label: string; icon: string }> = [
  { id: "cards", label: "发卡记录", icon: "card" }, { id: "plays", label: "游玩记录", icon: "game" }, { id: "transactions", label: "交易记录", icon: "wallet" }, { id: "members", label: "会员数据", icon: "members" }, { id: "games", label: "游戏数据", icon: "database" },
];
const query = computed(() => search.value.trim().toLowerCase());
const filteredCards = computed(() => cardIssueRecords.filter((item) => [item.id, item.braceletId, item.memberName, item.memberAccount].some((v) => v.toLowerCase().includes(query.value))));
const filteredPlays = computed(() => plays.value.filter((item) => [item.id, item.braceletId, item.memberName, item.roomName].some((v) => v.toLowerCase().includes(query.value))));
const filteredTransactions = computed(() => transactionRecords.filter((item) => [item.id, item.memberName, item.memberAccount].some((v) => v.toLowerCase().includes(query.value))));
const filteredMembers = computed(() => members.value.filter((item) => [item.name, item.account, item.phone, item.identityId].some((v) => v.toLowerCase().includes(query.value))));
const filteredGames = computed(() => gameConfigs.filter((item) => [item.name, item.category, item.id].some((v) => v.toLowerCase().includes(query.value))));
const statusLabel = (status: string) => ({ activated: "已激活", unused: "未使用", expired: "已过期", success: "成功", refunded: "已退款", pending: "处理中" }[status] ?? status);
const statusTone = (status: string) => status === "activated" || status === "success" ? "success" : status === "unused" || status === "pending" ? "warning" : "neutral";
onMounted(() => { void loadMembers(); void loadPlays(); });
</script>

<template>
  <section class="records-tabs glass-panel" aria-label="记录与数据分类">
    <button v-for="tab in tabs" :key="tab.id" :data-testid="`admin-record-tab-${tab.id}`" type="button" :class="{ active: activeTab === tab.id }" @click="activeTab = tab.id; search = ''"><AppIcon :name="tab.icon" :size="18" /> {{ tab.label }}</button>
  </section>
  <section class="toolbar glass-panel">
    <div class="search-field search-field--wide"><AppIcon name="search" :size="18" /><input v-model="search" aria-label="搜索记录" :placeholder="activeTab === 'games' ? '搜索游戏名称或分类' : activeTab === 'members' ? '搜索会员资料' : '搜索编号、会员或手环'" /></div>
    <select v-if="!['members','games'].includes(activeTab)" v-model="dateFilter" class="select-control" aria-label="时间范围"><option value="today">今天</option><option value="week">近 7 天</option><option value="month">本月</option></select>
    <span class="result-count"><AppIcon name="filter" :size="15" /> {{ activeTab === 'members' ? '会员来自 SQLite' : '当前为演示记录' }}</span>
  </section>

  <section class="table-card glass-panel">
    <div class="data-table-wrap">
      <table v-if="activeTab === 'cards'" class="data-table"><thead><tr><th>发卡编号</th><th>IC 手环</th><th>关联会员</th><th>发卡 / 激活时间</th><th>有效时长</th><th>状态</th></tr></thead><tbody><tr v-for="item in filteredCards" :key="item.id"><td><code>{{ item.id }}</code></td><td><strong>{{ item.braceletId }}</strong></td><td>{{ item.memberName }}<small class="cell-sub">{{ item.memberAccount }}</small></td><td>{{ item.issuedAt }}</td><td>{{ item.duration }} 分钟</td><td><StatusBadge :tone="statusTone(item.status)">{{ statusLabel(item.status) }}</StatusBadge></td></tr></tbody></table>
      <table v-else-if="activeTab === 'plays'" class="data-table" data-testid="admin-play-records"><thead><tr><th>记录编号</th><th>会员 / 手环</th><th>游玩房间</th><th>实时 / 最终积分</th><th>开始时间</th><th>结束时间</th></tr></thead><tbody><tr v-for="item in filteredPlays" :key="item.id" :data-testid="`admin-play-${item.id}`" :data-status="item.status"><td><code>{{ item.id }}</code></td><td>{{ item.memberName }}<small class="cell-sub">{{ item.braceletId }}</small></td><td><strong>{{ item.roomName }}</strong></td><td><strong class="score-value">{{ item.score.toLocaleString() }}</strong> 分</td><td>{{ item.startedAt }}</td><td><StatusBadge v-if="item.endedAt === '进行中'" tone="purple">进行中</StatusBadge><template v-else>{{ item.endedAt }}</template></td></tr></tbody></table>
      <table v-else-if="activeTab === 'transactions'" class="data-table"><thead><tr><th>交易流水</th><th>关联会员</th><th>充值金额</th><th>交易时间</th><th>状态</th></tr></thead><tbody><tr v-for="item in filteredTransactions" :key="item.id"><td><code>{{ item.id }}</code></td><td>{{ item.memberName }}<small class="cell-sub">{{ item.memberAccount }}</small></td><td><strong class="money-value">+ ¥{{ item.amount.toFixed(2) }}</strong></td><td>{{ item.tradedAt }}</td><td><StatusBadge :tone="statusTone(item.status)">{{ statusLabel(item.status) }}</StatusBadge></td></tr></tbody></table>
      <table v-else-if="activeTab === 'members'" class="data-table"><thead><tr><th>会员账号</th><th>会员</th><th>联系方式</th><th>身份 ID</th><th>加入日期</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filteredMembers" :key="item.id"><td><code>{{ item.account }}</code></td><td><span class="member-inline"><span class="avatar avatar--small" :style="{ background: item.color }">{{ item.initials }}</span><strong>{{ item.name }}</strong></span></td><td>{{ item.phone }}</td><td>{{ item.identityId }}</td><td>{{ item.joinedAt }}</td><td><StatusBadge :tone="item.status === 'active' ? 'success' : 'neutral'">{{ item.status === 'active' ? '正常' : '停用' }}</StatusBadge></td><td><button class="text-button" type="button" @click="selectedMember = item">详情 <AppIcon name="arrow" :size="14" /></button></td></tr></tbody></table>
      <table v-else class="data-table"><thead><tr><th>游戏</th><th>分类</th><th>关卡</th><th>生命值</th><th>组件数量</th><th>状态</th><th></th></tr></thead><tbody><tr v-for="item in filteredGames" :key="item.id"><td><strong>{{ item.name }}</strong><small class="cell-sub">{{ item.id }}</small></td><td>{{ item.category }}</td><td>{{ item.levels.length }} 关</td><td>{{ item.lives }} 点</td><td>{{ item.components.length }} 个</td><td><StatusBadge :tone="item.status === 'enabled' ? 'success' : 'neutral'">{{ item.status === 'enabled' ? '已启用' : '已停用' }}</StatusBadge></td><td><button class="text-button" type="button" @click="selectedGame = item">配置详情 <AppIcon name="arrow" :size="14" /></button></td></tr></tbody></table>
    </div>
    <footer class="table-footer"><span>显示当前演示数据第 1 页</span><div class="pagination"><button disabled type="button">上一页</button><button class="active" type="button">1</button><button type="button">2</button><button type="button">下一页</button></div></footer>
  </section>

  <SideDrawer v-if="selectedMember" :title="selectedMember.name" eyebrow="会员数据详情" @close="selectedMember = null"><div class="member-hero"><span class="avatar avatar--large" :style="{ background: selectedMember.color }">{{ selectedMember.initials }}</span><div><h3>{{ selectedMember.name }}</h3><p>{{ selectedMember.account }}</p><StatusBadge tone="success">{{ selectedMember.status === 'active' ? '正常会员' : '已停用' }}</StatusBadge></div></div><section class="drawer-section"><div class="drawer-section__title"><h3>完整数据</h3></div><dl class="detail-grid"><div><dt>会员账号</dt><dd>{{ selectedMember.account }}</dd></div><div><dt>联系方式</dt><dd>{{ selectedMember.phone }}</dd></div><div><dt>身份 ID</dt><dd>{{ selectedMember.identityId }}</dd></div><div><dt>头像标识</dt><dd>{{ selectedMember.initials }}</dd></div><div><dt>加入日期</dt><dd>{{ selectedMember.joinedAt }}</dd></div></dl></section><div class="notice-bar"><AppIcon name="card" :size="18" /><div><strong>购买时长不属于会员资料</strong><p>请前往“手环办理”查看具体手环的分钟数和状态。</p></div></div></SideDrawer>
  <SideDrawer v-if="selectedGame" :title="selectedGame.name" :eyebrow="`${selectedGame.id} · 游戏配置`" @close="selectedGame = null"><div class="game-hero"><span><AppIcon name="game" :size="30" /></span><div><h3>{{ selectedGame.category }}</h3><StatusBadge :tone="selectedGame.status === 'enabled' ? 'success' : 'neutral'">{{ selectedGame.status === 'enabled' ? '已启用' : '已停用' }}</StatusBadge></div></div><section class="drawer-section"><div class="drawer-section__title"><h3>基础配置</h3></div><dl class="detail-grid"><div><dt>生命值</dt><dd>{{ selectedGame.lives }} 点</dd></div><div><dt>关卡数量</dt><dd>{{ selectedGame.levels.length }} 关</dd></div></dl></section><section class="drawer-section"><div class="drawer-section__title"><h3>关卡配置</h3></div><ol class="level-list"><li v-for="(level, index) in selectedGame.levels" :key="level"><span>{{ index + 1 }}</span>{{ level }}</li></ol></section><section class="drawer-section"><div class="drawer-section__title"><h3>积分规则</h3></div><p class="detail-copy">{{ selectedGame.scoringRule }}</p></section><section class="drawer-section"><div class="drawer-section__title"><h3>素材路径</h3></div><code class="path-code">{{ selectedGame.assetPath }}</code></section><section class="drawer-section"><div class="drawer-section__title"><h3>组件信息</h3></div><div class="tag-list"><span v-for="component in selectedGame.components" :key="component">{{ component }}</span></div></section></SideDrawer>
</template>

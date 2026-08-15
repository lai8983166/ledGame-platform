<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs, type MemberAdminMessageKey } from "../localization";

const emit = defineEmits<{ toast: [message: string] }>();
const props = defineProps<{ locale: PlatformLocale }>();
const text = (key: MemberAdminMessageKey) => memberAdminCatalogs[props.locale][key];
const desktop = window.memberAdminDesktop;
const diagnostics = ref<MemberAdminDiagnostics | null>(null);
const port = ref(8090);
const busy = ref(false);
let removeStatusListener: (() => void) | undefined;

async function refresh() {
  if (!desktop) return;
  diagnostics.value = await desktop.diagnostics();
  port.value = diagnostics.value.port;
}

async function restart() {
  if (!desktop) return;
  busy.value = true;
  try {
    diagnostics.value = await desktop.restartBackend(Number(port.value));
    emit("toast", diagnostics.value.state === "online" ? "本机服务已在新端口启动" : diagnostics.value.message);
  } finally { busy.value = false; }
}

async function retry() {
  if (!desktop) return;
  busy.value = true;
  try { diagnostics.value = await desktop.retryBackend(); }
  finally { busy.value = false; }
}

onMounted(async () => {
  await refresh();
  removeStatusListener = desktop?.onStatus((value) => {
    if (diagnostics.value) diagnostics.value = { ...diagnostics.value, ...value };
    else void refresh();
  });
});
onBeforeUnmount(() => removeStatusListener?.());
</script>

<template>
  <section v-if="desktop && diagnostics" class="desktop-runtime-card glass-panel" data-testid="member-desktop-runtime">
    <header>
      <div><p>{{ text('runtimeEyebrow') }}</p><h2>{{ text('runtimeTitle') }}</h2></div>
      <strong :class="`runtime-${diagnostics.state}`">{{ diagnostics.message }}</strong>
    </header>
    <div class="runtime-grid">
      <label><span>{{ text('runtimePort') }}</span><input v-model.number="port" type="number" min="1024" max="65535" data-testid="member-runtime-port" /></label>
      <div><span>{{ text('runtimeDatabase') }}</span><code>{{ diagnostics.dataPath }}</code></div>
      <div><span>{{ text('runtimeLog') }}</span><code>{{ diagnostics.logPath }}</code></div>
      <div><span>{{ text('runtimeLanAddresses') }}</span><code v-for="url in diagnostics.lanUrls" :key="url">{{ url }}</code><small v-if="!diagnostics.lanUrls.length">{{ text('runtimeNoLanAddress') }}</small></div>
    </div>
    <p v-if="diagnostics.lastError" class="runtime-error">{{ diagnostics.lastError }}</p>
    <footer>
      <button type="button" :disabled="busy" @click="refresh">{{ text('runtimeRefresh') }}</button>
      <button v-if="diagnostics.state === 'failed'" type="button" :disabled="busy" @click="retry">{{ text('runtimeRetry') }}</button>
      <button class="runtime-primary" type="button" :disabled="busy || Number(port) === diagnostics.port" data-testid="member-runtime-restart" @click="restart">{{ text('runtimeRestart') }}</button>
    </footer>
  </section>
</template>

<style scoped>
.desktop-runtime-card { margin-bottom:18px; padding:24px; }
.desktop-runtime-card header { display:flex; align-items:center; justify-content:space-between; gap:20px; }
.desktop-runtime-card header p { margin:0 0 5px; color:#4ddde0; font-size:10px; letter-spacing:.14em; }
.desktop-runtime-card h2 { margin:0; font-size:22px; }
.desktop-runtime-card header > strong { padding:8px 11px; border-radius:10px; color:#ffd18c; background:rgba(219,151,54,.12); font-size:12px; }
.desktop-runtime-card header > .runtime-online { color:#7be7b6; background:rgba(47,194,139,.12); }
.desktop-runtime-card header > .runtime-failed { color:#ff9caf; background:rgba(196,58,86,.12); }
.runtime-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:14px; margin:22px 0; }
.runtime-grid > * { display:flex; flex-direction:column; gap:7px; min-width:0; padding:14px; border:1px solid rgba(94,173,198,.16); border-radius:12px; background:rgba(6,23,42,.46); }
.runtime-grid span { color:#7fa5b7; font-size:10px; }
.runtime-grid code { overflow-wrap:anywhere; color:#d8f7f8; font-size:11px; }
.runtime-grid input { width:160px; height:38px; padding:0 10px; border:1px solid rgba(72,222,225,.3); border-radius:9px; color:#eaffff; background:#081b31; }
.runtime-error { color:#ff9caf; }
.desktop-runtime-card footer { display:flex; justify-content:flex-end; gap:10px; }
.desktop-runtime-card footer button { min-height:40px; padding:0 14px; border:1px solid rgba(72,222,225,.25); border-radius:10px; color:#dff; background:#12344a; cursor:pointer; }
.desktop-runtime-card footer button:disabled { opacity:.4; cursor:not-allowed; }
.desktop-runtime-card footer .runtime-primary { color:#06232a; background:#4ddde0; }
</style>

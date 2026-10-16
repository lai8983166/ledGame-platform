<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import { memberAdminCatalogs } from "../localization";

const props = defineProps<{ locale: PlatformLocale }>();
defineEmits<{ toast: [message: string] }>();
const desktop = window.memberAdminDesktop;
const diagnostics = ref<MemberAdminDiagnostics | null>(null);
const addresses = computed(() => [...new Set(
  diagnostics.value?.lanUrls.map((url) => new URL(url).hostname) ?? [],
)]);
let removeStatusListener: (() => void) | undefined;

async function refresh() {
  if (desktop) diagnostics.value = await desktop.diagnostics();
}

onMounted(() => {
  void refresh();
  removeStatusListener = desktop?.onStatus(() => void refresh());
  window.addEventListener("online", refresh);
});
onBeforeUnmount(() => {
  removeStatusListener?.();
  window.removeEventListener("online", refresh);
});
</script>

<template>
  <section v-if="desktop && diagnostics" class="desktop-runtime-card glass-panel" data-testid="member-desktop-runtime">
    <span>{{ memberAdminCatalogs[props.locale].runtimeLocalIp }}</span>
    <div><code v-for="address in addresses" :key="address">{{ address }}</code><code v-if="!addresses.length">127.0.0.1</code></div>
  </section>
</template>

<style scoped>
.desktop-runtime-card { display:flex; align-items:center; gap:20px; margin-bottom:18px; padding:18px 24px; }
.desktop-runtime-card > span { flex-shrink:0; }
.desktop-runtime-card > div { display:flex; flex-wrap:wrap; gap:8px 20px; min-width:0; }
.desktop-runtime-card code { overflow-wrap:anywhere; font-size:14px; }
</style>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import { readStoredLocale } from "@ledgame/platform-shared-ui";
import { REGISTRATION_KIOSK_LOCALE_STORAGE_KEY, registrationKioskCatalogs, type RegistrationKioskMessageKey } from "./localization";

const host = ref("127.0.0.1");
const port = ref(8090);
const busy = ref(true);
const error = ref("");
const hostInput = ref<HTMLInputElement | null>(null);
const errorDialog = ref<HTMLElement | null>(null);
const tone = ref<"idle" | "success" | "error">("idle");
const desktop = window.registrationDesktop;
const locale = readStoredLocale(window.localStorage, REGISTRATION_KIOSK_LOCALE_STORAGE_KEY);
const text = (key: RegistrationKioskMessageKey) => registrationKioskCatalogs[locale][key];
const message = ref(text("operatorReady"));
const canLaunch = computed(() => Boolean(desktop?.saveSettings && desktop?.testConnection && desktop?.startKiosk) && !busy.value);

watch(error, async (value) => { if (value) { await nextTick(); errorDialog.value?.focus(); } });
const closeError = async () => { error.value = ""; await nextTick(); hostInput.value?.focus(); };
const displayError = (cause: unknown) => {
  const detail = cause instanceof Error ? cause.message.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, "") : text("operatorStartFailed");
  error.value = detail;
  message.value = detail;
  tone.value = "error";
};

onMounted(async () => {
  try {
    if (!desktop?.readSettings) { message.value = text("operatorBrowserPreview"); return; }
    const settings = await desktop.readSettings();
    host.value = settings.host;
    port.value = settings.port;
  } catch (cause) { displayError(cause); }
  finally { busy.value = false; }
});

function changed() {
  tone.value = "idle";
  message.value = text("operatorReady");
}

async function launch() {
  if (busy.value || !desktop?.saveSettings || !desktop?.testConnection || !desktop?.startKiosk) return;
  busy.value = true;
  error.value = "";
  message.value = text("operatorStarting");
  try {
    const settings = await desktop.saveSettings({ host: host.value, port: Number(port.value) });
    host.value = settings.host;
    port.value = settings.port;
    const result = await desktop.testConnection(settings);
    if (!result.ok) throw new Error(text("operatorConnectionFailed"));
    await desktop.startKiosk();
    tone.value = "success";
    message.value = text("operatorStarted");
  } catch (cause) { displayError(cause); }
  finally { busy.value = false; }
}
</script>

<template>
  <main class="operator-shell">
    <section class="operator-card">
      <p class="operator-eyebrow">{{ text('operatorEyebrow') }}</p>
      <h1>{{ text('operatorTitle') }}</h1>
      <p>{{ text('operatorDescription') }}</p>
      <div class="operator-fields">
        <label><span>{{ text('operatorHost') }}</span><input ref="hostInput" v-model="host" data-testid="operator-host" autocomplete="off" :placeholder="text('operatorHostPlaceholder')" :disabled="busy" @input="changed" @keydown.enter.prevent="launch" /></label>
        <label><span>{{ text('operatorPort') }}</span><input v-model.number="port" data-testid="operator-port" type="number" min="1024" max="65535" :disabled="busy" @input="changed" @keydown.enter.prevent="launch" /></label>
      </div>
      <p class="operator-status" :class="`operator-status--${tone}`" data-testid="operator-status" aria-live="polite">{{ message }}</p>
      <div class="operator-actions">
        <button class="operator-launch" type="button" :disabled="!canLaunch" data-testid="operator-launch" @click="launch">{{ text(busy ? 'operatorStarting' : 'operatorLaunch') }}</button>
      </div>
      <small>{{ text('operatorExitHint') }}</small>
    </section>
    <div v-if="error" class="modal-backdrop" @mousedown.self="closeError">
      <section ref="errorDialog" class="operator-error-dialog" role="dialog" aria-modal="true" aria-labelledby="operator-error-title" tabindex="-1" data-testid="operator-error-dialog" @keydown.esc.prevent="closeError">
        <h2 id="operator-error-title">{{ text('operatorStartFailed') }}</h2><p>{{ error }}</p>
        <button type="button" data-testid="operator-error-close" @click="closeError">{{ text('close') }}</button>
      </section>
    </div>
  </main>
</template>

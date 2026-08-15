<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { readStoredLocale } from "@ledgame/platform-shared-ui";
import { REGISTRATION_KIOSK_LOCALE_STORAGE_KEY, registrationKioskCatalogs, type RegistrationKioskMessageKey } from "./localization";

const host = ref("127.0.0.1");
const port = ref(8090);
const busy = ref(false);
const saved = ref(false);
const tested = ref(false);
const message = ref("请填写会员管理端所在电脑的地址和端口");
const tone = ref<"idle" | "success" | "error">("idle");
const desktop = window.registrationDesktop;
const locale = readStoredLocale(window.localStorage, REGISTRATION_KIOSK_LOCALE_STORAGE_KEY);
const text = (key: RegistrationKioskMessageKey) => registrationKioskCatalogs[locale][key];
const canLaunch = computed(() => saved.value && tested.value && !busy.value);

onMounted(async () => {
  if (!desktop?.readSettings) {
    message.value = "当前是浏览器预览。请使用桌面开发命令验证双窗口。";
    return;
  }
  const settings = await desktop.readSettings();
  host.value = settings.host;
  port.value = settings.port;
  saved.value = true;
});

function changed() {
  saved.value = false;
  tested.value = false;
  tone.value = "idle";
  message.value = "地址已修改，请保存并重新测试连接";
}

async function save() {
  if (!desktop?.saveSettings) return;
  busy.value = true;
  try {
    const result = await desktop.saveSettings({ host: host.value, port: Number(port.value) });
    host.value = result.host;
    port.value = result.port;
    saved.value = true;
    tested.value = false;
    tone.value = "success";
    message.value = "配置已保存，请测试连接";
  } catch (error) {
    tone.value = "error";
    message.value = error instanceof Error ? error.message : "保存失败";
  } finally { busy.value = false; }
}

async function testConnection() {
  if (!desktop?.testConnection) return;
  busy.value = true;
  try {
    const result = await desktop.testConnection({ host: host.value, port: Number(port.value) });
    tested.value = result.ok;
    tone.value = result.ok ? "success" : "error";
    message.value = result.ok ? "连接成功，可以启动自助注册" : (result.message || "连接失败");
  } finally { busy.value = false; }
}

async function launch() {
  if (!desktop?.startKiosk) return;
  busy.value = true;
  try { await desktop.startKiosk(); }
  catch (error) {
    tone.value = "error";
    message.value = error instanceof Error ? error.message : "启动失败";
    busy.value = false;
  }
}
</script>

<template>
  <main class="operator-shell">
    <section class="operator-card">
      <p class="operator-eyebrow">{{ text('operatorEyebrow') }}</p>
      <h1>{{ text('operatorTitle') }}</h1>
      <p>{{ text('operatorDescription') }}</p>
      <div class="operator-fields">
        <label><span>{{ text('operatorHost') }}</span><input v-model="host" data-testid="operator-host" autocomplete="off" :placeholder="text('operatorHostPlaceholder')" @input="changed" /></label>
        <label><span>{{ text('operatorPort') }}</span><input v-model.number="port" data-testid="operator-port" type="number" min="1024" max="65535" @input="changed" /></label>
      </div>
      <p class="operator-status" :class="`operator-status--${tone}`" data-testid="operator-status">{{ message }}</p>
      <div class="operator-actions">
        <button type="button" :disabled="busy" data-testid="operator-save" @click="save">{{ text('operatorSave') }}</button>
        <button type="button" :disabled="busy || !saved" data-testid="operator-test" @click="testConnection">{{ text('operatorTest') }}</button>
        <button class="operator-launch" type="button" :disabled="!canLaunch" data-testid="operator-launch" @click="launch">{{ text('operatorLaunch') }}</button>
      </div>
      <small>{{ text('operatorExitHint') }}</small>
    </section>
  </main>
</template>

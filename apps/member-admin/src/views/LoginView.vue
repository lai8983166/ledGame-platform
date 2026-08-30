<script setup lang="ts">
import { ref } from "vue";
import type { OperatorProfile } from "@ledgame/platform-api-client";
import type { PlatformLocale } from "@ledgame/platform-shared-ui";
import AppIcon from "../components/AppIcon.vue";
import { memberAdminMessage } from "../localization";
import { platformApi } from "../platformApi";

const props = defineProps<{ locale: PlatformLocale }>();
const emit = defineEmits<{ authenticated: [profile: OperatorProfile] }>();
const username = ref("");
const password = ref("");
const error = ref("");
const submitting = ref(false);
const text = (key: Parameters<typeof memberAdminMessage>[1]) => memberAdminMessage(props.locale, key);

const submit = async () => {
  if (submitting.value) return;
  error.value = "";
  submitting.value = true;
  try {
    emit("authenticated", await platformApi.loginOperator(username.value.trim(), password.value));
    password.value = "";
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : "登录失败";
  } finally {
    submitting.value = false;
  }
};
</script>

<template>
  <main class="operator-login-shell" data-testid="operator-login-page">
    <section class="operator-login-card glass-panel">
      <div class="brand operator-login-brand">
        <div class="brand__mark" aria-hidden="true"><span></span><span></span><span></span><span></span></div>
        <div><strong>{{ text("brandLedGame") }}</strong><small>{{ text("brandMemberAdmin") }}</small></div>
      </div>
      <header>
        <p class="section-eyebrow">{{ text("loginEyebrow") }}</p>
        <h1>{{ text("loginTitle") }}</h1>
        <p>{{ text("loginDescription") }}</p>
      </header>
      <form data-testid="operator-login-form" @submit.prevent="submit">
        <label class="form-field"><span>{{ text("loginUsername") }}</span><input v-model="username" data-testid="operator-login-username" autocomplete="username" autofocus @input="error = ''" /></label>
        <label class="form-field"><span>{{ text("loginPassword") }}</span><input v-model="password" data-testid="operator-login-password" type="password" autocomplete="current-password" @input="error = ''" /></label>
        <p v-if="error" class="form-error" data-testid="operator-login-error"><AppIcon name="alert" :size="16" />{{ error }}</p>
        <button class="primary-button operator-login-submit" data-testid="operator-login-submit" type="submit" :disabled="submitting || !username.trim() || !password">
          {{ text(submitting ? "loginSubmitting" : "loginAction") }}
        </button>
      </form>
      <div class="notice-bar"><AppIcon name="alert" :size="18" /><p>{{ text("loginFactoryHelp") }}</p></div>
    </section>
  </main>
</template>

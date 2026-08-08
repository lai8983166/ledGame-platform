<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import AvatarArt from "./components/AvatarArt.vue";
import KioskIcon from "./components/KioskIcon.vue";
import SoftKeyboard from "./components/SoftKeyboard.vue";
import WristbandArt from "./components/WristbandArt.vue";
import { avatars } from "./avatars";
import type { DemoMember, InputTarget, KeyboardLayout, KioskOverlay, KioskScreen, KioskSession } from "./types";

const API_BASE = "http://127.0.0.1:8090/api";
type ApiMember = DemoMember & { id: number };

const createSession = (): KioskSession => ({ phone: "", name: "", birthYear: "", birthMonth: "", birthDay: "", gender: "", avatarId: "", memberId: null, wristbandUid: "", durationMinutes: null, wristbandStatus: "idle" });
const screen = ref<KioskScreen>("home");
const overlay = ref<KioskOverlay>("none");
const session = reactive<KioskSession>(createSession());
const activeInput = ref<InputTarget | null>(null);
const keyboardLayout = ref<KeyboardLayout>("numeric");
const pendingAvatarId = ref("");
const errors = reactive<Record<string, string>>({});
const foundMember = ref<ApiMember | null>(null);
const activationError = ref("");
const toast = ref("");
let toastTimer: number | undefined;
let scanTimer: number | undefined;

const selectedAvatar = computed(() => avatars.find((avatar) => avatar.id === session.avatarId) ?? avatars[0]);
const pendingAvatar = computed(() => avatars.find((avatar) => avatar.id === pendingAvatarId.value) ?? avatars[0]);
const keyboardOpen = computed(() => activeInput.value !== null);
const currentTitle = computed(() => ({ home: "Self-Service", phone: "Activate Wristband", confirm: "Confirm Player", register: "Register Player", swipe: "Swipe Wristband", success: "Self-Service" }[screen.value]));
const stepNumber = computed(() => ({ home: 0, phone: 1, confirm: 2, register: 2, swipe: 3, success: 4 }[screen.value]));
const profileScreen = computed<KioskScreen>(() => foundMember.value ? "confirm" : "register");
const activeFieldLabel = computed(() => ({ phone: "Phone Number", name: "Player Name", birthYear: "Birth Year", birthMonth: "Birth Month", birthDay: "Birth Day" }[activeInput.value ?? "phone"]));

const request = async <T>(path: string, init?: RequestInit): Promise<T> => {
  const response = await fetch(`${API_BASE}${path}`, { headers: { "Content-Type": "application/json" }, ...init });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.error || `本机服务请求失败（HTTP ${response.status}）`);
  return body as T;
};

const showToast = (message: string) => {
  toast.value = message;
  if (toastTimer) window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (toast.value = ""), 2800);
};

const goTo = (target: KioskScreen) => {
  closeKeyboard();
  overlay.value = "none";
  screen.value = target;
  window.scrollTo({ top: 0, behavior: "smooth" });
};

const resetSession = () => {
  Object.assign(session, createSession());
  Object.keys(errors).forEach((key) => delete errors[key]);
  pendingAvatarId.value = "";
  foundMember.value = null;
  activationError.value = "";
  activeInput.value = null;
  overlay.value = "none";
  toast.value = "";
  screen.value = "home";
};

function closeKeyboard() { activeInput.value = null; }

const openInput = (target: InputTarget, layout: KeyboardLayout) => {
  activeInput.value = target;
  keyboardLayout.value = layout;
  delete errors[target === "phone" ? "phone" : target.startsWith("birth") ? "birthday" : target];
  nextTick(() => document.querySelector(`[data-input="${target}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" }));
};

const getInputValue = () => activeInput.value ? session[activeInput.value] : "";
const setInputValue = (value: string) => { if (activeInput.value) session[activeInput.value] = value; };
const handleKeyboardKey = (value: string) => {
  const target = activeInput.value;
  if (!target) return;
  const maxLength = target === "phone" ? 15 : target === "name" ? 24 : target === "birthYear" ? 4 : 2;
  if (getInputValue().length < maxLength) setInputValue(getInputValue() + (target === "name" ? value : value.replace(/\D/g, "")));
};
const backspace = () => setInputValue(getInputValue().slice(0, -1));
const clearInput = () => setInputValue("");

const isPhoneValid = () => /^\d{7,15}$/.test(session.phone);
const submitPhone = async () => {
  closeKeyboard();
  if (!isPhoneValid()) { errors.phone = "Enter 7–15 digits to continue."; nextTick(() => document.querySelector('[data-input="phone"]')?.scrollIntoView({ behavior: "smooth", block: "center" })); return; }
  delete errors.phone;
  try {
    const members = await request<ApiMember[]>(`/members?phone=${encodeURIComponent(session.phone)}`);
    foundMember.value = members[0] ?? null;
  } catch (error) {
    errors.phone = error instanceof Error ? error.message : "无法连接本机服务";
    return;
  }
  if (foundMember.value) {
    session.memberId = foundMember.value.id;
    session.name = foundMember.value.name;
    session.avatarId = foundMember.value.avatarId;
    screen.value = "confirm";
  } else {
    session.name = "";
    session.avatarId = "";
    screen.value = "register";
  }
};

const confirmExistingMember = () => {
  session.wristbandStatus = "waiting";
  activationError.value = "";
  screen.value = "swipe";
};

const validateRegistration = () => {
  Object.keys(errors).forEach((key) => delete errors[key]);
  if (!session.avatarId) errors.avatar = "Choose an avatar.";
  if (session.name.trim().length < 2) errors.name = "Enter at least 2 characters.";
  if (!isPhoneValid()) errors.phone = "Enter a valid phone number.";
  const year = Number(session.birthYear), month = Number(session.birthMonth), day = Number(session.birthDay);
  const currentYear = new Date().getFullYear();
  const validDate = year >= 1900 && year <= currentYear && month >= 1 && month <= 12 && day >= 1 && day <= new Date(year, month, 0).getDate();
  if (!validDate) errors.birthday = "Enter a valid date of birth.";
  if (!session.gender) errors.gender = "Select a gender option.";
  return Object.keys(errors).length === 0;
};

const submitRegistration = async () => {
  closeKeyboard();
  if (!validateRegistration()) {
    const first = ["avatar", "name", "phone", "birthday", "gender"].find((key) => errors[key]);
    nextTick(() => document.querySelector(`[data-field="${first}"]`)?.scrollIntoView({ behavior: "smooth", block: "center" }));
    return;
  }
  try {
    const member = await request<ApiMember>("/members", { method: "POST", body: JSON.stringify({ phone: session.phone, name: session.name.trim(), avatarId: session.avatarId, birthday: `${session.birthYear}-${session.birthMonth.padStart(2, "0")}-${session.birthDay.padStart(2, "0")}`, gender: session.gender, createdBy: "registration-kiosk" }) });
    foundMember.value = member;
    session.memberId = member.id;
    session.wristbandStatus = "waiting";
    activationError.value = "";
    screen.value = "swipe";
  } catch (error) {
    errors.phone = error instanceof Error ? error.message : "会员创建失败";
  }
};

const openAvatarSource = () => {
  closeKeyboard();
  pendingAvatarId.value = session.avatarId || avatars[0].id;
  overlay.value = "avatar-source";
};
const openAvatarLibrary = () => { pendingAvatarId.value = session.avatarId || avatars[0].id; overlay.value = "avatar-library"; };
const confirmAvatar = () => { session.avatarId = pendingAvatarId.value; delete errors.avatar; overlay.value = "none"; showToast(`${pendingAvatar.value.label} is now your avatar.`); };
const takePhoto = () => { overlay.value = "none"; showToast("Camera is not connected in this UI demo."); };

const scanWristband = async () => {
  if (session.wristbandStatus === "detected") return;
  activationError.value = "";
  const uid = session.wristbandUid.replace(/\D/g, "");
  if (!uid) return void (activationError.value = "请把已充时手环放到读卡器上");
  if (!session.memberId) return void (activationError.value = "请先完成会员确认");
  try {
    const wristband = await request<Record<string, unknown>>(`/wristbands/${encodeURIComponent(uid)}`);
    session.durationMinutes = wristband.durationMinutes == null ? null : Number(wristband.durationMinutes);
    await request("/wristbands/bind", { method: "POST", body: JSON.stringify({ uid, memberId: session.memberId }) });
    session.wristbandUid = uid;
    session.wristbandStatus = "detected";
    if (scanTimer) window.clearTimeout(scanTimer);
    scanTimer = window.setTimeout(() => { screen.value = "success"; }, 500);
  } catch (error) {
    activationError.value = error instanceof Error ? error.message : "手环绑定失败";
  }
};

const handleNativeInput = (target: InputTarget, event: Event) => {
  const value = (event.target as HTMLInputElement).value;
  session[target] = target === "name" ? value.slice(0, 24) : value.replace(/\D/g, "").slice(0, target === "phone" ? 15 : target === "birthYear" ? 4 : 2);
};

const onGlobalKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") { if (overlay.value !== "none") overlay.value = "none"; else closeKeyboard(); }
};
onMounted(() => window.addEventListener("keydown", onGlobalKeydown));
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onGlobalKeydown);
  if (toastTimer) window.clearTimeout(toastTimer);
  if (scanTimer) window.clearTimeout(scanTimer);
});
</script>

<template>
  <main class="kiosk-app" :class="[{ 'keyboard-is-open': keyboardOpen }, `screen-${screen}`]">
    <div class="kiosk-bg" aria-hidden="true"><span class="energy-orb energy-orb--one"></span><span class="energy-orb energy-orb--two"></span><span class="scanline"></span></div>
    <div class="portrait-notice"><KioskIcon name="rotate" :size="38" /><strong>Rotate your screen</strong><span>This experience is designed for a landscape kiosk.</span></div>

    <header class="kiosk-header">
      <div class="kiosk-brand"><span class="kiosk-brand__mark"><i></i><i></i><i></i><i></i></span><div><strong>LED GAME</strong><small>PLAYER STATION</small></div></div>
      <div class="screen-title"><i></i><span>{{ currentTitle }}</span><i></i></div>
      <div class="session-status"><span class="status-light"></span><div><strong>LOCAL SERVICE</strong><small>Reader input + SQLite API</small></div></div>
    </header>

    <div v-if="screen !== 'home' && screen !== 'success'" class="step-track" aria-label="Activation progress">
      <span v-for="step in 3" :key="step" :class="{ active: step <= stepNumber, current: step === stepNumber }"><i>{{ step }}</i><b>{{ ['Phone', 'Profile', 'Wristband'][step - 1] }}</b></span>
    </div>

    <section v-if="screen === 'home'" class="screen screen--home">
      <div class="home-intro"><p class="eyebrow"><span></span> WELCOME TO THE PLAYER STATION <span></span></p><h1>Ready to <em>light up</em><br />the game floor?</h1><p>Activate your wristband in a few simple steps.</p></div>
      <div class="home-actions">
        <button class="feature-card feature-card--primary" type="button" @click="goTo('phone')"><span class="feature-card__glow"></span><span class="feature-card__icon"><WristbandArt :size="68" /></span><span><small>GET STARTED</small><strong>Activate Wristband</strong><b>Register player & pair wristband</b></span><KioskIcon name="arrow" :size="25" /></button>
        <button class="feature-card" type="button" @click="showToast('Player Info Query is not included in this UI change.')"><span class="feature-card__icon"><KioskIcon name="user" :size="48" /></span><span><small>RETURNING PLAYER</small><strong>Player Info Query</strong><b>View an existing player profile</b></span><KioskIcon name="arrow" :size="25" /></button>
      </div>
      <footer class="home-footer"><span><KioskIcon name="info" :size="16" /> Touch a card to begin</span><span>Session data clears when you return home</span></footer>
    </section>

    <section v-else-if="screen === 'phone'" class="screen screen--center" :class="{ 'screen--with-keyboard': keyboardOpen }">
      <div class="phone-layout">
        <div class="screen-copy"><span class="screen-copy__icon"><KioskIcon name="phone" :size="34" /></span><p class="eyebrow">STEP 01 · FIND YOUR PROFILE</p><h1>Enter your<br /><em>phone number</em></h1><p>先查询或创建会员，确认身份后再刷手环。</p></div>
        <div class="tech-panel phone-panel">
          <div class="panel-corners" aria-hidden="true"><i></i><i></i><i></i><i></i></div>
          <label class="kiosk-field" :class="{ focused: activeInput === 'phone', invalid: errors.phone }" data-field="phone"><span>PHONE NUMBER</span><div><KioskIcon name="phone" :size="22" /><input data-input="phone" :value="session.phone" inputmode="none" autocomplete="off" placeholder="Tap to enter number" aria-label="Phone number" @focus="openInput('phone','numeric')" @input="handleNativeInput('phone',$event)" @keydown.enter.prevent="submitPhone" /><small>{{ session.phone.length }}/15</small></div><b v-if="errors.phone"><KioskIcon name="info" :size="15" /> {{ errors.phone }}</b><b v-else>Use 7–15 digits. Spaces are added visually.</b></label>
          <div class="panel-actions"><button class="kiosk-button kiosk-button--secondary" type="button" @click="goTo('home')"><KioskIcon name="back" :size="20" /> Back</button><button class="kiosk-button kiosk-button--primary" type="button" @click="submitPhone">Confirm <KioskIcon name="arrow" :size="20" /></button></div>
        </div>
      </div>
    </section>

    <section v-else-if="screen === 'confirm'" class="screen screen--center">
      <div class="phone-layout member-confirm-layout">
        <div class="screen-copy"><span class="screen-copy__icon"><KioskIcon name="user" :size="34" /></span><p class="eyebrow">STEP 02 · MEMBER FOUND</p><h1>Welcome back,<br /><em>{{ session.name }}</em></h1><p>Confirm this is your player profile, then pair the wristband that was charged at the counter.</p></div>
        <div class="tech-panel member-confirm-card"><div class="panel-corners" aria-hidden="true"><i></i><i></i><i></i><i></i></div><AvatarArt :avatar="selectedAvatar" size="large" /><div><small>EXISTING MEMBER</small><h2>{{ session.name }}</h2><p>{{ session.phone }}</p><span><KioskIcon name="check" :size="16" /> Profile found</span></div><div class="panel-actions"><button class="kiosk-button kiosk-button--secondary" type="button" @click="goTo('phone')"><KioskIcon name="back" :size="20" /> Not me</button><button class="kiosk-button kiosk-button--primary" type="button" @click="confirmExistingMember">Continue <KioskIcon name="arrow" :size="20" /></button></div></div>
      </div>
    </section>

    <section v-else-if="screen === 'register'" class="screen screen--register" :class="{ 'screen--with-keyboard': keyboardOpen }">
      <div class="register-heading"><div><p class="eyebrow">STEP 02 · PLAYER PROFILE</p><h1>Tell us about <em>your player</em></h1></div><p>All fields are used only in this UI session.</p></div>
      <div class="tech-panel register-panel">
        <div class="avatar-field" :class="{ invalid: errors.avatar }" data-field="avatar"><span class="field-label">AVATAR</span><div class="avatar-field__content"><AvatarArt :avatar="selectedAvatar" size="medium" /><div><strong>{{ session.avatarId ? selectedAvatar.label : 'Choose your look' }}</strong><small>{{ session.avatarId ? 'Avatar selected' : 'Required before continuing' }}</small></div><button type="button" aria-label="Choose avatar" @click="openAvatarSource"><KioskIcon name="edit" :size="20" /></button></div><b v-if="errors.avatar" class="field-error">{{ errors.avatar }}</b></div>
        <label class="kiosk-field" :class="{ focused: activeInput === 'name', invalid: errors.name }" data-field="name"><span>PLAYER NAME</span><div><KioskIcon name="user" :size="21" /><input data-input="name" :value="session.name" inputmode="none" autocomplete="off" placeholder="Tap to enter name" aria-label="Player name" @focus="openInput('name','alphabetic')" @input="handleNativeInput('name',$event)" @keydown.enter.prevent="closeKeyboard" /></div><b v-if="errors.name" class="field-error">{{ errors.name }}</b></label>
        <label class="kiosk-field" :class="{ focused: activeInput === 'phone', invalid: errors.phone }" data-field="phone"><span>PHONE NUMBER</span><div><KioskIcon name="phone" :size="21" /><input data-input="phone" :value="session.phone" inputmode="none" autocomplete="off" aria-label="Phone number" @focus="openInput('phone','numeric')" @input="handleNativeInput('phone',$event)" /></div><b v-if="errors.phone" class="field-error">{{ errors.phone }}</b></label>
        <div class="birthday-field" :class="{ invalid: errors.birthday }" data-field="birthday"><span class="field-label">DATE OF BIRTH</span><div class="birthday-inputs"><label :class="{ focused: activeInput === 'birthYear' }"><input data-input="birthYear" :value="session.birthYear" inputmode="none" placeholder="YYYY" aria-label="Birth year" @focus="openInput('birthYear','numeric')" @input="handleNativeInput('birthYear',$event)" /><small>YEAR</small></label><i>/</i><label :class="{ focused: activeInput === 'birthMonth' }"><input data-input="birthMonth" :value="session.birthMonth" inputmode="none" placeholder="MM" aria-label="Birth month" @focus="openInput('birthMonth','numeric')" @input="handleNativeInput('birthMonth',$event)" /><small>MONTH</small></label><i>/</i><label :class="{ focused: activeInput === 'birthDay' }"><input data-input="birthDay" :value="session.birthDay" inputmode="none" placeholder="DD" aria-label="Birth day" @focus="openInput('birthDay','numeric')" @input="handleNativeInput('birthDay',$event)" /><small>DAY</small></label></div><b v-if="errors.birthday" class="field-error">{{ errors.birthday }}</b></div>
        <fieldset class="gender-field" :class="{ invalid: errors.gender }" data-field="gender"><legend>GENDER</legend><div><button v-for="item in [{id:'male',label:'Male'}, {id:'female',label:'Female'}, {id:'secret',label:'Prefer not to say'}]" :key="item.id" type="button" :class="{ active: session.gender === item.id }" @click="session.gender = item.id as typeof session.gender; delete errors.gender"><span><i></i></span>{{ item.label }}</button></div><b v-if="errors.gender" class="field-error">{{ errors.gender }}</b></fieldset>
        <div class="panel-actions register-actions"><button class="kiosk-button kiosk-button--secondary" type="button" @click="goTo('phone')"><KioskIcon name="back" :size="20" /> Back</button><span><KioskIcon name="signal" :size="17" /> Next: pair a wristband</span><button class="kiosk-button kiosk-button--primary" type="button" @click="submitRegistration">Next Step <KioskIcon name="arrow" :size="20" /></button></div>
      </div>
    </section>

    <section v-else-if="screen === 'swipe'" class="screen screen--swipe">
      <div class="player-chip"><AvatarArt :avatar="selectedAvatar" size="small" /><span><small>PLAYER</small><strong>{{ session.name }}</strong></span></div>
      <div class="swipe-layout"><div class="reader-visual" :class="{ detected: session.wristbandStatus === 'detected' }"><span class="reader-ring reader-ring--one"></span><span class="reader-ring reader-ring--two"></span><span class="reader-ring reader-ring--three"></span><div class="wristband-symbol"><KioskIcon v-if="session.wristbandStatus === 'detected'" name="check" :size="72" /><WristbandArt v-else :size="132" /></div><span class="reader-scan"></span></div><div class="swipe-copy"><p class="eyebrow">STEP 03 · PAIR DEVICE</p><h1>{{ session.wristbandStatus === 'detected' ? 'Wristband bound' : 'Scan your charged wristband' }}</h1><p>{{ session.wristbandStatus === 'detected' ? 'Member and wristband are now linked.' : '请把柜台已充时的手环放到读卡器上。' }}</p><label class="demo-uid-field"><span>READER UID</span><input v-model="session.wristbandUid" inputmode="numeric" autocomplete="off" maxlength="32" autofocus :disabled="session.wristbandStatus === 'detected'" placeholder="等待读卡器输入" @input="activationError = ''" @keydown.enter.prevent="scanWristband" /></label><p v-if="activationError" class="activation-error"><KioskIcon name="info" :size="16" />{{ activationError }}</p><div class="waiting-status" :class="{ detected: session.wristbandStatus === 'detected' }"><span></span>{{ session.wristbandStatus === 'detected' ? 'Wristband bound · READY' : 'Waiting for reader…' }}</div></div></div>
      <div class="swipe-actions"><button class="kiosk-button kiosk-button--secondary" type="button" :disabled="session.wristbandStatus === 'detected'" @click="goTo(profileScreen)"><KioskIcon name="back" :size="20" /> Back</button><button class="kiosk-button kiosk-button--primary" type="button" :disabled="session.wristbandStatus === 'detected'" @click="scanWristband"><KioskIcon name="signal" :size="19" /> Bind scanned wristband</button></div>
      <p class="demo-note"><KioskIcon name="info" :size="15" /> UID comes from the keyboard-wedge reader. Timing starts only at the first game-system swipe.</p>
    </section>

    <section v-else class="screen screen--success">
      <div class="success-burst" aria-hidden="true"><i v-for="n in 12" :key="n" :style="{ '--i': n }"></i></div>
      <div class="success-layout"><div class="success-mark"><span class="success-ring"></span><span><KioskIcon name="check" :size="70" /></span></div><div class="success-copy"><p class="eyebrow"><KioskIcon name="spark" :size="16" /> WRISTBAND READY</p><h1>Binding<br /><em>Successful!</em></h1><p>Your member and wristband are linked. Game time begins only after the first swipe at a game system.</p><div class="success-summary"><AvatarArt :avatar="selectedAvatar" size="small" /><div><small>PLAYER</small><strong>{{ session.name }}</strong><b>{{ session.wristbandUid }}</b></div><i></i><div><small>PURCHASED PLAY TIME</small><strong>{{ session.durationMinutes }} <b>min</b></strong></div></div><div class="simulation-label"><KioskIcon name="info" :size="15" /> Saved by local member-admin backend and SQLite</div><button class="kiosk-button kiosk-button--primary kiosk-button--return" type="button" @click="resetSession">Return Home <KioskIcon name="arrow" :size="20" /></button></div></div>
    </section>

    <SoftKeyboard v-if="keyboardOpen" :layout="keyboardLayout" :field-label="activeFieldLabel" @key="handleKeyboardKey" @backspace="backspace" @clear="clearInput" @done="closeKeyboard" @close="closeKeyboard" />

    <div v-if="overlay === 'avatar-source'" class="modal-backdrop" @mousedown.self="overlay = 'none'">
      <section class="source-modal tech-panel" role="dialog" aria-modal="true" aria-label="Choose avatar source"><header><div><p class="eyebrow">AVATAR SOURCE</p><h2>How would you like to set your avatar?</h2></div><button type="button" aria-label="Close avatar options" @click="overlay = 'none'"><KioskIcon name="close" :size="22" /></button></header><div class="source-options"><button type="button" @click="openAvatarLibrary"><span><KioskIcon name="library" :size="32" /></span><div><small>OFFLINE COLLECTION</small><strong>Set from Library</strong><p>Choose from 20 built-in player avatars.</p></div><KioskIcon name="arrow" :size="21" /></button><button type="button" @click="takePhoto"><span><KioskIcon name="camera" :size="32" /></span><div><small>DEVICE CAMERA</small><strong>Take Photo</strong><p>Camera is not connected in this UI demo.</p></div><KioskIcon name="arrow" :size="21" /></button></div></section>
    </div>

    <div v-if="overlay === 'avatar-library'" class="modal-backdrop modal-backdrop--library">
      <section class="avatar-library tech-panel" role="dialog" aria-modal="true" aria-label="Select an avatar"><header><div><p class="eyebrow">PLAYER IDENTITY</p><h2>Select an Avatar</h2><p>Choose a character that feels like you.</p></div><div class="avatar-preview"><AvatarArt :avatar="pendingAvatar" size="small" /><span><small>SELECTED</small><strong>{{ pendingAvatar.label }}</strong></span></div></header><div class="avatar-grid"><button v-for="avatar in avatars" :key="avatar.id" type="button" :class="{ active: pendingAvatarId === avatar.id }" :aria-pressed="pendingAvatarId === avatar.id" @click="pendingAvatarId = avatar.id"><AvatarArt :avatar="avatar" size="medium" /><span>{{ avatar.label }}</span><i v-if="pendingAvatarId === avatar.id"><KioskIcon name="check" :size="15" /></i></button></div><footer><button class="kiosk-button kiosk-button--secondary" type="button" @click="overlay = 'none'"><KioskIcon name="close" :size="18" /> Cancel</button><button class="kiosk-button kiosk-button--primary" type="button" @click="confirmAvatar">Confirm Avatar <KioskIcon name="check" :size="18" /></button></footer></section>
    </div>

    <Transition name="toast"><div v-if="toast" class="kiosk-toast" role="status"><KioskIcon name="info" :size="19" /><span>{{ toast }}</span></div></Transition>
  </main>
</template>

<script setup lang="ts">
import KioskIcon from "./KioskIcon.vue";
import type { KeyboardLayout } from "../types";

const props = defineProps<{ layout: KeyboardLayout; fieldLabel: string }>();
const emit = defineEmits<{ key: [value: string]; backspace: []; clear: []; done: []; close: [] }>();
const alphaRows = ["QWERTYUIOP".split(""), "ASDFGHJKL".split(""), "ZXCVBNM".split("")];
const numericKeys = ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"];
</script>

<template>
  <section class="soft-keyboard" :class="`soft-keyboard--${props.layout}`" aria-label="On-screen keyboard">
    <header class="soft-keyboard__header">
      <div><KioskIcon name="keyboard" :size="20" /><span>Entering <strong>{{ props.fieldLabel }}</strong></span></div>
      <div class="soft-keyboard__utilities"><button type="button" @click="emit('clear')">Clear</button><button type="button" aria-label="Close keyboard" @click="emit('close')"><KioskIcon name="close" :size="19" /></button></div>
    </header>
    <div v-if="props.layout === 'numeric'" class="numeric-keys">
      <button v-for="key in numericKeys" :key="key" type="button" :aria-label="key" @click="emit('key', key)">{{ key }}</button>
      <button class="key-action" type="button" aria-label="Backspace" @click="emit('backspace')">⌫</button>
      <button class="key-done" type="button" @click="emit('done')">Done <KioskIcon name="check" :size="18" /></button>
    </div>
    <div v-else class="alpha-keys">
      <div v-for="(row, rowIndex) in alphaRows" :key="rowIndex" class="alpha-row">
        <button v-for="key in row" :key="key" type="button" :aria-label="key" @click="emit('key', key)">{{ key }}</button>
        <button v-if="rowIndex === 2" class="key-action" type="button" aria-label="Backspace" @click="emit('backspace')">⌫</button>
      </div>
      <div class="alpha-row alpha-row--bottom"><button class="space-key" type="button" @click="emit('key', ' ')">Space</button><button class="key-done" type="button" @click="emit('done')">Done <KioskIcon name="check" :size="18" /></button></div>
    </div>
  </section>
</template>

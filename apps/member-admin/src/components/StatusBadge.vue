<script setup lang="ts">
import type { StatusTone } from "../types";

withDefaults(defineProps<{ tone?: StatusTone; dot?: boolean }>(), {
  tone: "neutral",
  dot: true,
});
</script>

<template>
  <span class="status-badge" :class="`status-badge--${tone}`">
    <span v-if="dot" class="status-badge__dot" aria-hidden="true"></span>
    <slot />
  </span>
</template>

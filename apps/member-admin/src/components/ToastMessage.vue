<script setup lang="ts">
import AppIcon from "./AppIcon.vue";

defineProps<{ message: string }>();
</script>

<template>
  <Teleport to="body">
    <div class="toast" role="status" aria-live="polite">
      <span class="toast__icon"><AppIcon name="check" :size="17" /></span>
      <span>{{ message }}</span>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "./AppIcon.vue";
import { captureFocusReturnTarget, restoreFocusReturnTarget } from "../focusLifecycle";

const props = defineProps<{ title: string; eyebrow?: string }>();
const emit = defineEmits<{ close: [] }>();
const panel = ref<HTMLElement | null>(null);
const returnFocusTarget = captureFocusReturnTarget(document.activeElement);

const onKeydown = (event: KeyboardEvent) => {
  if (event.key === "Escape") emit("close");
};

onMounted(async () => {
  window.addEventListener("keydown", onKeydown);
  await nextTick();
  panel.value?.focus();
});
onBeforeUnmount(() => {
  window.removeEventListener("keydown", onKeydown);
  void nextTick(() => restoreFocusReturnTarget(returnFocusTarget));
});
</script>

<template>
  <Teleport to="body">
    <div class="overlay drawer-overlay" role="presentation" @mousedown.self="emit('close')">
      <aside ref="panel" class="side-drawer glass-panel" role="dialog" aria-modal="true" :aria-label="props.title" tabindex="-1">
        <header class="side-drawer__header">
          <div>
            <p v-if="props.eyebrow" class="section-eyebrow">{{ props.eyebrow }}</p>
            <h2>{{ props.title }}</h2>
          </div>
          <button class="icon-button" type="button" aria-label="关闭详情" @click="emit('close')">
            <AppIcon name="close" />
          </button>
        </header>
        <div class="side-drawer__content"><slot /></div>
        <footer v-if="$slots.footer" class="side-drawer__footer"><slot name="footer" /></footer>
      </aside>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import AppIcon from "./AppIcon.vue";
import { captureFocusReturnTarget, restoreFocusReturnTarget } from "../focusLifecycle";

const props = withDefaults(defineProps<{ title: string; description?: string; size?: "small" | "medium" | "large" }>(), {
  size: "medium",
});
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
    <div class="overlay" role="presentation" @mousedown.self="emit('close')">
      <section
        ref="panel"
        class="modal glass-panel"
        :class="`modal--${props.size}`"
        role="dialog"
        aria-modal="true"
        :aria-label="props.title"
        tabindex="-1"
      >
        <header class="modal__header">
          <div>
            <h2>{{ props.title }}</h2>
            <p v-if="props.description">{{ props.description }}</p>
          </div>
          <button class="icon-button" type="button" aria-label="关闭弹窗" @click="emit('close')">
            <AppIcon name="close" />
          </button>
        </header>
        <div class="modal__content"><slot /></div>
        <footer v-if="$slots.footer" class="modal__footer"><slot name="footer" /></footer>
      </section>
    </div>
  </Teleport>
</template>

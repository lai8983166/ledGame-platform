<script setup lang="ts">
import type { CSSProperties } from "vue";
import type { AvatarSpec } from "../types";

const props = withDefaults(defineProps<{ avatar: AvatarSpec; size?: "small" | "medium" | "large" }>(), { size: "medium" });
const avatarStyle = {
  "--skin": props.avatar.skin,
  "--hair": props.avatar.hair,
  "--shirt": props.avatar.shirt,
  "--accent": props.avatar.accent,
} as CSSProperties;
</script>

<template>
  <div class="avatar-art" :class="[`avatar-art--${props.size}`, `hair--${props.avatar.hairStyle}`]" :style="avatarStyle" :aria-label="`${props.avatar.label} avatar`" role="img">
    <div class="avatar-art__halo"></div>
    <div v-if="props.avatar.accessory === 'cap'" class="avatar-art__cap"></div>
    <div class="avatar-art__neck"></div>
    <div class="avatar-art__body"><span></span></div>
    <div class="avatar-art__head">
      <div class="avatar-art__hair"></div>
      <span class="avatar-art__ear avatar-art__ear--left"></span><span class="avatar-art__ear avatar-art__ear--right"></span>
      <span class="avatar-art__eye avatar-art__eye--left"></span><span class="avatar-art__eye avatar-art__eye--right"></span>
      <span class="avatar-art__brow avatar-art__brow--left"></span><span class="avatar-art__brow avatar-art__brow--right"></span>
      <span class="avatar-art__nose"></span><span class="avatar-art__smile"></span>
      <span v-if="props.avatar.accessory === 'freckles'" class="avatar-art__freckles"></span>
      <span v-if="props.avatar.accessory === 'headband'" class="avatar-art__headband"></span>
      <span v-if="props.avatar.accessory === 'glasses'" class="avatar-art__glasses"><i></i><i></i></span>
    </div>
  </div>
</template>

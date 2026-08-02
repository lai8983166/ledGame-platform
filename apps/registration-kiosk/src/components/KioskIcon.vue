<script setup lang="ts">
const props = withDefaults(defineProps<{ name: string; size?: number }>(), { size: 28 });
const icons: Record<string, string[]> = {
  wristband: ["M9 2h6l1 5H8z", "M8 17h8l-1 5H9z", "M8 7h8a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2z", "M12 10v2.4l1.6 1"],
  user: ["M12 13a5 5 0 1 0 0-10 5 5 0 0 0 0 10z", "M3 22a9 9 0 0 1 18 0"],
  phone: ["M7 2h10a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M10 18h4"],
  arrow: ["M5 12h14", "m13 6 6 6-6 6"],
  back: ["M19 12H5", "m11 18-6-6 6-6"],
  check: ["m20 6-11 11-5-5"],
  close: ["M18 6 6 18", "m6 6 12 12"],
  edit: ["M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"],
  library: ["M4 4h6v16H4z", "M14 4h6v16h-6z", "M7 8h.01", "M17 8h.01"],
  camera: ["M3 7h4l2-3h6l2 3h4v13H3z", "M12 17a4 4 0 1 0 0-8 4 4 0 0 0 0 8z"],
  delete: ["M4 7h16", "M9 7V4h6v3", "M18 7l-1 14H7L6 7", "M10 11v6", "M14 11v6"],
  keyboard: ["M3 6h18v12H3z", "M7 10h.01", "M11 10h.01", "M15 10h.01", "M19 10h.01", "M7 14h.01", "M11 14h5"],
  timer: ["M12 22a9 9 0 1 0 0-18 9 9 0 0 0 0 18z", "M12 7v5l3 2", "M9 2h6"],
  signal: ["M5 12.5a10 10 0 0 1 14 0", "M8.5 16a5 5 0 0 1 7 0", "M12 20h.01"],
  spark: ["m12 2 1.4 4.6L18 8l-4.6 1.4L12 14l-1.4-4.6L6 8l4.6-1.4z", "m19 15 .7 2.3L22 18l-2.3.7L19 21l-.7-2.3L16 18l2.3-.7z"],
  info: ["M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20z", "M12 11v6", "M12 7h.01"],
  rotate: ["M4 7h10a6 6 0 0 1 6 6", "m4 3-4 4 4 4", "M20 17H10a6 6 0 0 1-6-6", "m20 21 4-4-4-4"],
};
</script>

<template>
  <svg aria-hidden="true" fill="none" :height="props.size" :width="props.size" viewBox="0 0 24 24" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
    <path v-for="path in icons[props.name] ?? icons.spark" :key="path" :d="path" />
  </svg>
</template>

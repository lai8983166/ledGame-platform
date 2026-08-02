<script setup lang="ts">
withDefaults(defineProps<{ size?: number; label?: string }>(), {
  size: 96,
  label: "LED wristband",
});
</script>

<template>
  <svg
    class="wristband-art"
    :width="size"
    :height="size"
    viewBox="0 0 160 160"
    fill="none"
    role="img"
    :aria-label="label"
  >
    <defs>
      <linearGradient id="band-shell" x1="26" y1="24" x2="137" y2="141" gradientUnits="userSpaceOnUse">
        <stop stop-color="#2D72DF" />
        <stop offset="0.5" stop-color="#174DAE" />
        <stop offset="1" stop-color="#0B286F" />
      </linearGradient>
      <linearGradient id="device-shell" x1="20" y1="19" x2="91" y2="135" gradientUnits="userSpaceOnUse">
        <stop stop-color="#2D72DD" />
        <stop offset="1" stop-color="#0A2B74" />
      </linearGradient>
      <linearGradient id="device-screen" x1="34" y1="32" x2="76" y2="124" gradientUnits="userSpaceOnUse">
        <stop stop-color="#0D244E" />
        <stop offset="1" stop-color="#050E27" />
      </linearGradient>
      <linearGradient id="screen-glow" x1="31" y1="31" x2="79" y2="126" gradientUnits="userSpaceOnUse">
        <stop stop-color="#8BFFF8" />
        <stop offset="0.48" stop-color="#31EBED" />
        <stop offset="1" stop-color="#36A9F4" />
      </linearGradient>
      <filter id="cyan-glow" x="-50%" y="-50%" width="200%" height="200%">
        <feGaussianBlur stdDeviation="3.4" result="blur" />
        <feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge>
      </filter>
      <filter id="band-shadow" x="-30%" y="-30%" width="170%" height="180%">
        <feDropShadow dx="0" dy="8" stdDeviation="8" flood-color="#02091A" flood-opacity="0.5" />
      </filter>
    </defs>

    <g filter="url(#band-shadow)">
      <path
        d="M45 27C72 7 114 12 135 43C158 77 141 122 108 142C79 159 42 147 25 118C6 85 17 49 45 27Z"
        stroke="url(#band-shell)"
        stroke-width="21"
        stroke-linecap="round"
        stroke-linejoin="round"
      />
      <path
        d="M50 30C75 14 111 18 129 45C148 74 135 113 106 132"
        stroke="#4C8BEB"
        stroke-width="2.2"
        stroke-linecap="round"
        opacity="0.55"
      />
      <path
        d="M113 139C83 153 49 139 33 112"
        stroke="#071D55"
        stroke-width="4"
        stroke-linecap="round"
        opacity="0.75"
      />

      <g fill="#071D55" stroke="#3977D4" stroke-width="1.2">
        <ellipse cx="131" cy="57" rx="4" ry="5" transform="rotate(-20 131 57)" />
        <ellipse cx="137" cy="72" rx="4" ry="5" transform="rotate(-10 137 72)" />
        <ellipse cx="138" cy="88" rx="4" ry="5" />
        <ellipse cx="134" cy="104" rx="4" ry="5" transform="rotate(12 134 104)" />
        <ellipse cx="126" cy="119" rx="4" ry="5" transform="rotate(25 126 119)" />
      </g>

      <path d="M103 137L118 127L130 135L116 148Z" fill="#0A286F" stroke="#3472D0" stroke-width="2" />
      <path d="M111 133L120 128L126 135L117 142Z" fill="#1D55B3" />
    </g>

    <g transform="rotate(-17 54 80)" filter="url(#band-shadow)">
      <rect x="16" y="20" width="75" height="116" rx="37.5" fill="url(#device-shell)" stroke="#4B8EF0" stroke-width="2.4" />
      <rect x="24" y="28" width="59" height="100" rx="29.5" fill="#0B1E49" stroke="#1D5BAD" stroke-width="2" />
      <rect x="28" y="32" width="51" height="92" rx="25.5" fill="url(#device-screen)" />
      <rect x="27" y="31" width="53" height="94" rx="26.5" stroke="url(#screen-glow)" stroke-width="3.8" filter="url(#cyan-glow)" />
      <path d="M35 46C43 36 60 32 70 38" stroke="#C0FFFB" stroke-width="2" stroke-linecap="round" opacity="0.5" />
      <circle cx="54" cy="78" r="4" fill="#6CFFF5" filter="url(#cyan-glow)" />
      <path d="M43 94H65" stroke="#2AD9DF" stroke-width="2.4" stroke-linecap="round" opacity="0.8" />
      <path d="M47 101H61" stroke="#2D82D8" stroke-width="2" stroke-linecap="round" opacity="0.7" />
    </g>
  </svg>
</template>

<style scoped>
.wristband-art {
  display: block;
  max-width: 100%;
  overflow: visible;
  filter: drop-shadow(0 8px 16px rgba(0, 8, 30, 0.32));
}
</style>

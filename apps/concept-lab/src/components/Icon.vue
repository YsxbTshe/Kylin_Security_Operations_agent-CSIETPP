<script setup>
defineProps({ name: { type: String, required: true }, size: { type: Number, default: 18 } })

const paths = {
  task: ['M4 4h16v16H4z', 'M8 9h8M8 13h5'],
  topology: ['M12 3v5M5 8h14M5 8v5M19 8v5', 'M2 13h6v6H2zM9 17h6v4H9zM16 13h6v6h-6z'],
  screen: ['M3 5h18v12H3z', 'M8 21h8M12 17v4M7 13l3-3 2 2 4-5'],
  access: ['M12 3 4 6v5c0 5 3.4 8.4 8 10 4.6-1.6 8-5 8-10V6z', 'm9 12 2 2 4-5'],
  memory: ['M9 4a3 3 0 0 1 3 3v10a3 3 0 0 1-6 0 3 3 0 0 1-2-5 3 3 0 0 1 1-5 3 3 0 0 1 4-3z', 'M15 4a3 3 0 0 0-3 3v10a3 3 0 0 0 6 0 3 3 0 0 0 2-5 3 3 0 0 0-1-5 3 3 0 0 0-4-3z'],
  roadmap: ['M4 19V5M4 18c4-7 8 2 16-11', 'm16 7 4 0v4'],
  agent: ['M12 3v3M6.5 7.5 4.5 6M17.5 7.5 19.5 6', 'M5 10h14v9H5z', 'M9 14h.01M15 14h.01M9 17h6'],
  chevron: ['m9 18 6-6-6-6'],
  check: ['m5 12 4 4L19 6'],
  play: ['m8 5 11 7-11 7z'],
  pause: ['M9 5v14M15 5v14'],
  reset: ['M20 11a8 8 0 1 1-2.3-5.7L20 8', 'M20 3v5h-5'],
  cube: ['m12 3 8 4.5v9L12 21l-8-4.5v-9z', 'm4 7.5 8 4.5 8-4.5M12 12v9'],
  database: ['M5 6c0-2 14-2 14 0v12c0 2-14 2-14 0z', 'M5 6c0 2 14 2 14 0M5 12c0 2 14 2 14 0'],
  shield: ['M12 3 4 6v5c0 5 3.4 8.4 8 10 4.6-1.6 8-5 8-10V6z'],
  close: ['M6 6l12 12M18 6 6 18'],
  terminal: ['m5 7 4 5-4 5M12 17h7'],
  radio: ['M8.5 8.5a5 5 0 0 0 0 7M15.5 8.5a5 5 0 0 1 0 7', 'M5.5 5.5a9 9 0 0 0 0 13M18.5 5.5a9 9 0 0 1 0 13M12 12h.01'],
  moon: ['M20 15.5A9 9 0 1 1 8.5 4 7 7 0 0 0 20 15.5z'],
  spark: ['m12 3 1.7 5.3L19 10l-5.3 1.7L12 17l-1.7-5.3L5 10l5.3-1.7z'],
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path v-for="path in (paths[name] || [])" :key="path" :d="path" />
  </svg>
</template>

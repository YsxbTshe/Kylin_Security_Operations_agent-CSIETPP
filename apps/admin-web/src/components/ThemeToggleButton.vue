<script setup lang="ts">
import { Check, Moon, Sun } from 'lucide-vue-next'
import { BRAND_PRESETS, useThemeStore } from '../stores/theme'
import Popover from '../ui/Popover.vue'

defineProps<{ text?: boolean }>()

const themeStore = useThemeStore()
</script>

<template>
  <Popover side="bottom" align="end">
    <template #trigger>
      <button type="button" class="theme-toggle" :class="{ text }">
        <component :is="themeStore.isDark ? Sun : Moon" class="theme-toggle__ic" />
        <span v-if="text">{{ themeStore.isDark ? '浅色模式' : '深色模式' }}</span>
      </button>
    </template>

    <div class="tp">
      <div class="tp-section">
        <div class="tp-label">外观</div>
        <div class="tp-modes">
          <button
            type="button"
            class="tp-mode"
            :class="{ on: !themeStore.isDark }"
            @click="themeStore.setTheme('light')"
          >
            <Sun class="tp-mode__ic" />
            <span>浅色</span>
            <Check v-if="!themeStore.isDark" class="tp-mode__check" />
          </button>
          <button
            type="button"
            class="tp-mode"
            :class="{ on: themeStore.isDark }"
            @click="themeStore.setTheme('dark')"
          >
            <Moon class="tp-mode__ic" />
            <span>深色</span>
            <Check v-if="themeStore.isDark" class="tp-mode__check" />
          </button>
        </div>
      </div>

      <div class="tp-section">
        <div class="tp-label">强调色</div>
        <div class="tp-swatches">
          <button
            v-for="p in BRAND_PRESETS"
            :key="p.key"
            type="button"
            class="tp-swatch"
            :class="{ on: themeStore.brand === p.key }"
            :title="p.name"
            :style="{
              background: themeStore.isDark
                ? `oklch(0.74 0.12 ${p.hue})`
                : `oklch(0.5 0.13 ${p.hue})`,
            }"
            @click="themeStore.setBrand(p.key)"
          >
            <Check v-if="themeStore.brand === p.key" class="tp-swatch__check" />
          </button>
        </div>
      </div>
    </div>
  </Popover>
</template>

<style scoped>
.theme-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 36px;
  width: 36px;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  color: var(--text-2);
  cursor: pointer;
  transition: border-color 0.13s, color 0.13s, background 0.13s;
}
.theme-toggle.text {
  width: auto;
  padding: 0 12px;
  font-size: 13px;
}
.theme-toggle:hover {
  color: var(--text);
  border-color: var(--border-2);
  background: var(--surface-2);
}
.theme-toggle__ic {
  width: 17px;
  height: 17px;
}

.tp {
  width: 232px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tp-label {
  font-size: 11px;
  letter-spacing: 0.06em;
  color: var(--text-3);
  margin-bottom: 8px;
}
.tp-modes {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}
.tp-mode {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  background: var(--surface);
  color: var(--text-2);
  font-size: 13px;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.13s, background 0.13s, color 0.13s;
}
.tp-mode:hover {
  background: var(--surface-2);
  color: var(--text);
}
.tp-mode.on {
  border-color: var(--accent);
  color: var(--accent);
  background: var(--accent-soft);
}
.tp-mode__ic {
  width: 15px;
  height: 15px;
  flex: none;
}
.tp-mode__check {
  width: 14px;
  height: 14px;
  margin-left: auto;
}

.tp-swatches {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 8px;
}
.tp-swatch {
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: 50%;
  border: 2px solid transparent;
  cursor: pointer;
  position: relative;
  padding: 0;
  display: grid;
  place-items: center;
  color: #fff;
  box-shadow: 0 0 0 1px var(--border);
  transition: transform 0.13s, box-shadow 0.13s;
}
.tp-swatch:hover {
  transform: scale(1.07);
}
.tp-swatch.on {
  box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px var(--accent);
}
.tp-swatch__check {
  width: 14px;
  height: 14px;
}
</style>

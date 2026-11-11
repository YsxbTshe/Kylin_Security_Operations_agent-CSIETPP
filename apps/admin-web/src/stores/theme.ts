import { defineStore } from 'pinia'

const THEME_KEY = 'kylin.ops.theme'
const BRAND_KEY = 'kylin.ops.brand'

export type ThemeMode = 'light' | 'dark'

export interface BrandPreset {
  key: string
  name: string
  hue: number
}

export const BRAND_PRESETS: BrandPreset[] = [
  { key: 'indigo', name: '靛蓝', hue: 262 },
  { key: 'cobalt', name: '钴蓝', hue: 248 },
  { key: 'teal', name: '青绿', hue: 195 },
  { key: 'emerald', name: '翠绿', hue: 162 },
  { key: 'amber', name: '琥珀', hue: 70 },
  { key: 'rose', name: '玫红', hue: 18 },
]

const DEFAULT_BRAND = 'indigo'

function findPreset(key: string): BrandPreset {
  return BRAND_PRESETS.find((p) => p.key === key) ?? BRAND_PRESETS[0]
}

function resolveInitialTheme(): ThemeMode {
  const saved = window.localStorage.getItem(THEME_KEY)
  if (saved === 'light' || saved === 'dark') return saved
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

function applyTheme(mode: ThemeMode) {
  const root = document.documentElement
  root.classList.toggle('dark', mode === 'dark')
  root.dataset.theme = mode
  root.style.colorScheme = mode
}

function applyBrand(hue: number, mode: ThemeMode) {
  const root = document.documentElement
  if (mode === 'dark') {
    root.style.setProperty('--accent', `oklch(0.74 0.12 ${hue})`)
    root.style.setProperty('--accent-2', `oklch(0.82 0.1 ${hue})`)
    root.style.setProperty('--accent-soft', `oklch(0.74 0.12 ${hue} / 0.18)`)
    root.style.setProperty('--accent-ring', `oklch(0.74 0.12 ${hue} / 0.3)`)
  } else {
    root.style.setProperty('--accent', `oklch(0.5 0.13 ${hue})`)
    root.style.setProperty('--accent-2', `oklch(0.42 0.14 ${hue})`)
    root.style.setProperty('--accent-soft', `oklch(0.5 0.13 ${hue} / 0.09)`)
    root.style.setProperty('--accent-ring', `oklch(0.5 0.13 ${hue} / 0.22)`)
  }
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    mode: 'light' as ThemeMode,
    brand: DEFAULT_BRAND,
    initialized: false,
  }),
  getters: {
    isDark: (state) => state.mode === 'dark',
    currentBrand(state): BrandPreset {
      return findPreset(state.brand)
    },
  },
  actions: {
    initialize() {
      if (!this.initialized) {
        this.mode = resolveInitialTheme()
        const savedBrand = window.localStorage.getItem(BRAND_KEY)
        this.brand = findPreset(savedBrand ?? DEFAULT_BRAND).key
        this.initialized = true
      }
      applyTheme(this.mode)
      applyBrand(findPreset(this.brand).hue, this.mode)
    },
    setTheme(mode: ThemeMode) {
      this.mode = mode
      window.localStorage.setItem(THEME_KEY, mode)
      applyTheme(mode)
      applyBrand(findPreset(this.brand).hue, mode)
    },
    toggleTheme() {
      this.setTheme(this.mode === 'dark' ? 'light' : 'dark')
    },
    setBrand(key: string) {
      const p = findPreset(key)
      this.brand = p.key
      window.localStorage.setItem(BRAND_KEY, p.key)
      applyBrand(p.hue, this.mode)
    },
  },
})

import { defineStore } from 'pinia'

export type ThemeMode = 'light' | 'dark' | 'system'
export type AccentTone = 'forest' | 'ocean' | 'plum' | 'amber' | 'graphite'
export type Density = 'comfortable' | 'compact'
export type MotionLevel = 'standard' | 'reduced' | 'off'
export type RadiusStyle = 'modern' | 'crisp'

export const themeOptions: { value: ThemeMode; label: string }[] = [
  { value: 'light', label: '浅色' },
  { value: 'dark', label: '深色' },
  { value: 'system', label: '跟随系统' },
]

export const accentOptions: { value: AccentTone; label: string; description: string; swatch: string; accent: string; surface: string }[] = [
  { value: 'forest', label: '森林暖阳', description: '深绿与日光，慢慢长成自己的风景', swatch: '#255643', accent: '#ebc76b', surface: '#f5f3ec' },
  { value: 'ocean', label: '青瓷微风', description: '安静、自然、适合专注', swatch: '#39776d', accent: '#d26e50', surface: '#e8eee9' },
  { value: 'plum', label: '莓果晚霞', description: '柔和、充满生命力', swatch: '#a95468', accent: '#c9813f', surface: '#f3e4e3' },
  { value: 'amber', label: '纸页暖光', description: '朴素、松弛、像一本手账', swatch: '#9b663d', accent: '#64856a', surface: '#f1e6d3' },
  { value: 'graphite', label: '木炭静夜', description: '克制、稳重、保留温度', swatch: '#5d5550', accent: '#b9624c', surface: '#eae6e1' },
]

export const densityOptions: { value: Density; label: string }[] = [
  { value: 'comfortable', label: '舒适' },
  { value: 'compact', label: '紧凑' },
]

export const motionOptions: { value: MotionLevel; label: string }[] = [
  { value: 'standard', label: '标准' },
  { value: 'reduced', label: '减弱' },
  { value: 'off', label: '关闭' },
]

export const radiusOptions: { value: RadiusStyle; label: string }[] = [
  { value: 'modern', label: '现代' },
  { value: 'crisp', label: '克制' },
]

const appearanceKey = 'better-self:appearance'

type AppearanceState = {
  theme: ThemeMode
  accent: AccentTone
  density: Density
  motion: MotionLevel
  radius: RadiusStyle
  hydrated: boolean
}

function storageAvailable() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

export const useAppearanceStore = defineStore('appearance', {
  state: (): AppearanceState => ({
    theme: 'light',
    accent: 'forest',
    density: 'comfortable',
    motion: 'standard',
    radius: 'modern',
    hydrated: false,
  }),
  actions: {
    hydrate() {
      if (this.hydrated) return
      if (storageAvailable()) {
        const saved = window.localStorage.getItem(appearanceKey)
        if (saved) {
          try {
            Object.assign(this, JSON.parse(saved))
          } catch {
            window.localStorage.removeItem(appearanceKey)
          }
        }
      }
      this.hydrated = true
      this.apply()
      this.$subscribe(() => {
        if (storageAvailable()) {
          window.localStorage.setItem(appearanceKey, JSON.stringify({
            theme: this.theme,
            accent: this.accent,
            density: this.density,
            motion: this.motion,
            radius: this.radius,
          }))
        }
        this.apply()
      })
    },
    setTheme(theme: ThemeMode) {
      this.theme = theme
    },
    setAccent(accent: AccentTone) {
      this.accent = accent
    },
    setDensity(density: Density) {
      this.density = density
    },
    setMotion(motion: MotionLevel) {
      this.motion = motion
    },
    setRadius(radius: RadiusStyle) {
      this.radius = radius
    },
    reset() {
      this.theme = 'light'
      this.accent = 'forest'
      this.density = 'comfortable'
      this.motion = 'standard'
      this.radius = 'modern'
    },
    apply() {
      if (typeof document === 'undefined') return
      const root = document.documentElement
      root.dataset.theme = this.theme
      root.dataset.accent = this.accent
      root.dataset.density = this.density
      root.dataset.motion = this.motion
      root.dataset.radius = this.radius
    },
  },
})

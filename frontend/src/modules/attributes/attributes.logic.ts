import { computed, ref, type Component } from 'vue'
import { Brain, BriefcaseBusiness, HeartHandshake, Sparkles, Sprout } from 'lucide-vue-next'
import { api } from '../../shared/api/client'

export type AttributeRow = {
  code: string
  name: string
  dimensionName: string
  description: string
  experience: number
  level: number
  radarScore: number
  currentLevelExperience: number
  nextLevelExperience?: number | null
  experienceToNextLevel: number
}

export type AttributesOverview = {
  totalExperience: number
  overallLevel: number
  attributes: AttributeRow[]
}

export const attributeIcons: Record<string, Component> = {
  KNOWLEDGE: Brain,
  HEALTH: Sprout,
  CAREER: BriefcaseBusiness,
  RELATIONSHIP: HeartHandshake,
  WELLBEING: Sparkles,
}

/** Fixed per dimension so a colour always means the same ability; mirrors --dim-* in tokens.css. */
export const attributeTones: Record<string, string> = {
  KNOWLEDGE: 'var(--dim-knowledge)',
  HEALTH: 'var(--dim-health)',
  CAREER: 'var(--dim-career)',
  RELATIONSHIP: 'var(--dim-relationship)',
  WELLBEING: 'var(--dim-wellbeing)',
}

export function attributeProgress(row: AttributeRow) {
  if (!row.nextLevelExperience) return 100
  const span = row.nextLevelExperience - row.currentLevelExperience
  return span <= 0 ? 100 : Math.max(0, Math.min(100, Math.round(((row.experience - row.currentLevelExperience) * 100) / span)))
}

export function strongestAttribute(attributes: AttributeRow[]) {
  return attributes.reduce<AttributeRow | null>((best, item) => (!best || item.experience > best.experience ? item : best), null)
}

/** 五维属性总览，整页与面板共用同一份加载/派生逻辑。 */
export function useAttributesOverview() {
  const data = ref<AttributesOverview | null>(null)
  const loading = ref(true)
  const error = ref('')
  const strongest = computed(() => (data.value ? strongestAttribute(data.value.attributes) : null))

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      data.value = await api.get<AttributesOverview>('/insights/attributes')
    } catch {
      error.value = '属性数据暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  return { data, loading, error, strongest, load }
}

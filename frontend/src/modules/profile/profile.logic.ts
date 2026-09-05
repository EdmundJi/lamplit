import { computed, ref, type Ref } from 'vue'
import { api } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'
import type { Title } from '../achievements/achievement.types'
import type { Pet, Wallet } from '../partners/partner.types'

export type Profile = {
  publicId: string
  email: string
  displayName: string
  birthDate: string
  age: number
  timezone: string
  createdAt: string
  overallLevel: number
  totalExperience: number
  effectiveActions: number
  wallet: Wallet
  selectedPet: Pet
  petCount: number
  soloGrowth: boolean
  equippedTitle: Title | null
}

/** Avatar initial: the profile card only needs the leading character/letter, not a full name. */
export function avatarInitial(name: string) {
  return Array.from(name.trim())[0]?.toUpperCase() ?? '我'
}

export function memberSinceLabel(createdAt: string) {
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' }).format(new Date(createdAt))
}

export function petProgressPercent(pet: Pet | null | undefined) {
  if (!pet) return 0
  return Math.min(100, Math.round((pet.affection * 100) / Math.max(1, pet.nextLevelAffection)))
}

/** 个人档案：身份、成长总量、当前伙伴与账户资料，整页与面板共用同一份加载逻辑。 */
export function useProfile() {
  const profile = ref<Profile | null>(null)
  const loading = ref(true)
  const error = ref('')

  async function load(showLoading = true) {
    if (showLoading) loading.value = true
    error.value = ''
    try {
      profile.value = await api.get<Profile>('/me/profile')
    } catch {
      error.value = '个人资料暂时无法加载'
    } finally {
      if (showLoading) loading.value = false
    }
  }

  return { profile, loading, error, load }
}

/** 称号佩戴/卸下：清单来自 /titles，佩戴后立刻同步进 profile.equippedTitle，供两端渲染头像框。 */
export function useTitles(profile: Ref<Profile | null>) {
  const titles = ref<Title[]>([])
  const busy = ref('')
  const feedback = ref('')
  const heldTitles = computed(() => titles.value.filter(item => item.held))

  async function load() {
    titles.value = await api.get<Title[]>('/titles')
  }

  async function equip(title: Title) {
    if (busy.value) return
    busy.value = title.code
    feedback.value = ''
    try {
      titles.value = await api.patch<Title[]>('/titles/equipped', { code: title.code })
      if (profile.value) profile.value.equippedTitle = titles.value.find(item => item.equipped) ?? null
      feedback.value = `已佩戴「${title.name}」`
      notifyDataChanged('achievements')
    } catch {
      feedback.value = '称号暂时无法更新'
    } finally {
      busy.value = ''
    }
  }

  async function unequip() {
    if (busy.value) return
    busy.value = 'UNEQUIP'
    feedback.value = ''
    try {
      titles.value = await api.delete<Title[]>('/titles/equipped')
      if (profile.value) profile.value.equippedTitle = null
      feedback.value = '已卸下称号'
      notifyDataChanged('achievements')
    } catch {
      feedback.value = '称号暂时无法更新'
    } finally {
      busy.value = ''
    }
  }

  return { titles, busy, feedback, heldTitles, load, equip, unequip }
}

/** 好友隐私：独自升级开关，切换成功后广播 profile/social 两个数据域。 */
export function usePrivacy(profile: Ref<Profile | null>) {
  const busy = ref(false)
  const feedback = ref('')

  async function toggleSoloGrowth() {
    if (!profile.value || busy.value) return
    busy.value = true
    feedback.value = ''
    const nextValue = !profile.value.soloGrowth
    try {
      const result = await api.patch<{ soloGrowth: boolean }>('/me/privacy', { soloGrowth: nextValue })
      profile.value.soloGrowth = result.soloGrowth
      feedback.value = result.soloGrowth ? '已进入独自升级模式' : '已允许其他用户找到你'
      notifyDataChanged(['profile', 'social'])
    } catch {
      feedback.value = '隐私设置暂时无法更新'
    } finally {
      busy.value = false
    }
  }

  return { busy, feedback, toggleSoloGrowth }
}

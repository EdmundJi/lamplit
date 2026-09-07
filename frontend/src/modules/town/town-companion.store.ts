import { defineStore } from 'pinia'
import { api } from '../../shared/api/client'
import { notifyDataChanged } from '../../shared/data-sync'
import type { PartnerProfile, Pet } from '../partners/partner.types'

export type CompanionMode = 'home' | 'following' | 'roaming'

const storagePrefix = 'better-self:town-companion:'

function canUseStorage() {
  try {
    return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
  } catch {
    return false
  }
}

function outingKey(userId: string, petId: string) {
  return `${storagePrefix}${userId}:${petId}`
}

function savedMode(userId: string, petId: string): CompanionMode {
  if (!canUseStorage()) return 'home'
  try {
    const value = window.localStorage.getItem(outingKey(userId, petId))
    return value === 'following' || value === 'roaming' ? value : 'home'
  } catch {
    return 'home'
  }
}

function saveMode(userId: string | null, petId: string | null, mode: CompanionMode) {
  if (!userId || !petId || !canUseStorage()) return
  try {
    const key = outingKey(userId, petId)
    if (mode === 'home') window.localStorage.removeItem(key)
    else window.localStorage.setItem(key, mode)
  } catch {
    // This is a convenience preference. A browser that blocks storage still has a valid walk.
  }
}

/**
 * The town only owns the current browser's outing preference. Pet identity and affection stay
 * authoritative in the existing partners API.
 */
export const useTownCompanionStore = defineStore('town-companion', {
  state: () => ({
    userId: null as string | null,
    profile: null as PartnerProfile | null,
    pet: null as Pet | null,
    mode: 'home' as CompanionMode,
    loading: false,
    busy: false,
    error: '',
    request: 0,
    loadedPetKey: null as string | null,
  }),
  actions: {
    setMode(mode: CompanionMode) {
      this.mode = mode
      saveMode(this.userId, this.pet?.publicId ?? null, mode)
    },

    /** Clears account-scoped in-memory data without touching the browser's saved outing preference. */
    clearUser() {
      ++this.request
      this.userId = null
      this.profile = null
      this.pet = null
      this.mode = 'home'
      this.loading = false
      this.busy = false
      this.error = ''
      this.loadedPetKey = null
    },

    /** Loads the selected server-backed pet, isolating late requests and a changed signed-in user. */
    async load(userId: string): Promise<void> {
      const request = ++this.request
      const previousUser = this.userId
      const previousPetId = this.pet?.publicId ?? null
      if (previousUser !== userId) {
        // Never show the previous user's companion while this request is in flight or failed.
        this.userId = userId
        this.profile = null
        this.pet = null
        this.mode = 'home'
        this.loadedPetKey = null
        // A late interaction from the former user must not keep this user's control disabled.
        this.busy = false
      }
      this.loading = true
      this.error = ''
      try {
        const profile = await api.get<PartnerProfile | null>('/partners/profile')
        if (request !== this.request || this.userId !== userId) return
        const selected = profile?.selectedPet ?? profile?.pets?.find(item => item.selected) ?? null
        const selectionChanged = previousUser === userId && previousPetId !== selected?.publicId
        if (selectionChanged && previousPetId) {
          // A selection change always puts the former companion back at home.
          saveMode(userId, previousPetId, 'home')
        }
        this.profile = profile
        this.pet = selected
        // The newly selected pet always starts at home, even if it had a stale local preference.
        const selectedKey = selected ? outingKey(userId, selected.publicId) : null
        if (selected && selectionChanged) {
          saveMode(userId, selected.publicId, 'home')
          this.mode = 'home'
        } else if (selected && this.loadedPetKey !== selectedKey) this.mode = savedMode(userId, selected.publicId)
        else if (!selected) this.mode = 'home'
        this.loadedPetKey = selectedKey
      } catch {
        if (request === this.request && this.userId === userId) {
          this.error = '伙伴资料暂时无法加载，请重试'
          // A user switch must not retain the preceding user's data after a failed request.
          if (previousUser !== userId) {
            this.pet = null
            this.profile = null
            this.mode = 'home'
          }
        }
      } finally {
        if (request === this.request && this.userId === userId) this.loading = false
      }
    },

    startWalk(): boolean {
      if (!this.pet || this.loading) {
        if (!this.loading) this.error = '先选择一位伙伴，再一起出门。'
        return false
      }
      this.error = ''
      this.setMode('following')
      return true
    },

    endWalk() {
      this.setMode('home')
    },

    setRoaming(enabled: boolean): boolean {
      if (!this.pet || this.mode === 'home') return false
      this.setMode(enabled ? 'roaming' : 'following')
      return true
    },

    /** A deliberate petting action; travelling and idling deliberately never call this endpoint. */
    async stroke(): Promise<boolean> {
      if (!this.pet || this.busy) return false
      const petId = this.pet.publicId
      const userId = this.userId
      const request = this.request
      this.busy = true
      this.error = ''
      try {
        const result = await api.post<{ pet: Pet }>(`/partners/pets/${encodeURIComponent(petId)}/interact`)
        if (request !== this.request || this.userId !== userId || this.pet?.publicId !== petId) return false
        if (this.pet?.publicId === petId) this.pet = result.pet
        if (this.profile) {
          this.profile = {
            ...this.profile,
            selectedPet: this.profile.selectedPet?.publicId === petId ? result.pet : this.profile.selectedPet,
            pets: this.profile.pets.map(item => item.publicId === petId ? result.pet : item),
          }
        }
        notifyDataChanged('partners')
        return true
      } catch {
        if (request === this.request && this.userId === userId && this.pet?.publicId === petId) this.error = '摸摸没有成功，请稍后重试。'
        return false
      } finally {
        if (request === this.request && this.userId === userId && this.pet?.publicId === petId) this.busy = false
      }
    },
  },
})

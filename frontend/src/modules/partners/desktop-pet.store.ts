import { defineStore } from 'pinia'

const storageKey = 'better-self:desktop-pet'

function storageAvailable() {
  return typeof window !== 'undefined' && typeof window.localStorage !== 'undefined'
}

export const useDesktopPetStore = defineStore('desktop-pet', {
  state: () => ({
    petPublicId: null as string | null,
    hydrated: false,
  }),
  actions: {
    hydrate() {
      if (this.hydrated) return
      this.petPublicId = storageAvailable() ? window.localStorage.getItem(storageKey) : null
      this.hydrated = true
    },
    setPet(publicId: string) {
      this.petPublicId = publicId
      if (storageAvailable()) window.localStorage.setItem(storageKey, publicId)
    },
    clear() {
      this.petPublicId = null
      if (storageAvailable()) window.localStorage.removeItem(storageKey)
    },
  },
})

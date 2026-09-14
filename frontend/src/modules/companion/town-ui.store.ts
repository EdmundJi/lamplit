import { ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from '../auth/auth.store'

/**
 * The /town page's own UI state, shared between CompanionView (the fullscreen page) and TownStage
 * (the docked street strip that mounts the same CompanionScene). Only the handful of fields the
 * scene itself needs as props - or that the strip needs to read - live here; panel-only state that
 * never reaches CompanionScene (journal/story disclosure, the focus card) stays local to
 * CompanionView, same as before this store existed.
 */
export const useTownUi = defineStore('town-ui', () => {
  const auth = useAuthStore()
  const overview = ref(false)
  const quiet = ref(false)
  const soundEnabled = ref(false)
  const textBubbles = ref(false)
  const selectedResident = ref<string | null>(null)
  const selectedConversation = ref<string | null>(null)
  const selectedPlace = ref('')
  const selectedProject = ref<string | null>(null)
  // Mirrors CompanionView's own "is a panel open over the scene" check (person/conversation/
  // stories/journal/focus) so the docked strip - which cannot see those local panel refs - can
  // still suppress hover cards while /town has something open over the scene.
  const hoverSuppressed = ref(false)

  // The fullscreen CompanionScene is mounted by TownStage (so the same Phaser canvas survives the
  // docked<->fullscreen Teleport), but its click handlers need CompanionView's own rich,
  // panel-mutual-exclusion logic (closing the focus/journal/story panels, distinguishing the
  // avatar from a resident, ...) - logic that stays local to CompanionView rather than duplicated
  // here. CompanionView registers its own selectResident/openConversation/followProject while
  // mounted; TownStage forwards the scene's emitted events through whatever is currently
  // registered (nothing, when /town itself is not mounted - the docked strip never calls these).
  const fullscreenHandlers = ref<{ selectResident?: (id: string) => void; selectConversation?: (id: string) => void; selectProject?: (id: string) => void }>({})
  function registerFullscreenHandlers(handlers: typeof fullscreenHandlers.value) {
    fullscreenHandlers.value = handlers
    return () => { fullscreenHandlers.value = {} }
  }

  function bubbleKey() { return `better-self:town-text-bubbles:${auth.user?.publicId || 'guest'}` }
  try { textBubbles.value = localStorage.getItem(bubbleKey()) === 'on' } catch { /* Browsing remains available without storage. */ }
  function toggleTextBubbles() {
    textBubbles.value = !textBubbles.value
    if (textBubbles.value) quiet.value = false
    try { localStorage.setItem(bubbleKey(), textBubbles.value ? 'on' : 'off') } catch { /* Keep the preference for this visit. */ }
  }

  watch(() => auth.user?.publicId ?? null, () => {
    overview.value = false
    quiet.value = false
    soundEnabled.value = false
    selectedResident.value = null
    selectedConversation.value = null
    selectedPlace.value = ''
    selectedProject.value = null
    hoverSuppressed.value = false
    fullscreenHandlers.value = {}
    try { textBubbles.value = localStorage.getItem(bubbleKey()) === 'on' } catch { textBubbles.value = false }
  }, { flush: 'sync' })

  return {
    overview, quiet, soundEnabled, textBubbles, selectedResident, selectedConversation,
    selectedPlace, selectedProject, hoverSuppressed, toggleTextBubbles,
    fullscreenHandlers, registerFullscreenHandlers,
  }
})

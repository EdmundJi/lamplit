import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { useAuthStore } from '../../modules/auth/auth.store'

/** A device preference scoped to the signed-in account. */
export const useWorkspaceModeStore = defineStore('workspace-mode', () => {
  const auth = useAuthStore()
  const minimal = ref(false)
  const key = computed(() => `better-self:workspace-mode:${auth.user?.publicId ?? 'guest'}`)
  watch(key, value => {
    try { minimal.value = localStorage.getItem(value) === 'minimal' } catch { minimal.value = false }
  }, { immediate: true })
  function setMinimal(value: boolean) {
    minimal.value = value
    try { localStorage.setItem(key.value, value ? 'minimal' : 'growth') } catch { /* Current session still works. */ }
  }
  return { minimal, setMinimal }
})

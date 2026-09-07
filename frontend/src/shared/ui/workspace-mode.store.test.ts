import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { beforeEach, expect, it } from 'vitest'
import { useWorkspaceModeStore } from './workspace-mode.store'
import { useAuthStore } from '../../modules/auth/auth.store'

beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()) })
it('persists the selected mode per account without changing task data', async () => {
  const auth = useAuthStore()
  auth.user = { publicId: 'a', email: 'a@example.test', displayName: 'A', role: 'USER', timezone: 'Asia/Shanghai' }
  const mode = useWorkspaceModeStore()
  expect(mode.minimal).toBe(false)
  mode.setMinimal(true)
  expect(localStorage.getItem('better-self:workspace-mode:a')).toBe('minimal')
  auth.user = { ...auth.user, publicId: 'b' }
  await nextTick()
  expect(mode.minimal).toBe(false)
  auth.user = { ...auth.user, publicId: 'a' }
  await nextTick()
  expect(mode.minimal).toBe(true)
  mode.setMinimal(false)
  expect(localStorage.getItem('better-self:workspace-mode:a')).toBe('growth')
})

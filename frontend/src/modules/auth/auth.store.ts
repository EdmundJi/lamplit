import { defineStore } from 'pinia'
import { api } from '../../shared/api/client'

export type User = { publicId: string; email: string; displayName: string; timezone: string; role: string }
type LoginResult = User | { status: 'MFA_PENDING' }
export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null as User | null, loading: false, initialized: false }),
  getters: { signedIn: state => Boolean(state.user), isAdmin: state => Boolean(state.user && state.user.role !== 'USER') },
  actions: {
    async load() { this.loading = true; try { this.user = await api.get<User>('/me') } catch { this.user = null } finally { this.loading = false; this.initialized = true } },
    async login(email: string, password: string) {
      const result = await api.post<LoginResult>('/auth/login', { email, password })
      if ('status' in result) {
        this.user = null
        this.initialized = true
        return result
      }
      this.user = result
      this.initialized = true
      return result
    },
    async verifyAdminMfa(email: string, password: string, code: string) {
      this.user = await api.post<User>('/auth/mfa/verify', { email, password, code })
      this.initialized = true
      return this.user
    },
    async logout() { await api.post('/auth/logout'); this.user = null },
  },
})

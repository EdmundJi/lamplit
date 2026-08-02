import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AdminDashboard from './AdminDashboard.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

describe('admin dashboard', () => {
  beforeEach(() => {
    api.get.mockReset()
    api.post.mockReset()
    api.get.mockImplementation((path: string) => {
      if (path === '/admin/metrics') return Promise.resolve({ activeUsers: 3, taskEvents: 8, safetyEvents: 1, deletionBacklog: 0 })
      if (path === '/admin/safety/events') return Promise.resolve([{ publicId: 's1', scene: 'STUDY', riskLevel: 'L2', direction: 'INPUT', excerpt: 'redacted', reviewStatus: 'OPEN', createdAt: '2026-08-01T00:00:00Z' }])
      if (path === '/admin/audit') return Promise.resolve([{ publicId: 'a1', action: 'LOGIN', resourceType: 'USER', resourcePublicId: 'u1', outcome: 'SUCCESS', requestId: 'r1', createdAt: '2026-08-01T00:00:00Z' }])
      if (path === '/admin/users') return Promise.resolve([{ publicId: 'u1', email: 'admin@example.test', displayName: 'Admin', timezone: 'Asia/Shanghai', status: 'ACTIVE', role: 'ADMIN', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z' }])
      return Promise.resolve([])
    })
  })

  it('shows governance sections and creates an MFA protected admin account', async () => {
    api.post.mockResolvedValue({ user: { publicId: 'u2', email: 'new@example.test', displayName: 'New Admin', timezone: 'Asia/Shanghai', status: 'ACTIVE', role: 'ADMIN', createdAt: '2026-08-01T00:00:00Z', updatedAt: '2026-08-01T00:00:00Z' }, mfaSecret: 'JBSWY3DPEHPK3PXP' })
    const wrapper = mount(AdminDashboard)
    await flushPromises()

    expect(wrapper.text()).toContain('活跃用户')
    await wrapper.findAll('.admin-tabs button')[1].trigger('click')
    await wrapper.get('#admin-email').setValue('new@example.test')
    await wrapper.get('#admin-password').setValue('Correct-Horse-Battery-2026!')
    await wrapper.get('form').trigger('submit')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/admin/users/admins', expect.objectContaining({ email: 'new@example.test', role: 'ADMIN' }))
    expect(wrapper.text()).toContain('JBSWY3DPEHPK3PXP')
  })
})

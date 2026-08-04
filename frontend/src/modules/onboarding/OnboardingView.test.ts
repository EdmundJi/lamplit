import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import OnboardingView from './OnboardingView.vue'

const api = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))
vi.mock('../../shared/api/client', () => ({ api }))

const starters = [
  { publicId: 'starter-1', title: '阅读十分钟', description: '从一小段开始', estimatedMinutes: 10, difficulty: 1, plannedLocalTime: '20:00', rrule: 'FREQ=DAILY', dimensionCode: 'KNOWLEDGE' },
  { publicId: 'starter-2', title: '整理三个要点', description: '留下简单记录', estimatedMinutes: 10, difficulty: 1, plannedLocalTime: '20:15', rrule: 'FREQ=DAILY', dimensionCode: 'KNOWLEDGE' },
  { publicId: 'starter-3', title: '复习一道错题', description: '保持轻量复习', estimatedMinutes: 10, difficulty: 1, plannedLocalTime: '20:30', rrule: 'FREQ=DAILY', dimensionCode: 'KNOWLEDGE' },
  { publicId: 'starter-4', title: '写一句总结', description: '收束当天学习', estimatedMinutes: 5, difficulty: 1, plannedLocalTime: '20:45', rrule: 'FREQ=DAILY', dimensionCode: 'KNOWLEDGE' },
]

async function mountOnboarding() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/onboarding', component: OnboardingView },
      { path: '/today', component: { template: '<div>today</div>' } },
      { path: '/goals', component: { template: '<div>goals</div>' } },
    ],
  })
  await router.push('/onboarding')
  await router.isReady()
  return mount(OnboardingView, { global: { plugins: [router] } })
}

function buttonByText(wrapper: ReturnType<typeof mount>, text: string) {
  const button = wrapper.findAll('button').find(item => item.text().includes(text))
  if (!button) throw new Error(`Button not found: ${text}`)
  return button
}

describe('Onboarding', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.get.mockResolvedValue(starters)
    api.post.mockResolvedValue({
      alreadyCompleted: false,
      completedAt: '2026-08-04T08:00:00Z',
      goalPublicId: 'goal-1',
      weeklyPlanPublicId: 'plan-1',
      taskPublicIds: ['task-1', 'task-2', 'task-3'],
      rewardTitle: { code: 'NEWCOMER_PATH', name: '成长之路', description: '你已经为成长留下了第一条路。', graphicKey: 'Route', frameStyle: 'emerald' },
    })
  })

  it('loads starter tasks, keeps at most three selections and completes setup', async () => {
    const wrapper = await mountOnboarding()

    await buttonByText(wrapper, '继续').trigger('click')
    await buttonByText(wrapper, '挑选任务').trigger('click')
    await flushPromises()
    expect(api.get).toHaveBeenCalledWith('/onboarding/starters?scene=STUDY')
    expect(wrapper.findAll('.starter-item')).toHaveLength(4)

    const inputs = wrapper.findAll<HTMLInputElement>('input[type="checkbox"]')
    await inputs[2].setValue(false)
    await inputs[3].setValue(true)
    await buttonByText(wrapper, '开始行动').trigger('click')
    await flushPromises()

    expect(api.post).toHaveBeenCalledWith('/onboarding/complete', {
      scene: 'STUDY',
      dailyMinutes: 30,
      weeklyFrequency: 3,
      preferredDifficulty: 2,
      starterTemplatePublicIds: ['starter-1', 'starter-2', 'starter-4'],
    })
    expect(wrapper.text()).toContain('成长之路')
    expect(wrapper.text()).toContain('3 个起步任务')
  })
})

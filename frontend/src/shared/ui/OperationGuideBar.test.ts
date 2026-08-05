import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import { createMemoryHistory, createRouter } from 'vue-router'
import OperationGuideBar from './OperationGuideBar.vue'

async function mountGuide(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }],
  })
  await router.push(path)
  await router.isReady()
  return mount(OperationGuideBar, { global: { plugins: [router] } })
}

describe('OperationGuideBar', () => {
  it.each([
    ['/today', '今日行动'],
    ['/goals', '目标规划'],
    ['/partners', '成长伙伴'],
    ['/friends', '好友协作'],
    ['/friends/chat', '消息中心'],
    ['/attributes', '属性查看'],
    ['/insights', '成长洞察'],
    ['/ai', 'AI 协作'],
    ['/profile', '个人主页'],
    ['/settings', '系统设置'],
    ['/admin', '治理流程'],
  ])('covers the function page %s', async (path, label) => {
    const wrapper = await mountGuide(path)

    expect(wrapper.attributes('aria-label')).toBe(label)
    expect(wrapper.findAll('li').length).toBeGreaterThanOrEqual(3)
  })

  it('shows the correct order for a main function page', async () => {
    const wrapper = await mountGuide('/goals')

    expect(wrapper.attributes('aria-label')).toBe('目标规划')
    expect(wrapper.findAll('li').map(item => item.text())).toEqual([
      '1新建目标',
      '2添加周期任务',
      '3回到今日执行',
    ])
  })

  it('uses a specific guide for a direct message page', async () => {
    const wrapper = await mountGuide('/friends/user-1/chat')

    expect(wrapper.attributes('aria-label')).toBe('聊天顺序')
    expect(wrapper.findAll('.step-label').map(item => item.text())).toEqual(['查看新消息', '输入内容', '发送交流'])
  })

  it('stays absent on pages with their own step flow', async () => {
    const wrapper = await mountGuide('/onboarding')

    expect(wrapper.find('.operation-guide').exists()).toBe(false)
  })
})

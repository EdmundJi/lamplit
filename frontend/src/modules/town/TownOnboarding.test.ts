import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import TownOnboarding from './TownOnboarding.vue'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: (key: string) => store[key] || null,
    setItem: (key: string, value: string) => { store[key] = value },
    removeItem: (key: string) => { delete store[key] },
    clear: () => { store = {} },
  }
})()
Object.defineProperty(window, 'localStorage', { value: localStorageMock })

describe('TownOnboarding', () => {
  let wrapper: ReturnType<typeof mount> | null = null

  beforeEach(() => {
    localStorageMock.clear()
    if (!document.body) {
      document.body = document.createElement('body')
    }
  })

  afterEach(() => {
    if (wrapper) {
      wrapper.unmount()
      wrapper = null
    }
    // 清理 Teleport 残留
    const onboarding = document.querySelector('.town-onboarding')
    if (onboarding) {
      onboarding.remove()
    }
  })

  it('未完成时自动显示', async () => {
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()
    const el = document.querySelector('.town-onboarding')
    expect(el).toBeTruthy()
    expect(document.body.textContent).toContain('欢迎来到成长小镇')
  })

  it('已完成时不显示', async () => {
    localStorageMock.setItem('better-self:town-onboarding:user-123:completed', 'true')
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()
    expect(document.querySelector('.town-onboarding')).toBeFalsy()
  })

  it('点击下一步推进到下一个步骤', async () => {
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()

    expect(document.body.textContent).toContain('欢迎来到成长小镇')

    const primaryBtn = document.querySelector('.primary') as HTMLElement
    primaryBtn?.click()
    await nextTick()
    await nextTick()

    expect(document.body.textContent).toContain('今天的一小步')
  })

  it('点击跳过引导标记完成并关闭', async () => {
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()

    const skipBtn = document.querySelector('.onboarding-skip') as HTMLElement
    skipBtn?.click()
    await nextTick()
    await nextTick()

    expect(document.querySelector('.town-onboarding')).toBeFalsy()
    expect(localStorageMock.getItem('better-self:town-onboarding:user-123:completed')).toBe('true')
  })

  it('显示正确的进度条', async () => {
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()

    let progressBar = document.querySelector('.progress-bar') as HTMLElement
    // 第一步是 1/3
    expect(parseFloat(progressBar.style.width)).toBeCloseTo(33.333, 1)

    const primaryBtn = document.querySelector('.primary') as HTMLElement
    primaryBtn?.click()
    await nextTick()
    await nextTick()

    progressBar = document.querySelector('.progress-bar') as HTMLElement
    // 第二步是 2/3
    expect(parseFloat(progressBar.style.width)).toBeCloseTo(66.666, 1)
  })

  it('restart 重新开始引导', async () => {
    localStorageMock.setItem('better-self:town-onboarding:user-123:completed', 'true')
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()
    await nextTick()

    expect(document.querySelector('.town-onboarding')).toBeFalsy()

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    ;(wrapper.vm as any).restart()
    await nextTick()
    await nextTick()

    expect(document.querySelector('.town-onboarding')).toBeTruthy()
    expect(document.body.textContent).toContain('欢迎来到成长小镇')
  })

  it('发出实际入住动作并在面板打开期间让出界面', async () => {
    wrapper = mount(TownOnboarding, { props: { userId: 'new' }, attachTo: document.body })
    await nextTick()
    ;(document.querySelector('.primary') as HTMLElement).click()
    await nextTick()
    expect(wrapper.emitted('action')).toEqual([['home-style']])
    await wrapper.setProps({ anyPanelOpen: true })
    expect(document.querySelector('.town-onboarding')).toBeNull()
    await wrapper.setProps({ anyPanelOpen: false })
    expect(document.body.textContent).toContain('今天的一小步')
  })

  it('isCompleted 检查完成状态', async () => {
    wrapper = mount(TownOnboarding, {
      props: {
        userId: 'user-123',
      },
      attachTo: document.body,
    })
    await nextTick()

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((wrapper.vm as any).isCompleted()).toBe(false)

    localStorageMock.setItem('better-self:town-onboarding:user-123:completed', 'true')
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((wrapper.vm as any).isCompleted()).toBe(true)
  })
})

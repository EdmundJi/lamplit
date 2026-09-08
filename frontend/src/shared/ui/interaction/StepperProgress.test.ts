import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import StepperProgress from './StepperProgress.vue'

describe('StepperProgress', () => {
  it('separates finished, current and upcoming steps', () => {
    const wrapper = mount(StepperProgress, { props: { steps: 3, current: 2, label: '入门设置进度' } })
    expect(wrapper.findAll('.stepper-step').map(step => step.attributes('data-state')))
      .toEqual(['done', 'current', 'upcoming'])
  })

  it('reports progress to assistive technology', () => {
    const wrapper = mount(StepperProgress, { props: { steps: 3, current: 2, label: '入门设置进度' } })
    expect(wrapper.attributes('role')).toBe('progressbar')
    expect(wrapper.attributes('aria-valuenow')).toBe('2')
    expect(wrapper.attributes('aria-valuetext')).toBe('第 2 步，共 3 步')
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('offers real buttons when the steps can be jumped to', async () => {
    const wrapper = mount(StepperProgress, {
      props: { steps: 4, current: 1, label: '欢迎介绍进度', variant: 'dots', selectable: true, stepLabel: (step: number) => `第 ${step} 页` },
    })
    const steps = wrapper.findAll('button')
    expect(steps).toHaveLength(4)
    expect(steps[2].attributes('aria-label')).toBe('第 3 页')
    expect(steps[0].attributes('aria-current')).toBe('step')
    await steps[2].trigger('click')
    expect(wrapper.emitted('select')).toEqual([[3]])
  })
})

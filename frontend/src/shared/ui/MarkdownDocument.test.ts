import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import MarkdownDocument from './MarkdownDocument.vue'

describe('MarkdownDocument', () => {
  it('renders markdown as structured document content', () => {
    const wrapper = mount(MarkdownDocument, {
      props: {
        text: [
          '# 今日计划',
          '',
          '- **第一步**：复习 20 分钟',
          '- 记录 `完成比例`',
          '',
          '> 先做小版本。',
        ].join('\n'),
      },
    })

    expect(wrapper.find('h2').text()).toBe('今日计划')
    expect(wrapper.find('strong').text()).toBe('第一步')
    expect(wrapper.findAll('li')).toHaveLength(2)
    expect(wrapper.find('code').text()).toBe('完成比例')
    expect(wrapper.find('blockquote').text()).toContain('先做小版本')
    expect(wrapper.text()).not.toContain('**第一步**')
  })
})

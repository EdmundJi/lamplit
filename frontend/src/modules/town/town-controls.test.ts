import { describe, it, expect, vi } from 'vitest'
import { TownControls, shouldInterceptClick } from './town-controls'

describe('TownControls', () => {
  it('初始状态是走路模式', () => {
    const controls = new TownControls()
    expect(controls.runMode).toBe('walk')
    expect(controls.isRunning).toBe(false)
  })

  it('toggleRunMode 切换跑步模式', () => {
    const onRunModeChange = vi.fn()
    const controls = new TownControls({ onRunModeChange })

    controls.toggleRunMode()
    expect(controls.runMode).toBe('run')
    expect(controls.isRunning).toBe(true)
    expect(onRunModeChange).toHaveBeenCalledWith('run')

    controls.toggleRunMode()
    expect(controls.runMode).toBe('walk')
    expect(controls.isRunning).toBe(false)
    expect(onRunModeChange).toHaveBeenCalledWith('walk')
  })

  it('R 键切换跑步模式', () => {
    const controls = new TownControls()
    const event = new KeyboardEvent('keydown', { key: 'r' })

    const handled = controls.handleKeyDown(event)
    expect(handled).toBe(true)
    expect(controls.runMode).toBe('run')
  })

  it('Shift 键临时加速（走路模式下生效）', () => {
    const onShiftChange = vi.fn()
    const controls = new TownControls({ onShiftChange })

    const downEvent = new KeyboardEvent('keydown', { key: 'Shift' })
    controls.handleKeyDown(downEvent)
    expect(controls.isRunning).toBe(true)
    expect(onShiftChange).toHaveBeenCalledWith(true)

    const upEvent = new KeyboardEvent('keyup', { key: 'Shift' })
    controls.handleKeyUp(upEvent)
    expect(controls.isRunning).toBe(false)
    expect(onShiftChange).toHaveBeenCalledWith(false)
  })

  it('Shift 在跑步模式下不生效', () => {
    const controls = new TownControls()
    controls.setRunMode('run')

    const downEvent = new KeyboardEvent('keydown', { key: 'Shift' })
    controls.handleKeyDown(downEvent)
    expect(controls.isRunning).toBe(true) // 因为本来就在跑步
  })

  it('输入框内不响应快捷键', () => {
    const controls = new TownControls()
    const input = document.createElement('input')
    const event = new KeyboardEvent('keydown', { key: 'r' })
    Object.defineProperty(event, 'target', { value: input, writable: false })

    const handled = controls.handleKeyDown(event)
    expect(handled).toBe(false)
    expect(controls.runMode).toBe('walk')
  })

  it('destroy 清理状态', () => {
    const controls = new TownControls()
    controls.setRunMode('run')
    controls.destroy()
    expect(controls.runMode).toBe('walk')
  })
})

describe('shouldInterceptClick', () => {
  it('点击 canvas 不拦截', () => {
    const canvas = document.createElement('canvas')
    const event = new MouseEvent('pointerdown', { bubbles: true }) as unknown as PointerEvent
    Object.defineProperty(event, 'target', { value: canvas, writable: false })
    expect(shouldInterceptClick(event)).toBe(false)
  })

  it('点击其他元素拦截', () => {
    const button = document.createElement('button')
    const event = new MouseEvent('pointerdown', { bubbles: true }) as unknown as PointerEvent
    Object.defineProperty(event, 'target', { value: button, writable: false })
    expect(shouldInterceptClick(event)).toBe(true)
  })

  it('target 为 null 不拦截', () => {
    const event = new MouseEvent('pointerdown', { bubbles: true }) as unknown as PointerEvent
    Object.defineProperty(event, 'target', { value: null, writable: false })
    expect(shouldInterceptClick(event)).toBe(false)
  })
})

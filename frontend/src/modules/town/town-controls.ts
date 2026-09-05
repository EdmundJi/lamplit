/**
 * 小镇交互控制逻辑：跑步模式切换、快捷键处理、点击穿透防护
 */

export type RunMode = 'walk' | 'run'

export interface TownControlsState {
  runMode: RunMode
  shiftHeld: boolean
  temporaryRun: boolean
}

export interface TownControlsOptions {
  onRunModeChange?: (mode: RunMode) => void
  onShiftChange?: (held: boolean) => void
}

export class TownControls {
  private state: TownControlsState = {
    runMode: 'walk',
    shiftHeld: false,
    temporaryRun: false,
  }

  private options: TownControlsOptions

  constructor(options: TownControlsOptions = {}) {
    this.options = options
  }

  /** 当前实际的跑步状态（考虑 Shift 临时加速） */
  get isRunning(): boolean {
    return this.state.runMode === 'run' || this.state.temporaryRun
  }

  /** 持久的跑步模式设置 */
  get runMode(): RunMode {
    return this.state.runMode
  }

  /** 切换跑步模式（持久设置） */
  toggleRunMode(): void {
    this.state.runMode = this.state.runMode === 'walk' ? 'run' : 'walk'
    this.options.onRunModeChange?.(this.state.runMode)
  }

  /** 设置跑步模式 */
  setRunMode(mode: RunMode): void {
    if (this.state.runMode === mode) return
    this.state.runMode = mode
    this.options.onRunModeChange?.(this.state.runMode)
  }

  /** 处理键盘按下（供 keydown 事件调用） */
  handleKeyDown(event: KeyboardEvent): boolean {
    const target = event.target as HTMLElement | null
    const typing = Boolean(target && ['INPUT', 'TEXTAREA'].includes(target.tagName))

    // Shift：临时加速（按住时生效，松开恢复）
    if (event.key === 'Shift' && !typing && !this.state.shiftHeld) {
      this.state.shiftHeld = true
      this.state.temporaryRun = this.state.runMode === 'walk' // 只在走路模式下生效
      this.options.onShiftChange?.(true)
      return true
    }

    // R：切换持久跑步模式
    if ((event.key === 'r' || event.key === 'R') && !typing) {
      this.toggleRunMode()
      return true
    }

    return false
  }

  /** 处理键盘松开（供 keyup 事件调用） */
  handleKeyUp(event: KeyboardEvent): boolean {
    if (event.key === 'Shift' && this.state.shiftHeld) {
      this.state.shiftHeld = false
      this.state.temporaryRun = false
      this.options.onShiftChange?.(false)
      return true
    }
    return false
  }

  /** 销毁时清理状态 */
  destroy(): void {
    this.state = { runMode: 'walk', shiftHeld: false, temporaryRun: false }
  }
}

/**
 * 检查点击事件是否应该被拦截（点在 DOM 元素上而非 canvas）
 * 用于防止点击面板/按钮时穿透到 Phaser canvas
 */
export function shouldInterceptClick(event: PointerEvent): boolean {
  const target = event.target as HTMLElement | null
  if (!target) return false

  // 点击的是 canvas，放行给 Phaser
  if (target.tagName === 'CANVAS') return false

  // 点击的是其他 DOM 元素（面板/按钮等），拦截
  return true
}

/**
 * 为元素添加点击穿透防护（阻止事件冒泡到 canvas）
 */
export function addClickProtection(element: HTMLElement): () => void {
  const handler = (event: PointerEvent) => {
    event.stopPropagation()
  }
  element.addEventListener('pointerdown', handler, { capture: true })
  return () => element.removeEventListener('pointerdown', handler, { capture: true })
}

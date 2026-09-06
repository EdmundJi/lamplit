import type { Component, InjectionKey } from 'vue'

/**
 * 沉浸式小镇里的「面板」是路由跳页的替代品：同一套 store 和接口，渲染在小镇画布上方的窗口里。
 * 外壳（ImmersiveTown.vue）只认这份契约，面板实现放在 ./panels/，两边可以各自演进。
 */

export type WorldPanelKey =
  | 'today' | 'goals' | 'ai' | 'friends' | 'insights' | 'attributes' | 'partners' | 'profile' | 'settings'

/** 小镇里可以「走过去」的锚点，与 town.engine.ts 的 TownSelection 对齐。
 * plaza/street 目前引擎还产生不了这两个选中值（见 immersive.store.ts 的 anchorForSelection），
 * 先把 9 个面板按地点语义分完，等引擎补上对应的可点击区域自然就会命中。 */
export type WorldAnchor = 'home' | 'academy' | 'npc:assistant' | 'npc:postman' | 'gym' | 'cafe' | 'park' | 'plaza' | 'street'

export type WorldPanelDef = {
  key: WorldPanelKey
  /** 展示在底部 dock 和窗口标题栏。 */
  title: string
  /** 一句话说明这个面板在小镇里代表什么。 */
  subtitle: string
  /** Lucide 图标组件。 */
  icon: Component
  /** 异步组件：面板的代码只在打开时加载。 */
  loader: () => Promise<{ default: Component }>
  /** 窗口宽度倾向，最终几何由外壳决定。 */
  size: 'compact' | 'wide'
  /** 走到这个地点/NPC 时自动打开本面板。 */
  anchor?: WorldAnchor
  /** 用户要求「打开完整页面」时跳转的路由。 */
  fullPage: string
}

/** 面板回传给世界的事件：数据变化要在画面里看得见。 */
export type WorldEvent =
  | { type: 'celebrate'; publicId: string }
  | { type: 'focus'; publicId: string }
  | { type: 'travel'; place: string }
  | { type: 'mail-count'; count: number }
  | { type: 'toast'; text: string }
  | { type: 'open'; panel: WorldPanelKey }
  | { type: 'close' }
  /** 昼夜/学院内外是引擎里少有的"有当前状态"的开关，外壳负责真正调用 TownGame，这里只是意图。 */
  | { type: 'night'; value: boolean }
  | { type: 'academy'; value: boolean }

/** world-actions.ts 里 runWorldAction() 结果的最小形状，供 WorldBridge.run() 的调用方使用。
 * 定义在这里（而不是反过来从 world-actions.ts import）是为了不让这份契约依赖那份注册表实现。 */
export type WorldActionOutcome = { ok: boolean; message?: string }

export type WorldBridge = {
  emit(event: WorldEvent): void
  /** 当前是否开着跑步模式，面板里也能显示/切换。 */
  readonly runMode: boolean
  setRunMode(enabled: boolean): void
  /** 调用一个已注册的世界能力（world-actions.ts），结果会自动出现在 WorldFeedback 里。
   * `payload` 原样转给该能力的 run(ctx)，用不用看能力自己。 */
  run(actionId: string, payload?: unknown): Promise<WorldActionOutcome>
}

export const worldBridgeKey: InjectionKey<WorldBridge> = Symbol('world-bridge')

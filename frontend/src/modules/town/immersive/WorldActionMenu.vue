<script setup lang="ts">
/**
 * 玩家走到一个地点/NPC 旁时弹出的能力清单。纯展示 + 交互：具体能不能用（availability）、
 * 需不需要先确认（confirmText）都由外壳按 world-actions.ts 算好，通过 props 传进来——这样
 * 这个组件不用认识小镇引擎或注册表，键盘可达性和"不可用态"能单独测试。
 */
import { ref } from 'vue'
import type { Component } from 'vue'
import type { ResolvedWorldAction } from './world-actions'

defineProps<{ actions: ResolvedWorldAction[]; title?: string }>()
const emit = defineEmits<{ run: [id: string]; close: [] }>()

/** 需要二次确认的能力，第一次点击只展示这条文案；第二次点"确认"才真的触发。 */
const pendingId = ref<string | null>(null)

function onActivate(action: ResolvedWorldAction) {
  if (!action.availability.ok) return
  if (action.confirmText && pendingId.value !== action.id) {
    pendingId.value = action.id
    return
  }
  pendingId.value = null
  emit('run', action.id)
}

function cancelConfirm() {
  pendingId.value = null
}

function iconOf(icon: Component | undefined) {
  return icon ?? null
}
</script>

<template>
  <section
    class="world-action-menu"
    role="menu"
    :aria-label="title ?? '这里能做的事'"
    @keydown.esc.stop="emit('close')"
  >
    <header class="world-action-menu-head">
      <strong>{{ title ?? '这里能做的事' }}</strong>
      <button class="icon-button" type="button" aria-label="关闭动作菜单" @click="emit('close')">×</button>
    </header>
    <ul class="world-action-list">
      <li v-for="action in actions" :key="action.id">
        <button
          type="button"
          role="menuitem"
          class="world-action-item"
          :aria-disabled="!action.availability.ok"
          :title="action.availability.ok ? action.hint : action.availability.reason"
          @click="onActivate(action)"
        >
          <component :is="iconOf(action.icon)" v-if="action.icon" :size="16" aria-hidden="true" />
          <span class="world-action-texts">
            <span class="world-action-label">{{ action.label }}</span>
            <small v-if="!action.availability.ok" class="world-action-reason">{{ action.availability.reason }}</small>
            <small v-else-if="action.hint" class="world-action-hint">{{ action.hint }}</small>
          </span>
        </button>
        <div v-if="pendingId === action.id" class="world-action-confirm">
          <span>{{ action.confirmText }}</span>
          <div class="world-action-confirm-buttons">
            <button type="button" class="secondary" @click="cancelConfirm">取消</button>
            <button type="button" @click="onActivate(action)">确认</button>
          </div>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.world-action-menu { color: var(--ink); position: absolute; left: 50%; bottom: 92px; transform: translateX(-50%); z-index: 30; width: min(300px, calc(100vw - 32px)); border: 1px solid color-mix(in srgb, var(--border) 60%, transparent); border-radius: var(--radius-panel); background: color-mix(in srgb, var(--surface) 96%, transparent); backdrop-filter: blur(14px); box-shadow: var(--shadow); overflow: hidden; }
.world-action-menu-head { display: flex; align-items: center; gap: 8px; padding: 10px 8px 10px 14px; border-bottom: 1px solid var(--border); background: var(--surface-muted); font-size: 13px; }
.world-action-menu-head strong { flex: 1; }
.world-action-menu-head .icon-button { width: 26px; height: 26px; min-height: 26px; padding: 0; line-height: 1; }
.world-action-list { list-style: none; margin: 0; padding: 6px; display: grid; gap: 4px; max-height: 50vh; overflow: auto; }
.world-action-item { width: 100%; display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: none; border-radius: var(--radius); background: transparent; color: var(--ink); text-align: left; }
.world-action-item:hover { background: var(--surface-muted); }
.world-action-item[aria-disabled='true'] { color: var(--muted); }
.world-action-item[aria-disabled='true']:hover { background: transparent; cursor: default; }
.world-action-texts { display: grid; gap: 1px; min-width: 0; }
.world-action-label { font-size: 13px; font-weight: 600; }
.world-action-hint, .world-action-reason { font-size: 11px; color: var(--muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.world-action-reason { color: var(--danger); }
.world-action-confirm { margin: 2px 10px 6px; padding: 8px 10px; border-radius: var(--radius); background: var(--surface-muted); font-size: 12px; display: grid; gap: 6px; }
.world-action-confirm-buttons { display: flex; justify-content: flex-end; gap: 8px; }
.world-action-confirm-buttons button { min-height: 30px; padding: 0 12px; font-size: 12px; }
@media (prefers-reduced-motion: reduce) { .world-action-menu { transition: none; } }
</style>

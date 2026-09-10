<script setup lang="ts">
/**
 * Shared dialog/drawer primitive: `.dialog-backdrop` (from global.css) + a focus-trapped
 * panel with correct `role="dialog"` / `aria-modal` semantics, wired to the existing
 * `useDialogFocus` helper so Escape, backdrop click and focus return all work the same
 * way GoalsView's hand-rolled drawers already do. `placement="drawer"` slides in from the
 * side (like the goal/task drawers); `placement="center"` (default) is a centered dialog.
 */
import { X } from 'lucide-vue-next'
import { useDialogFocus } from './use-dialog-focus'

let uid = 0

const props = withDefaults(defineProps<{
  open: boolean
  /** Rendered as the dialog heading and used for aria-labelledby. Omit and pass `ariaLabel` for a custom header slot. */
  title?: string
  /** Accessible name when no `title`/heading is rendered (e.g. a fully custom header slot). */
  ariaLabel?: string
  placement?: 'center' | 'drawer'
  closeOnBackdrop?: boolean
  /** While true, Escape/backdrop/close-button are ignored — mirrors the `!busy && (panel = null)` guard used elsewhere. */
  busy?: boolean
}>(), {
  placement: 'center',
  closeOnBackdrop: true,
  busy: false,
})

const emit = defineEmits<{ close: [] }>()

const dialogId = `ui-modal-${++uid}`
const titleId = `${dialogId}-title`

function requestClose() {
  if (props.busy) return
  emit('close')
}

useDialogFocus(() => props.open, `#${dialogId}`, requestClose)
</script>

<template>
  <div v-if="open" class="dialog-backdrop" @click="closeOnBackdrop && requestClose()" />
  <section
    v-if="open"
    :id="dialogId"
    class="ui-modal"
    :class="`ui-modal--${placement}`"
    role="dialog"
    aria-modal="true"
    :aria-labelledby="title ? titleId : undefined"
    :aria-label="!title ? ariaLabel : undefined"
    tabindex="-1"
  >
    <button type="button" class="icon-button ui-modal-close" aria-label="关闭" :disabled="busy" @click="requestClose">
      <X :size="18" />
    </button>
    <header v-if="title || $slots.header" class="ui-modal-header">
      <slot name="header"><h2 :id="titleId">{{ title }}</h2></slot>
    </header>
    <div class="ui-modal-body"><slot /></div>
    <footer v-if="$slots.footer" class="ui-modal-footer"><slot name="footer" /></footer>
  </section>
</template>

<style scoped>
.ui-modal {
  position: fixed;
  z-index: 51;
  display: grid;
  gap: 16px;
  max-height: calc(100vh - 48px);
  overflow: auto;
  padding: 28px 26px 22px;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.ui-modal--center {
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  width: min(560px, calc(100vw - 32px));
}

.ui-modal--drawer {
  right: 16px;
  top: 16px;
  bottom: 16px;
  width: min(420px, calc(100vw - 32px));
}

.ui-modal-close {
  position: absolute;
  top: 14px;
  right: 14px;
  border: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 88%, transparent);
}

.ui-modal-header {
  padding-right: 40px;
}

.ui-modal-header h2 {
  margin: 0;
}

.ui-modal-body {
  min-width: 0;
  color: var(--ink);
}

.ui-modal-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 9px;
  padding-top: 14px;
  border-top: 1px solid var(--border);
}

@media (prefers-reduced-motion: no-preference) {
  .dialog-backdrop {
    animation: ui-modal-fade var(--motion-medium) var(--ease) both;
  }

  .ui-modal {
    animation: ui-modal-fade var(--motion-medium) var(--ease) both;
  }
}

@keyframes ui-modal-fade {
  from { opacity: 0; }
  to { opacity: 1; }
}

@media (max-width: 760px) {
  .ui-modal--drawer {
    left: 12px;
    right: 12px;
    width: auto;
  }
}
</style>

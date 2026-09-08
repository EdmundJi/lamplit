<script setup lang="ts">
/**
 * Thin wrapper around the `.primary/.secondary/.danger/.icon-button` classes already
 * defined in `styles/global.css`. It adds no colours, sizes or radii of its own —
 * everything still comes from the shared tokens those classes read from `--control`,
 * `--radius`, `--focus`, etc. Use this when you want a typed `variant` prop instead of
 * remembering the class name; the raw classes remain fine for existing pages.
 */
type Variant = 'primary' | 'secondary' | 'danger' | 'icon'

const props = withDefaults(defineProps<{
  variant?: Variant
  type?: 'button' | 'submit' | 'reset'
  disabled?: boolean
  /** Required in practice for variant="icon" so the control has an accessible name. */
  ariaLabel?: string
}>(), {
  variant: 'primary',
  type: 'button',
  disabled: false,
})

defineEmits<{ click: [MouseEvent] }>()
</script>

<template>
  <button
    :type="props.type"
    class="button"
    :class="props.variant === 'icon' ? 'icon-button' : props.variant"
    :disabled="props.disabled"
    :aria-label="props.ariaLabel"
    @click="$emit('click', $event)"
  >
    <slot />
  </button>
</template>

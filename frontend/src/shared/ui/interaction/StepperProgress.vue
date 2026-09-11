<script setup lang="ts">
/**
 * A multi-step indicator that answers a completed step instead of sliding
 * quietly: the finished segment springs to full width and the new current one
 * takes over immediately, so done, current and upcoming stay distinguishable.
 */
const props = withDefaults(defineProps<{
  steps: number
  current: number
  label: string
  variant?: 'bar' | 'dots'
  selectable?: boolean
  stepLabel?: (step: number) => string
}>(), { variant: 'bar', selectable: false })

const emit = defineEmits<{ select: [number] }>()

function state(step: number) {
  return step < props.current ? 'done' : step === props.current ? 'current' : 'upcoming'
}
</script>

<template>
  <div
    class="stepper"
    :class="`stepper--${variant}`"
    :role="selectable ? 'group' : 'progressbar'"
    :aria-label="label"
    :aria-valuemin="selectable ? undefined : 1"
    :aria-valuemax="selectable ? undefined : steps"
    :aria-valuenow="selectable ? undefined : current"
    :aria-valuetext="selectable ? undefined : `第 ${current} 步，共 ${steps} 步`"
  >
    <component
      :is="selectable ? 'button' : 'span'"
      v-for="step in steps"
      :key="step"
      class="stepper-step"
      :type="selectable ? 'button' : undefined"
      :data-state="state(step)"
      :aria-label="selectable ? (stepLabel?.(step) ?? `第 ${step} 步`) : undefined"
      :aria-current="step === current ? 'step' : undefined"
      @click="selectable ? emit('select', step) : undefined"
    ><i class="stepper-fill" aria-hidden="true" /></component>
  </div>
</template>

<style scoped>
.stepper { display: flex; align-items: center; min-width: 0; }
.stepper-step { position: relative; overflow: hidden; padding: 0; border: 0; background: var(--surface-muted); }
.stepper-fill {
  display: block;
  height: 100%;
  width: 100%;
  border-radius: inherit;
  background: var(--primary);
  transform: scaleX(0);
  transform-origin: left center;
  transition: transform var(--motion-medium) var(--ease);
}
.stepper-step[data-state='done'] .stepper-fill,
.stepper-step[data-state='current'] .stepper-fill { transform: scaleX(1); }
.stepper-step[data-state='current'] .stepper-fill { background: color-mix(in srgb, var(--primary) 82%, var(--accent)); }

.stepper--bar { gap: 4px; }
.stepper--bar .stepper-step { flex: 1; height: 4px; border-radius: 999px; }

.stepper--dots { gap: 7px; }
.stepper--dots .stepper-step {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 999px;
  cursor: pointer;
  transition: width var(--motion-medium) var(--ease), background-color var(--motion-fast) var(--ease);
}
.stepper--dots .stepper-step[data-state='current'] { width: 22px; }
/* Dots read as three states: filled and quiet behind you, a pill for now, an empty track ahead. */
.stepper--dots .stepper-step[data-state='done'] .stepper-fill { background: color-mix(in srgb, var(--primary) 46%, var(--surface-muted)); }
.stepper--dots .stepper-step:focus-visible { outline: 3px solid color-mix(in srgb, var(--focus) 35%, transparent); outline-offset: 3px; }
</style>

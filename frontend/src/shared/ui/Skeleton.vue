<script setup lang="ts">
/**
 * The site currently has no loading skeleton anywhere (pages show plain "正在加载…" text
 * via `.empty` instead). This is a minimal shimmer block for spots where a shape preview
 * reads better than a loading sentence. `variant="text"` draws one or more lines,
 * `"circle"` an avatar-shaped dot, `"block"` a card-shaped rectangle.
 */
const props = withDefaults(defineProps<{
  variant?: 'text' | 'circle' | 'block'
  /** Number of lines for variant="text"; ignored otherwise. */
  lines?: number
  width?: string
  height?: string
  /** Accessible label for the loading region; falls back to a generic Chinese label. */
  label?: string
}>(), {
  variant: 'text',
  lines: 1,
  label: '正在加载',
})

const lineCount = () => Math.max(1, props.variant === 'text' ? props.lines : 1)
</script>

<template>
  <span v-if="variant === 'text'" class="skeleton-lines" role="status" :aria-label="label">
    <span
      v-for="line in lineCount()"
      :key="line"
      class="skeleton skeleton--text"
      :style="{ width: line === lineCount() && lineCount() > 1 ? '72%' : (width ?? '100%') }"
    />
  </span>
  <span
    v-else
    class="skeleton"
    :class="variant === 'circle' ? 'skeleton--circle' : 'skeleton--block'"
    role="status"
    :aria-label="label"
    :style="{ width: width ?? (variant === 'circle' ? '40px' : '100%'), height: height ?? (variant === 'circle' ? '40px' : '96px') }"
  />
</template>

<style scoped>
.skeleton-lines {
  display: grid;
  gap: 8px;
}

.skeleton {
  display: block;
  background:
    linear-gradient(
      100deg,
      var(--surface-muted) 30%,
      color-mix(in srgb, var(--surface-muted) 40%, var(--surface)) 50%,
      var(--surface-muted) 70%
    );
  background-size: 220% 100%;
}

.skeleton--text {
  height: 14px;
  border-radius: var(--radius);
}

.skeleton--circle {
  border-radius: 50%;
}

.skeleton--block {
  border-radius: var(--radius-card);
}

@media (prefers-reduced-motion: no-preference) {
  .skeleton {
    animation: skeleton-shimmer calc(var(--motion-slow) * 3) ease-in-out infinite;
  }
}

@keyframes skeleton-shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -20% 0; }
}
</style>

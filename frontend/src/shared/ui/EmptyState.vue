<script setup lang="ts">
/**
 * Typed wrapper around the `.empty` class from global.css. Existing pages keep using
 * `<p class="empty">…</p>` / `<div class="empty">…</div>` directly for loading text and
 * simple messages — this is for the richer cases (icon + title + description + action)
 * so those don't get hand re-built per page.
 */
withDefaults(defineProps<{
  title?: string
  description?: string
}>(), {})
</script>

<template>
  <div class="empty">
    <div v-if="$slots.icon" class="empty-icon" aria-hidden="true"><slot name="icon" /></div>
    <h3 v-if="title">{{ title }}</h3>
    <p v-if="description">{{ description }}</p>
    <slot />
    <div v-if="$slots.actions" class="empty-actions"><slot name="actions" /></div>
  </div>
</template>

<style scoped>
.empty-icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  margin: 0 auto 14px;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary);
}

.empty-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 9px;
  margin-top: 14px;
}
</style>

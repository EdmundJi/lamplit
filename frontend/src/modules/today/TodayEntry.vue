<script setup lang="ts">
import { defineAsyncComponent } from 'vue'
import { useWorkspaceModeStore } from '../../shared/ui/workspace-mode.store'
const mode = useWorkspaceModeStore()
const TodayView = defineAsyncComponent(() => import('./TodayView.vue'))
const ChecklistView = defineAsyncComponent(() => import('../checklist/ChecklistView.vue'))
</script>

<template>
  <ChecklistView v-if="mode.minimal" />
  <TodayView v-else />
</template>

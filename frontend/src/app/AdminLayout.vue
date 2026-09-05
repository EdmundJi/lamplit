<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { ArrowLeft, ShieldCheck } from 'lucide-vue-next'
import OperationGuideBar from '../shared/ui/OperationGuideBar.vue'
</script>

<template>
  <div class="admin-shell">
    <header>
      <RouterLink class="brand" to="/admin">
        <ShieldCheck :size="20" />
        治理控制台
      </RouterLink>
      <RouterLink class="return-link" to="/today">
        <ArrowLeft :size="17" />
        返回用户端
      </RouterLink>
    </header>
    <OperationGuideBar />
    <RouterView />
  </div>
</template>

<style scoped>
.admin-shell { min-height: 100vh; background: var(--canvas); }
.admin-shell > header { position: sticky; top: 0; z-index: 10; height: 64px; padding: 0 28px; display: flex; justify-content: space-between; align-items: center; border-bottom: 1px solid color-mix(in srgb, white 10%, transparent); background: var(--forest-deep); color: white; }
.admin-shell :deep(.operation-guide) { top: 58px; }
header a { color: white; text-decoration: none; }
.brand, .return-link { display: inline-flex; align-items: center; gap: 8px; }
.brand { font-weight: 800; }
.return-link { color: rgb(255 255 255 / 78%); font-size: 14px; }
@media (max-width: 560px) {
  .admin-shell > header { padding: 0 18px; }
}
</style>

<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import DesktopPet from './DesktopPet.vue'

onMounted(() => {
  document.documentElement.classList.add('desktop-pet-mode')
})

onBeforeUnmount(() => {
  document.documentElement.classList.remove('desktop-pet-mode')
})
</script>

<template>
  <main class="desktop-pet-window">
    <DesktopPet standalone />
  </main>
</template>

<style>
html.desktop-pet-mode,
html.desktop-pet-mode body,
html.desktop-pet-mode #app,
html.desktop-pet-mode .app-root { width: 100%; height: 100%; overflow: hidden; background: transparent; }
.desktop-pet-window { width: 100%; height: 100%; display: grid; place-items: center; background: transparent; }
</style>

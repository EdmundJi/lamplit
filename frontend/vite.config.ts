import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  envDir: '..',
  server: {
    proxy: {
      '/api/v1': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
  },
})

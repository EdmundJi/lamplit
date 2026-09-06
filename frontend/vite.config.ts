import { configDefaults, defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

const inContainer = process.env.VITE_DEV_CONTAINER === 'true'

export default defineConfig({
  plugins: [vue()],
  envDir: '..',
  server: {
    // 只在共享开发服务器的容器里打开（deploy/compose.dev.yaml 设置这个变量）：
    // 源码是从 macOS bind mount 进 Linux 容器的，inotify 事件跨不过虚拟机边界，
    // 不改成轮询的话 HMR 永远收不到改动。本机直接 pnpm dev 时保持默认，不受影响。
    watch: inContainer ? { usePolling: true, interval: 300 } : undefined,
    // 同上：开发服务器要让别人用局域网 IP 或主机名访问，Vite 默认会按 Host 头拦下来。
    allowedHosts: inContainer ? true : undefined,
    proxy: {
      '/api/v1': {
        // Point at a locally run backend with DEV_API_TARGET; defaults to the docker stack.
        target: process.env.DEV_API_TARGET ?? 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    exclude: [...configDefaults.exclude, 'e2e/**'],
  },
})

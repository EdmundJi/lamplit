import { configDefaults, defineConfig } from 'vitest/config'
import { existsSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'

const inContainer = process.env.VITE_DEV_CONTAINER === 'true'

/**
 * dev server 对 /assets/** 下不存在的文件返回 404，而不是 SPA 兜底的 index.html。
 *
 * 起因：`public/assets/town/characters/labour-anims.json` 不存在，dev server 把 index.html
 * 返回给了 Phaser 的 JSON 加载器，`JSON.parse("<!doctype html>...")` 抛出
 * "Unexpected token '<'"。这个错误发生在 Phaser 的 onProcess 里，加载器的 loaderror 事件
 * 接不到，只能变成 window 上的未捕获错误——而小镇的资源全走这条路（图集、室内地图 JSON、
 * 角色表），任何一个文件名写错都会以同样的方式静默炸掉，还伪装成"JSON 格式不对"。
 *
 * 返回 404 之后，Phaser 走正常的加载失败分支（labour 动画本来就有 `if (!manifest) return`
 * 的兜底），测试里也能直接从 failedRequests 看到是哪个文件缺了。
 */
function assets404() {
  return {
    name: 'assets-404-not-spa-fallback',
    apply: 'serve' as const,
    configureServer(server: { middlewares: { use: (fn: (req: any, res: any, next: () => void) => void) => void }; publicDir?: string }) {
      const publicDir = resolve(dirname(fileURLToPath(import.meta.url)), 'public')
      server.middlewares.use((req, res, next) => {
        const url = (req.url ?? '').split('?')[0]
        if (!url.startsWith('/assets/')) return next()
        if (existsSync(resolve(publicDir, '.' + url))) return next()
        res.statusCode = 404
        res.setHeader('content-type', 'text/plain; charset=utf-8')
        res.end(`404 ${url}\n`)
      })
    },
  }
}

export default defineConfig({
  plugins: [vue(), assets404()],
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
    // .pnpm-store holds symlinked copies of every workspace this store has ever served, including
    // ones in git worktrees that no longer exist. vitest globs into it, resolves each link, and
    // reports 70 "failed" suites that are nothing but dead symlinks - which buries the 408 real
    // results under an equal number of fake failures and makes the suite useless as a signal.
    exclude: [...configDefaults.exclude, 'e2e/**', '.pnpm-store/**'],
  },
})

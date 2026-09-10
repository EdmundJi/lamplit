<script setup lang="ts">
import { computed, ref } from 'vue'
import { ArrowLeft, ArrowRight, ChartNoAxesColumnIncreasing, Check, Paintbrush, ShieldCheck, Sparkles, Target, X } from 'lucide-vue-next'
import StepperProgress from './interaction/StepperProgress.vue'

type Slide = {
  eyebrow: string
  title: string
  body: string
  bullets: string[]
  icon: unknown
  visual: 'rhythm' | 'signal' | 'ai' | 'control'
}

const emit = defineEmits<{ dismiss: [] }>()

const slides: Slide[] = [
  {
    eyebrow: '第一步',
    title: '把四周目标落到今天',
    body: '系统会把目标下的周期任务安排到对应日期，你只需要处理眼前这一小步。',
    bullets: ['目标最多保持 3 个活跃项', '任务可以完成、部分完成、延期或跳过', '撤销入口保留最近一次记录'],
    icon: Target,
    visual: 'rhythm',
  },
  {
    eyebrow: '第二步',
    title: '用个人趋势看见节奏',
    body: '洞察页只呈现你的历史变化，不做排名，也不把经验值解释成能力或人格判断。',
    bullets: ['周计划兑现率', '有效行动与恢复次数', '维度趋势和个人最佳'],
    icon: ChartNoAxesColumnIncreasing,
    visual: 'signal',
  },
  {
    eyebrow: '第三步',
    title: 'AI 先建议，你再确认',
    body: 'AI 可以帮你整理任务建议和复盘草稿，但不会在你确认前替你创建正式任务。',
    bullets: ['建议清单可编辑', '安全边界优先', 'AI 记忆默认关闭'],
    icon: Sparkles,
    visual: 'ai',
  },
  {
    eyebrow: '最后',
    title: '数据和界面都由你掌控',
    body: '你可以调整通知、保留期、导出数据，也可以把界面调成更适合自己的样子。',
    bullets: ['深浅主题和强调色', '舒适或紧凑密度', '动画强度可降低或关闭'],
    icon: Paintbrush,
    visual: 'control',
  },
]

const index = ref(0)
const current = computed(() => slides[index.value])
const isLast = computed(() => index.value === slides.length - 1)

function next() {
  if (isLast.value) emit('dismiss')
  else index.value += 1
}

function previous() {
  if (index.value > 0) index.value -= 1
}
</script>

<template>
  <div class="welcome-backdrop" role="presentation">
    <section class="welcome-dialog" role="dialog" aria-modal="true" aria-labelledby="welcome-title">
      <button class="icon-button close-button" type="button" aria-label="关闭欢迎介绍" @click="emit('dismiss')">
        <X :size="18"></X>
      </button>

      <div class="welcome-visual" :data-visual="current.visual" aria-hidden="true">
        <div class="visual-mark">
          <component :is="current.icon" :size="34"></component>
        </div>
        <span v-for="dot in 8" :key="dot" class="visual-dot"></span>
        <span v-for="bar in 5" :key="bar" class="visual-bar"></span>
        <ShieldCheck class="visual-shield" :size="28"></ShieldCheck>
      </div>

      <div class="welcome-copy">
        <p class="eyebrow">{{ current.eyebrow }}</p>
        <h2 id="welcome-title">{{ current.title }}</h2>
        <p class="welcome-body">{{ current.body }}</p>
        <ul>
          <li v-for="item in current.bullets" :key="item">
            <Check :size="15"></Check>
            <span>{{ item }}</span>
          </li>
        </ul>
      </div>

      <footer class="welcome-actions">
        <StepperProgress
          class="welcome-dots"
          variant="dots"
          selectable
          :steps="slides.length"
          :current="index + 1"
          label="欢迎介绍进度"
          :step-label="slide => `第 ${slide} 页`"
          @select="index = $event - 1"
        />
        <div class="actions">
          <button type="button" class="secondary" @click="emit('dismiss')">跳过</button>
          <button v-if="index > 0" type="button" class="secondary" aria-label="上一页" @click="previous">
            <ArrowLeft :size="17"></ArrowLeft>
          </button>
          <button type="button" class="primary" @click="next">
            {{ isLast ? '立即开始' : '下一页' }}
            <ArrowRight v-if="!isLast" :size="17"></ArrowRight>
          </button>
        </div>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.welcome-backdrop {
  position: fixed;
  inset: 0;
  z-index: 40;
  display: grid;
  place-items: center;
  padding: 20px;
  background: color-mix(in srgb, var(--canvas) 74%, rgb(22 29 24 / 68%));
}

.welcome-dialog {
  position: relative;
  width: min(820px, 100%);
  display: grid;
  grid-template-columns: minmax(260px, .9fr) minmax(0, 1fr);
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: var(--surface);
  box-shadow: var(--shadow);
}

.close-button {
  position: absolute;
  top: 12px;
  right: 12px;
  z-index: 2;
  border: 1px solid var(--border);
  background: color-mix(in srgb, var(--surface) 88%, transparent);
}

.welcome-visual {
  position: relative;
  min-height: 420px;
  overflow: hidden;
  background:
    linear-gradient(150deg, color-mix(in srgb, var(--primary) 20%, var(--surface)) 0%, var(--surface-muted) 100%);
}

.visual-mark {
  position: absolute;
  left: 50%;
  top: 50%;
  z-index: 1;
  width: 112px;
  height: 112px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--primary) 28%, var(--border));
  border-radius: 50%;
  background: var(--surface-raised);
  color: var(--primary);
  transform: translate(-50%, -50%);
}

.visual-dot,
.visual-bar,
.visual-shield {
  position: absolute;
  color: var(--primary);
}

.visual-dot {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  background: currentColor;
  opacity: .26;
}

.visual-dot:nth-of-type(1) { left: 16%; top: 22%; }
.visual-dot:nth-of-type(2) { left: 26%; top: 70%; }
.visual-dot:nth-of-type(3) { left: 44%; top: 16%; }
.visual-dot:nth-of-type(4) { left: 64%; top: 78%; }
.visual-dot:nth-of-type(5) { left: 78%; top: 30%; }
.visual-dot:nth-of-type(6) { left: 12%; top: 52%; }
.visual-dot:nth-of-type(7) { left: 84%; top: 58%; }
.visual-dot:nth-of-type(8) { left: 54%; top: 42%; }

.visual-bar {
  left: 18%;
  right: 18%;
  height: 8px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--primary) 32%, transparent);
  transform-origin: left center;
}

.visual-bar:nth-of-type(9) { top: 28%; }
.visual-bar:nth-of-type(10) { top: 39%; width: 48%; }
.visual-bar:nth-of-type(11) { top: 61%; width: 54%; }
.visual-bar:nth-of-type(12) { top: 72%; width: 36%; }
.visual-bar:nth-of-type(13) { top: 83%; width: 62%; }

.visual-shield {
  right: 22%;
  bottom: 18%;
  opacity: 0;
}

.welcome-copy {
  display: grid;
  align-content: center;
  padding: 54px 48px 26px;
}

.welcome-copy h2 {
  margin: 0;
  font-family: Georgia, "Songti SC", serif;
  font-size: 30px;
  line-height: 1.25;
}

.welcome-body {
  margin: 14px 0 0;
  color: var(--muted);
  line-height: 1.75;
}

.welcome-copy ul {
  display: grid;
  gap: 10px;
  padding: 0;
  margin: 22px 0 0;
  list-style: none;
}

.welcome-copy li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  color: var(--ink);
  line-height: 1.55;
}

.welcome-copy li svg {
  flex: none;
  margin-top: 4px;
  color: var(--primary);
}

.welcome-actions {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
  border-top: 1px solid var(--border);
}

.welcome-dots :deep(.stepper-step) {
  min-height: 0;
  background: var(--border);
}

[data-visual='signal'] .visual-bar {
  transform: scaleX(.42);
}

[data-visual='signal'] .visual-bar:nth-of-type(10),
[data-visual='signal'] .visual-bar:nth-of-type(12) {
  transform: scaleX(.74);
}

[data-visual='ai'] .visual-dot {
  opacity: .42;
}

[data-visual='control'] .visual-shield {
  opacity: 1;
}

@media (prefers-reduced-motion: no-preference) {
  .welcome-backdrop {
    animation: welcome-backdrop var(--motion-medium) var(--ease) both;
  }

  .welcome-dialog {
    animation: welcome-dialog var(--motion-medium) var(--ease) both;
  }
}

@keyframes welcome-backdrop {
  from { opacity: 0; }
  to { opacity: 1; }
}

@keyframes welcome-dialog {
  from { opacity: 0; transform: scale(.98); }
  to { opacity: 1; transform: scale(1); }
}

@media (max-width: 720px) {
  .welcome-dialog {
    grid-template-columns: 1fr;
  }

  .welcome-visual {
    min-height: 210px;
  }

  .welcome-copy {
    padding: 28px 22px 10px;
  }

  .welcome-copy h2 {
    font-size: 24px;
  }

  .welcome-actions {
    align-items: flex-start;
    flex-direction: column;
  }

  .welcome-actions .actions {
    width: 100%;
  }

  .welcome-actions .actions .primary,
  .welcome-actions .actions .secondary {
    flex: 1;
  }
}
</style>

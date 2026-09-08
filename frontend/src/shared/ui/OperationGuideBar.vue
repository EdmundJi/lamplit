<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, ListChecks } from 'lucide-vue-next'
import { disclose as vDisclose } from './interaction/disclose'
import { useRoute } from 'vue-router'

type Guide = {
  label: string
  steps: string[]
}

const route = useRoute()

function resolveGuide(path: string): Guide | null {
  const guides: Record<string, Guide> = {
    '/today': { label: '今日行动', steps: ['检查状态', '选择任务', '记录结果'] },
    '/goals': { label: '目标规划', steps: ['新建目标', '添加周期任务', '回到今日执行'] },
    '/partners': { label: '成长伙伴', steps: ['创建或选择伙伴', '互动与照顾', '设置桌宠'] },
    '/friends': { label: '好友协作', steps: ['按邮箱添加', '处理好友申请', '查看或交流'] },
    '/friends/chat': { label: '消息中心', steps: ['选择会话', '发起群聊', '进入交流'] },
    '/attributes': { label: '属性查看', steps: ['查看整体雷达', '比较属性明细', '调整后续行动'] },
    '/insights': { label: '成长洞察', steps: ['查看趋势', '检查成就', '完成周复盘'] },
    '/ai': { label: 'AI 协作', steps: ['选择场景', '描述具体需求', '核对后采用'] },
    '/profile': { label: '个人主页', steps: ['查看成长状态', '选择佩戴称号', '设置隐私状态'] },
    '/settings': { label: '系统设置', steps: ['调整外观', '设置通知与偏好', '管理个人数据'] },
    '/admin': { label: '治理流程', steps: ['查看概览', '管理账号', '处理安全事件', '核对审计记录'] },
  }

  if (guides[path]) return guides[path]
  if (/^\/friends\/groups\/[^/]+$/.test(path)) {
    return { label: '群聊顺序', steps: ['查看新消息', '输入内容', '发送交流'] }
  }
  if (/^\/friends\/[^/]+\/chat$/.test(path)) {
    return { label: '聊天顺序', steps: ['查看新消息', '输入内容', '发送交流'] }
  }
  if (/^\/friends\/[^/]+$/.test(path)) {
    return { label: '好友详情', steps: ['查看近况', '了解成长', '发起交流'] }
  }
  return null
}

const guide = computed(() => resolveGuide(route.path))
</script>

<template>
  <details v-if="guide" v-disclose class="operation-guide" :aria-label="guide.label">
    <summary><ListChecks :size="14" />{{ guide.label }} · 使用提示</summary>
    <div class="guide-inner">
      <div class="guide-label">
        <ListChecks :size="18" />
        <span><small>操作指南</small><strong>{{ guide.label }}</strong></span>
      </div>
      <ol :style="{ '--guide-steps': guide.steps.length }">
        <li v-for="(step, index) in guide.steps" :key="step">
          <span class="step-number">{{ index + 1 }}</span>
          <span class="step-label">{{ step }}</span>
          <ChevronRight v-if="index < guide.steps.length - 1" :size="15" aria-hidden="true" />
        </li>
      </ol>
    </div>
  </details>
</template>

<style scoped>
.operation-guide {
  position: relative;
  z-index: 6;
  border-bottom: 1px solid var(--border);
  background: var(--canvas);
  color: var(--muted);
}
.operation-guide summary { display: flex; align-items: center; gap: 8px; width: fit-content; margin-left: auto; padding: 7px 32px; cursor: pointer; font-size: 11px; list-style: none; }
.operation-guide summary::-webkit-details-marker { display: none; }
.operation-guide summary::after { content: '+'; margin-left: 10px; }
.operation-guide[open] summary::after { content: '−'; }

.guide-inner {
  width: min(100%, 1120px);
  min-height: 62px;
  margin: 0 auto;
  padding: 9px 30px;
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr);
  align-items: center;
  gap: 24px;
}

.guide-label {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--primary);
}

.guide-label > span {
  display: grid;
  gap: 1px;
}

.guide-label small {
  color: var(--muted);
  font-size: 10px;
  font-weight: 700;
}

.guide-label strong {
  font-size: 13px;
  white-space: nowrap;
}

ol {
  min-width: 0;
  display: grid;
  grid-template-columns: repeat(var(--guide-steps), minmax(0, 1fr));
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  min-width: 0;
  display: grid;
  grid-template-columns: 24px minmax(0, 1fr) 15px;
  align-items: center;
  gap: 7px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 700;
}

li:last-child {
  grid-template-columns: 24px minmax(0, 1fr);
}

.step-number {
  width: 24px;
  height: 24px;
  display: grid;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--primary) 32%, var(--border));
  border-radius: 50%;
  background: color-mix(in srgb, var(--primary-soft) 58%, var(--surface));
  color: var(--primary-strong);
  font-size: 11px;
  font-weight: 850;
}

.step-label {
  min-width: 0;
  line-height: 1.35;
  overflow-wrap: anywhere;
}

li > svg {
  color: color-mix(in srgb, var(--muted) 62%, transparent);
}

@media (max-width: 760px) {
  .guide-inner {
    padding-inline: 18px;
    gap: 14px;
  }
}

@media (max-width: 560px) {
  .guide-inner {
    min-height: 96px;
    grid-template-columns: 1fr;
    align-content: center;
    gap: 8px;
    padding-block: 10px;
  }

  .guide-label > span {
    display: flex;
    align-items: baseline;
    gap: 7px;
  }

  ol {
    gap: 4px;
  }

  li {
    grid-template-columns: 20px minmax(0, 1fr);
    align-items: start;
    gap: 5px;
    font-size: 11px;
  }

  li:last-child {
    grid-template-columns: 20px minmax(0, 1fr);
  }

  li > svg {
    display: none;
  }

  .step-number {
    width: 20px;
    height: 20px;
    font-size: 10px;
  }
}
</style>

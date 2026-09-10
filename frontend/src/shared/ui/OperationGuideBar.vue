<script lang="ts">
/**
 * Route -> guide lookup lives in a plain <script> block (module scope, not
 * per-instance) so UserLayout can import `resolveGuide` directly to decide
 * whether the "使用提示" button has anything to show, without mounting this
 * component just to ask.
 */
export type Guide = {
  label: string
  steps: string[]
}

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

export function resolveGuide(path: string): Guide | null {
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
</script>

<script setup lang="ts">
import { computed } from 'vue'
import { ListChecks } from 'lucide-vue-next'
import { useRoute } from 'vue-router'
import StepperProgress from './interaction/StepperProgress.vue'

const route = useRoute()
const guide = computed(() => resolveGuide(route.path))
</script>

<template>
  <section v-if="guide" class="operation-guide" :aria-label="guide.label">
    <div class="guide-label">
      <ListChecks :size="16" />
      <span><small>操作指南</small><strong>{{ guide.label }}</strong></span>
    </div>
    <StepperProgress
      class="guide-progress"
      :steps="guide.steps.length"
      :current="0"
      :selectable="false"
      variant="dots"
      label="操作步骤"
    />
    <ol>
      <li v-for="(step, index) in guide.steps" :key="step">
        <span class="step-number">{{ index + 1 }}</span>
        <span class="step-label">{{ step }}</span>
      </li>
    </ol>
  </section>
</template>

<style scoped>
.operation-guide {
  display: grid;
  gap: 14px;
  color: var(--muted);
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
  font-size: 14px;
}

.guide-progress {
  width: 100%;
}

ol {
  display: grid;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}

li {
  display: flex;
  align-items: baseline;
  gap: 9px;
  color: var(--ink);
  font-size: 13px;
}

.step-number {
  flex: none;
  width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: color-mix(in srgb, var(--primary-soft) 58%, var(--surface));
  color: var(--primary-strong);
  font-size: 10px;
  font-weight: 800;
}

.step-label {
  min-width: 0;
  line-height: 1.4;
  overflow-wrap: anywhere;
}
</style>

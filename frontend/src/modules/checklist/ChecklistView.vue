<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { Check, Plus, GripVertical, MoreHorizontal, ArrowUp, ArrowDown, Clock3, Trash2, Undo2 } from 'lucide-vue-next'
import { useDragSort } from '../../shared/ui/interaction/use-drag-sort'
import { disclose as vDisclose } from '../../shared/ui/interaction/disclose'
import SegmentedControl from '../../shared/ui/interaction/SegmentedControl.vue'
import EmptyState from '../../shared/ui/EmptyState.vue'
import { useChecklist, type ChecklistTask } from './checklist.logic'

const listTabOptions = [
  { value: 'today', label: '今天' },
  { value: 'all', label: '全部' },
]

const { tab, title, loading, saving, pending, error, loadError, notice, last, undoing, today, active, completed, load, add, act, tomorrow, undo, rename, move } = useChecklist()
const input = ref<HTMLInputElement | null>(null)
const editing = ref<string | null>(null)
const editTitle = ref('')
const editInput = ref<HTMLInputElement[]>([])
const menu = ref<string | null>(null)
const list = ref<HTMLElement | null>(null)
const { draggingId, start } = useDragSort({ container: () => list.value, move })
async function submit(event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  await create()
}
async function create() {
  await add()
  await nextTick()
  input.value?.focus()
}
async function edit(task: ChecklistTask) {
  menu.value = null
  editing.value = task.publicId
  editTitle.value = task.taskTitle
  await nextTick()
  editInput.value[0]?.focus()
  editInput.value[0]?.select()
}
async function saveTitle(task: ChecklistTask, event: KeyboardEvent) {
  if (event.isComposing || event.keyCode === 229) return
  event.preventDefault()
  if (await rename(task, editTitle.value)) editing.value = null
}
function shift(task: ChecklistTask, offset: number) {
  const target = active.value[active.value.findIndex(item => item.publicId === task.publicId) + offset]
  if (target) move(task.publicId, target.publicId)
}
</script>

<template>
  <section class="checklist" aria-labelledby="checklist-heading">
    <header class="checklist-head">
      <div><p class="checklist-date">{{ today.replaceAll('-', ' / ') }}</p><h1 id="checklist-heading">{{ tab === 'today' ? '今天' : '全部任务' }}<span v-if="!loading && active.length">{{ active.length }}</span></h1></div>
      <nav class="list-tabs" aria-label="清单范围">
        <SegmentedControl
          :model-value="tab"
          :options="listTabOptions"
          label="清单范围"
          @update:model-value="value => tab = value as typeof tab"
        />
      </nav>
    </header>

    <form class="quick-add" @submit.prevent="create">
      <Plus :size="20" aria-hidden="true" />
      <input ref="input" v-model="title" aria-label="添加一件事" placeholder="添加一件事…" maxlength="160" :disabled="saving" autocomplete="off" @keydown.enter="submit">
      <button type="submit" :disabled="saving || !title.trim()">{{ saving ? '保存中…' : '添加' }}</button>
    </form>
    <p v-if="error" class="checklist-error" role="alert">{{ error }}</p>
    <p v-if="loadError" class="checklist-error" role="alert">{{ loadError }} <button @click="load">重新加载</button></p>
    <div v-if="notice || last" class="list-feedback" role="status"><span>{{ notice }}</span><button v-if="last" :disabled="undoing" @click="undo"><Undo2 :size="14" />{{ undoing ? '撤销中…' : '撤销上次' }}</button></div>

    <p v-if="loading" class="list-empty" role="status">正在加载清单…</p>
    <template v-else>
      <ul ref="list" class="checklist-items" aria-label="待办事项">
        <li v-for="(task, index) in active" :key="task.publicId" class="checklist-row" :class="{ 'is-dragging': draggingId === task.publicId }" :data-sort-id="task.publicId">
          <button
            class="drag-handle"
            type="button"
            :aria-label="`调整顺序：${task.taskTitle}`"
            aria-keyshortcuts="ArrowUp ArrowDown"
            @pointerdown="start(task.publicId, $event)"
            @keydown.up.prevent="shift(task, -1)"
            @keydown.down.prevent="shift(task, 1)"
          ><GripVertical :size="15" /></button>
          <button class="task-check" :aria-label="`完成：${task.taskTitle}`" :disabled="pending.has(task.publicId)" @click="act(task, 'COMPLETED')"><Check v-if="pending.has(task.publicId)" :size="14" /></button>
          <div class="task-text">
            <template v-if="editing === task.publicId">
              <input ref="editInput" v-model="editTitle" class="title-edit" aria-label="修改任务名称" maxlength="160" :disabled="pending.has(task.publicId)" @keydown.enter="saveTitle(task, $event)" @keydown.esc="editing = null">
              <div class="edit-actions"><button :disabled="!editTitle.trim() || pending.has(task.publicId)" @click="rename(task, editTitle).then(saved => { if (saved) editing = null })">保存</button><button @click="editing = null">取消</button><span>周期任务的名称会同步修改</span></div>
            </template>
            <button v-else class="task-title" :disabled="pending.has(task.publicId)" @click="edit(task)">{{ task.taskTitle }}</button>
            <span v-if="task.localDate > today" class="task-date">{{ task.localDate }}</span>
          </div>
          <div class="task-menu-wrap" @keydown.esc="menu = null">
            <button class="task-menu-toggle" :aria-label="`更多：${task.taskTitle}`" :aria-expanded="menu === task.publicId" :disabled="pending.has(task.publicId)" @click="menu = menu === task.publicId ? null : task.publicId"><MoreHorizontal :size="19" /></button>
            <div v-if="menu === task.publicId" class="task-menu">
              <button :disabled="index === 0" @click="shift(task, -1); menu = null"><ArrowUp :size="15" />上移</button>
              <button :disabled="index === active.length - 1" @click="shift(task, 1); menu = null"><ArrowDown :size="15" />下移</button>
              <button @click="tomorrow(task); menu = null"><Clock3 :size="15" />移到明天</button>
              <button @click="act(task, 'CANCELLED'); menu = null"><Trash2 :size="15" />删除本次</button>
            </div>
          </div>
        </li>
      </ul>
      <EmptyState
        v-if="!active.length && !loadError"
        class="list-empty"
        :sprite="completed.length ? 'chicken_white_idle_1' : 'rabbit_brown_idle_1'"
        :title="completed.length ? '都做好了。' : tab === 'today' ? '今天还没有待办。' : '清单是空的。'"
        :description="completed.length ? '有新的事情，随时记下来。' : '在上方写下第一件事。'"
      />
      <details v-if="completed.length" v-disclose class="completed-list"><summary>已完成 {{ completed.length }} 项</summary><ul><li v-for="task in completed" :key="task.publicId"><Check :size="15" /><span>{{ task.taskTitle }}</span></li></ul></details>
    </template>
  </section>
</template>

<style scoped>
.checklist { width: min(100%, 744px); margin: 0 auto; padding: 56px 24px 96px; color: var(--ink); }
.checklist-head { display: flex; justify-content: space-between; align-items: center; gap: 20px; margin-bottom: 32px; }
.checklist-date { font-size: 12px; color: var(--muted); letter-spacing: .08em; margin: 0 0 10px; }
h1 { font-size: 32px; line-height: 1.3; margin: 0; font-weight: 650; letter-spacing: -.03em; }
h1 span { display: inline-block; margin-left: 12px; vertical-align: middle; font-size: 14px; font-weight: 400; color: var(--muted); letter-spacing: 0; }
button, input { font: inherit; }
button { cursor: pointer; }
button:disabled { opacity: .45; cursor: default; }
.list-tabs { display: flex; }
.quick-add { display: flex; gap: 12px; align-items: center; background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-card); padding: 10px 12px 10px 18px; color: var(--muted); }
.quick-add:focus-within { border-color: var(--primary); }
.quick-add input { min-width: 0; flex: 1; border: 0; outline: none; box-shadow: none; padding: 10px 0; background: transparent; color: var(--ink); font-size: 15px; }
.quick-add button { flex-shrink: 0; background: var(--primary); color: var(--on-primary, #fff); border: 0; border-radius: var(--radius-card); padding: 9px 14px; font-size: 13px; }
.checklist-items, .completed-list ul { list-style: none; padding: 0; margin: 22px 0 0; }
.checklist-row { position: relative; display: flex; align-items: center; gap: 12px; min-height: 64px; border-bottom: 1px solid var(--border); padding: 10px 0; }
.drag-handle { color: var(--muted); opacity: .35; cursor: grab; display: grid; place-items: center; width: 22px; height: 44px; min-height: 0; padding: 0; border: 0; border-radius: var(--radius); background: transparent; flex-shrink: 0; touch-action: none; }
.checklist-row:hover .drag-handle, .drag-handle:focus-visible { opacity: 1; }
/* The row being carried lifts above its neighbours while they slide aside underneath. */
.is-dragging { z-index: 2; cursor: grabbing; border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow-soft); }
.is-dragging .drag-handle { opacity: 1; cursor: grabbing; }
.task-check { width: 24px; height: 24px; min-height: 24px; flex-shrink: 0; border: 1.5px solid var(--muted); border-radius: 50%; background: transparent; padding: 0; display: grid; place-items: center; color: var(--primary); position: relative; }
.task-check::before { content: ''; position: absolute; inset: -10px; }
.task-check:hover { border-color: var(--primary); background: var(--primary-soft); }
.task-text { flex: 1; min-width: 0; }
.task-title { text-align: left; overflow-wrap: anywhere; width: 100%; border: 0; background: none; color: var(--ink); padding: 10px 0; line-height: 1.5; font-size: 15px; }
.task-date { display: block; color: var(--muted); font-size: 11px; }
.title-edit { width: 100%; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); }
.edit-actions { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; margin-top: 8px; }
.edit-actions button, .list-feedback button, .checklist-error button { background: none; border: 0; color: var(--primary); padding: 4px 0; }
.edit-actions span { color: var(--muted); }
.task-menu-wrap { position: relative; }
.task-menu-toggle { border: 0; color: var(--muted); background: transparent; width: 44px; height: 44px; display: grid; place-items: center; border-radius: var(--radius-card); }
.task-menu-toggle:hover { background: var(--surface-muted); }
.task-menu { position: absolute; z-index: 5; right: 0; top: 40px; min-width: 152px; background: var(--surface); padding: 6px; border: 1px solid var(--border); border-radius: var(--radius-card); box-shadow: var(--shadow); }
.task-menu button { display: flex; align-items: center; gap: 10px; width: 100%; padding: 12px; font-size: 13px; background: none; border: 0; border-radius: var(--radius); color: var(--ink); text-align: left; }
.task-menu button:hover { background: var(--surface-muted); }
.list-feedback { display: flex; gap: 18px; align-items: center; margin-top: 14px; font-size: 12px; color: var(--muted); }
.list-feedback button { display: inline-flex; gap: 5px; align-items: center; }
.checklist-error { font-size: 13px; color: var(--danger, #ad3636); }
.list-empty { padding: 52px 0; text-align: center; }
.list-empty :deep(.empty-sprite) { margin: 0 auto 14px; }
.list-empty :deep(h3) { font-size: 15px; margin: 0 0 8px; }
.list-empty :deep(p) { font-size: 13px; }
.completed-list { margin-top: 32px; color: var(--muted); font-size: 13px; }
.completed-list summary { cursor: pointer; padding: 12px 0; }
.completed-list ul { margin-top: 8px; }
.completed-list li { display: flex; align-items: baseline; gap: 15px; padding: 12px 28px; }
.completed-list li span { text-decoration: line-through; overflow-wrap: anywhere; }
@media (max-width: 600px) { .checklist { padding: 32px 20px 72px; } .checklist-head { margin-bottom: 24px; } h1 { font-size: 28px; } .drag-handle { opacity: .6; } .checklist-row { gap: 14px; } }
</style>

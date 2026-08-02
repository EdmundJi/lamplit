<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Activity, ClipboardList, FileClock, Lock, RefreshCw, ShieldAlert, ShieldCheck, UserPlus, Users } from 'lucide-vue-next'
import { api } from '../../shared/api/client'

type Metrics = Record<string, number>
type SafetyEvent = { publicId: string; scene: string; riskLevel: string; direction: string; excerpt: string; reviewStatus: string; createdAt: string }
type AuditEntry = { publicId: string; action: string; resourceType: string; resourcePublicId: string; outcome: string; requestId: string; createdAt: string }
type AdminUser = { publicId: string; email: string; displayName: string; timezone: string; status: string; role: string; createdAt: string; updatedAt: string }
type CreatedAdmin = { user: AdminUser; mfaSecret: string }

const roleOptions = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'CONTENT_OPERATOR', label: '内容运营' },
  { value: 'SAFETY_OPERATOR', label: '安全审核' },
  { value: 'USER', label: '普通用户' },
]
const statusOptions = [
  { value: 'ACTIVE', label: '正常' },
  { value: 'LOCKED', label: '锁定' },
  { value: 'DELETION_PENDING', label: '注销冷静期' },
  { value: 'DELETED', label: '已删除' },
]
const metricLabels: Record<string, string> = { activeUsers: '活跃用户', taskEvents: '任务事件', safetyEvents: '安全事件', deletionBacklog: '注销积压' }
const metricIcons = [Users, Activity, ShieldAlert, FileClock]

const tab = ref<'overview' | 'users' | 'safety' | 'audit'>('overview')
const metrics = ref<Metrics | null>(null)
const events = ref<SafetyEvent[]>([])
const audits = ref<AuditEntry[]>([])
const users = ref<AdminUser[]>([])
const error = ref('')
const feedback = ref('')
const loading = ref(false)
const createdSecret = ref('')
const createForm = reactive({ email: '', password: '', displayName: '', role: 'ADMIN', timezone: Intl.DateTimeFormat().resolvedOptions().timeZone, mfaSecret: '' })

const administrators = computed(() => users.value.filter(user => user.role !== 'USER'))
const lockedUsers = computed(() => users.value.filter(user => user.status !== 'ACTIVE'))

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [metricData, eventData, auditData, userData] = await Promise.all([
      api.get<Metrics>('/admin/metrics'),
      api.get<SafetyEvent[]>('/admin/safety/events'),
      api.get<AuditEntry[]>('/admin/audit'),
      api.get<AdminUser[]>('/admin/users'),
    ])
    metrics.value = metricData
    events.value = eventData
    audits.value = auditData
    users.value = userData
  } catch {
    error.value = '无权访问或管理数据暂时不可用'
  } finally {
    loading.value = false
  }
}

async function createAdmin() {
  error.value = ''
  feedback.value = ''
  createdSecret.value = ''
  try {
    const result = await api.post<CreatedAdmin>('/admin/users/admins', { ...createForm, mfaSecret: createForm.mfaSecret || null })
    createdSecret.value = result.mfaSecret
    feedback.value = `已创建 ${roleName(result.user.role)}：${result.user.email}`
    Object.assign(createForm, { email: '', password: '', displayName: '', role: 'ADMIN', timezone: createForm.timezone, mfaSecret: '' })
    await load()
  } catch {
    error.value = '后台账号未创建，请检查邮箱、密码和权限'
  }
}

async function updateRole(user: AdminUser, role: string) {
  error.value = ''
  try {
    const updated = await api.post<AdminUser>(`/admin/users/${user.publicId}/role`, { role })
    replaceUser(updated)
  } catch {
    error.value = '角色未更新，请确认不能降级自己的管理员账号'
  }
}

async function updateStatus(user: AdminUser, status: string) {
  error.value = ''
  try {
    const updated = await api.post<AdminUser>(`/admin/users/${user.publicId}/status`, { status })
    replaceUser(updated)
  } catch {
    error.value = '状态未更新，请确认不能停用自己的管理员账号'
  }
}

function replaceUser(user: AdminUser) {
  users.value = users.value.map(item => item.publicId === user.publicId ? user : item)
}

function roleName(role: string) {
  return roleOptions.find(item => item.value === role)?.label ?? role
}

function statusName(status: string) {
  return statusOptions.find(item => item.value === status)?.label ?? status
}

function roleChoices(user: AdminUser) {
  return user.role === 'USER' ? roleOptions.filter(item => item.value === 'USER') : roleOptions
}

function timeLabel(value: string) {
  return new Date(value).toLocaleString('zh-CN', { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

onMounted(load)
</script>

<template>
  <main class="page admin-page">
    <header class="page-head">
      <div>
        <p class="eyebrow">权限、内容、安全与审计</p>
        <h1>治理工作台</h1>
      </div>
      <button class="secondary" type="button" :disabled="loading" @click="load">
        <RefreshCw :size="17" :class="{ spinning: loading }" />
        刷新
      </button>
    </header>

    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="feedback" class="feedback-banner" role="status">{{ feedback }}</p>

    <nav class="admin-tabs" aria-label="治理分区">
      <button type="button" :aria-pressed="tab === 'overview'" @click="tab = 'overview'"><Activity :size="16" />概览</button>
      <button type="button" :aria-pressed="tab === 'users'" @click="tab = 'users'"><Users :size="16" />账号</button>
      <button type="button" :aria-pressed="tab === 'safety'" @click="tab = 'safety'"><ShieldAlert :size="16" />安全</button>
      <button type="button" :aria-pressed="tab === 'audit'" @click="tab = 'audit'"><ClipboardList :size="16" />审计</button>
    </nav>

    <section v-if="tab === 'overview'" class="admin-stack">
      <div v-if="metrics" class="admin-metrics">
        <article v-for="([key, value], index) in Object.entries(metrics)" :key="key">
          <component :is="metricIcons[index] ?? Activity" :size="18" />
          <span>{{ metricLabels[key] ?? key }}</span>
          <strong>{{ value }}</strong>
        </article>
      </div>
      <section class="band overview-grid">
        <article>
          <p class="eyebrow">后台账号</p>
          <strong>{{ administrators.length }}</strong>
          <span>非普通用户，需要 MFA 登录</span>
        </article>
        <article>
          <p class="eyebrow">异常状态</p>
          <strong>{{ lockedUsers.length }}</strong>
          <span>锁定、注销中或已删除账号</span>
        </article>
        <article>
          <p class="eyebrow">安全队列</p>
          <strong>{{ events.filter(item => item.reviewStatus !== 'RESOLVED').length }}</strong>
          <span>待复核风险事件</span>
        </article>
      </section>
    </section>

    <section v-else-if="tab === 'users'" class="admin-stack">
      <form class="band create-admin" @submit.prevent="createAdmin">
        <div class="section-head">
          <div>
            <p class="eyebrow">创建后台账号</p>
            <h2>生成带 MFA 的账号</h2>
          </div>
          <UserPlus :size="20" />
        </div>
        <div class="admin-form-grid">
          <div class="field"><label for="admin-email">邮箱</label><input id="admin-email" v-model="createForm.email" type="email" required></div>
          <div class="field"><label for="admin-name">称呼</label><input id="admin-name" v-model="createForm.displayName" maxlength="80" placeholder="后台账号"></div>
          <div class="field"><label for="admin-role">角色</label><select id="admin-role" v-model="createForm.role"><option v-for="role in roleOptions.filter(item => item.value !== 'USER')" :key="role.value" :value="role.value">{{ role.label }}</option></select></div>
          <div class="field"><label for="admin-password">初始密码</label><input id="admin-password" v-model="createForm.password" type="password" minlength="12" required></div>
          <div class="field"><label for="admin-timezone">时区</label><input id="admin-timezone" v-model="createForm.timezone" required></div>
          <div class="field"><label for="admin-mfa">MFA Secret（可留空自动生成）</label><input id="admin-mfa" v-model="createForm.mfaSecret" autocomplete="off"></div>
        </div>
        <div class="actions">
          <button class="primary">创建账号</button>
        </div>
        <p v-if="createdSecret" class="secret-box"><Lock :size="16" /> 请立即保存并导入认证器：<code>{{ createdSecret }}</code></p>
      </form>

      <section class="band">
        <div class="section-head">
          <div>
            <p class="eyebrow">账号权限</p>
            <h2>用户与后台角色</h2>
          </div>
          <ShieldCheck :size="20" />
        </div>
        <div class="table-wrap">
          <table>
            <thead><tr><th>用户</th><th>角色</th><th>状态</th><th>时区</th><th>更新时间</th></tr></thead>
            <tbody>
              <tr v-for="user in users" :key="user.publicId">
                <td><strong>{{ user.displayName }}</strong><small>{{ user.email }}</small></td>
                <td><select :value="user.role" @change="event => updateRole(user, (event.target as HTMLSelectElement).value)"><option v-for="role in roleChoices(user)" :key="role.value" :value="role.value">{{ role.label }}</option></select></td>
                <td><select :value="user.status" @change="event => updateStatus(user, (event.target as HTMLSelectElement).value)"><option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option></select></td>
                <td>{{ user.timezone }}</td>
                <td>{{ timeLabel(user.updatedAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>
    </section>

    <section v-else-if="tab === 'safety'" class="band">
      <div class="section-head">
        <div>
          <p class="eyebrow">AI 安全</p>
          <h2>风险事件</h2>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>风险</th><th>场景</th><th>方向</th><th>脱敏摘要</th><th>状态</th><th>时间</th></tr></thead>
          <tbody><tr v-for="event in events" :key="event.publicId"><td><span class="risk">{{ event.riskLevel }}</span></td><td>{{ event.scene }}</td><td>{{ event.direction }}</td><td>{{ event.excerpt }}</td><td>{{ event.reviewStatus }}</td><td>{{ timeLabel(event.createdAt) }}</td></tr></tbody>
        </table>
      </div>
    </section>

    <section v-else class="band">
      <div class="section-head">
        <div>
          <p class="eyebrow">不可抵赖记录</p>
          <h2>审计日志</h2>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead><tr><th>动作</th><th>资源</th><th>结果</th><th>请求</th><th>时间</th></tr></thead>
          <tbody><tr v-for="entry in audits" :key="entry.publicId"><td>{{ entry.action }}</td><td>{{ entry.resourceType }}<small>{{ entry.resourcePublicId }}</small></td><td>{{ entry.outcome }}</td><td>{{ entry.requestId }}</td><td>{{ timeLabel(entry.createdAt) }}</td></tr></tbody>
        </table>
      </div>
    </section>
  </main>
</template>

<style scoped>
.admin-page { max-width: 1240px; }
.admin-stack { display: grid; gap: 18px; }
.admin-tabs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border: 1px solid var(--border); border-radius: var(--radius); overflow: hidden; margin-bottom: 20px; padding: 4px; background: var(--surface-muted); }
.admin-tabs button { min-width: 0; border: 0; border-radius: calc(var(--radius) - 2px); background: transparent; color: var(--muted); }
.admin-tabs button[aria-pressed='true'] { background: var(--surface); color: var(--primary); font-weight: 800; box-shadow: var(--shadow-soft); }
.admin-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.admin-metrics article { min-height: 116px; display: grid; grid-template-rows: auto auto 1fr; gap: 8px; padding: 18px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); box-shadow: var(--shadow-soft); }
.admin-metrics svg, .section-head svg { color: var(--primary); }
.admin-metrics span, table small, .overview-grid span { color: var(--muted); font-size: 12px; }
.admin-metrics strong, .overview-grid strong { align-self: end; font-size: 30px; line-height: 1; color: var(--primary); }
.overview-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.overview-grid article { display: grid; gap: 5px; padding: 14px; border: 1px solid var(--border); border-radius: var(--radius); background: color-mix(in srgb, var(--surface) 88%, transparent); }
.section-head { display: flex; align-items: start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.section-head h2 { margin: 0; font-size: 18px; }
.admin-form-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.secret-box { display: flex; align-items: center; gap: 8px; margin: 14px 0 0; padding: 12px; border-left: 3px solid var(--amber); background: color-mix(in srgb, var(--amber) 8%, var(--surface)); color: var(--ink); }
.secret-box code { overflow-wrap: anywhere; }
.table-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); box-shadow: var(--shadow-soft); }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { text-align: left; padding: 11px 12px; border-bottom: 1px solid var(--border); vertical-align: middle; }
th { color: var(--primary-strong); background: var(--surface-muted); font-weight: 800; }
td strong, td small { display: block; }
td select { min-height: 34px; border: 1px solid var(--border); border-radius: var(--radius); background: var(--surface); color: var(--ink); padding: 5px 8px; }
.risk { font-weight: 800; color: var(--danger); }
.spinning { animation: spin .8s linear infinite; }
@media (prefers-reduced-motion: no-preference) {
  .admin-metrics article, .band { animation: admin-enter var(--motion-medium) ease-out both; }
  tr { transition: background-color var(--motion-fast) ease; }
  tbody tr:hover { background: color-mix(in srgb, var(--primary) 5%, transparent); }
}
@keyframes admin-enter { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: translateY(0); } }
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 860px) {
  .admin-metrics, .overview-grid, .admin-form-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .admin-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 560px) {
  .admin-metrics, .overview-grid, .admin-form-grid { grid-template-columns: 1fr; }
}
</style>

const labels: Record<string, string> = {
  PLANNED: '待开始', IN_PROGRESS: '进行中', STARTED: '进行中', DONE: '已完成',
  COMPLETED: '已完成', PARTIAL: '部分完成', DEFERRED: '已延期', SKIPPED: '已跳过',
  EXPIRED: '已过期', CANCELLED: '已取消', ACTIVE: '进行中', PAUSED: '已暂停', ARCHIVED: '已归档',
}
export function taskStatusLabel(status: string) { return labels[status] ?? status }

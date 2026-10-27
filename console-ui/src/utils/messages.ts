import type { MessageLevel } from '@/api/console'

/** Chinese names of message.category (docs/design.md §10.7). */
export const messageCategories: { value: string; label: string }[] = [
  { value: 'RUN_FAILED', label: '离线运行失败' },
  { value: 'VALIDATION', label: '行数校验' },
  { value: 'BACKFILL', label: '补数' },
  { value: 'STREAM_FAILED', label: '实时任务失败' },
  { value: 'STREAM_UNSTABLE', label: '实时任务自动恢复' },
  { value: 'CHECKPOINT', label: 'Checkpoint 异常' },
  { value: 'SCHEMA_CHANGE', label: '表结构变化' },
  { value: 'CLUSTER', label: '集群' },
]

export function categoryText(category: string): string {
  return messageCategories.find((c) => c.value === category)?.label ?? category
}

export const messageLevels: { value: MessageLevel; label: string }[] = [
  { value: 'ERROR', label: '错误' },
  { value: 'WARN', label: '警告' },
  { value: 'INFO', label: '信息' },
]

export function levelText(level: MessageLevel): string {
  return messageLevels.find((l) => l.value === level)?.label ?? level
}

export function levelTag(level: MessageLevel): 'danger' | 'warning' | 'info' {
  return level === 'ERROR' ? 'danger' : level === 'WARN' ? 'warning' : 'info'
}

/** "2026-10-27T10:32:05.123" -> "10:32" today, "10-26 10:32" this year, else the date. */
export function shortTime(value: string): string {
  const now = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  const today = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
  if (value.startsWith(today)) return value.slice(11, 16)
  if (value.startsWith(String(now.getFullYear()))) return value.slice(5, 16).replace('T', ' ')
  return value.slice(0, 10)
}

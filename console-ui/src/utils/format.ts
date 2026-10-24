import type { RunStatus } from '@/api/console'

/** "2026-10-24T00:00:21.081" -> "2026-10-24 00:00:21" */
export function formatTime(value: string | null | undefined): string {
  if (!value) {
    return '-'
  }
  return value.replace('T', ' ').slice(0, 19)
}

export function formatDuration(millis: number | null | undefined): string {
  if (millis === null || millis === undefined) {
    return '-'
  }
  const seconds = Math.round(millis / 1000)
  if (seconds < 60) {
    return `${seconds}秒`
  }
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) {
    return `${minutes}分${seconds % 60}秒`
  }
  return `${Math.floor(minutes / 60)}小时${minutes % 60}分`
}

export function formatNumber(value: number | null | undefined): string {
  return value === null || value === undefined ? '-' : value.toLocaleString('zh-CN')
}

export const statusText: Record<RunStatus, string> = {
  PENDING: '等待中',
  RUNNING: '运行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELED: '已取消',
}

export const statusTag: Record<RunStatus, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  PENDING: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELED: 'warning',
}

export const triggerText: Record<string, string> = {
  SCHEDULE: '定时',
  MANUAL: '手动',
  RERUN: '重跑',
  BACKFILL: '补数',
}

export function isActive(status: RunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

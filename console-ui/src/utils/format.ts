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
  START: '实时任务启动',
}

export function isActive(status: RunStatus): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined) {
    return '-'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit++
  }
  return `${unit === 0 ? value : value.toFixed(1)} ${units[unit]}`
}

/** Rows per second, compact: 0.4, 12, 3.2万. */
export function formatRate(perSecond: number | null | undefined): string {
  if (perSecond === null || perSecond === undefined || !Number.isFinite(perSecond)) {
    return '-'
  }
  if (perSecond >= 10000) {
    return `${(perSecond / 10000).toFixed(1)}万`
  }
  return perSecond >= 10 ? Math.round(perSecond).toLocaleString('zh-CN') : perSecond.toFixed(1)
}

/** Large counts for stat cards: 1,234 / 12.3万 / 1.2亿. */
export function formatCompact(value: number | null | undefined): string {
  if (value === null || value === undefined) {
    return '-'
  }
  if (value >= 1e8) return `${(value / 1e8).toFixed(1)}亿`
  if (value >= 1e5) return `${(value / 1e4).toFixed(1)}万`
  return value.toLocaleString('zh-CN')
}

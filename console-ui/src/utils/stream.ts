import type { StreamState, StreamSummary } from '@/api/console'

/**
 * How a real-time job's state reads (design.md §12 P2). A stopped job whose last start failed is
 * shown as failed, so it stands out until someone starts it again.
 */
export type StreamDisplay = StreamState | 'FAILED'

export const streamStateText: Record<StreamDisplay, string> = {
  STOPPED: '已停止',
  RUNNING: '运行中',
  RECOVERING: '自动恢复中',
  RESTARTING: '等待自动重启',
  STOPPING: '停止中',
  FAILED: '已失败',
}

export const streamStateTag: Record<StreamDisplay, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  STOPPED: 'info',
  RUNNING: 'success',
  RECOVERING: 'warning',
  RESTARTING: 'warning',
  STOPPING: 'primary',
  FAILED: 'danger',
}

export function streamDisplay(s: Pick<StreamSummary, 'state' | 'lastRunStatus'>): StreamDisplay {
  return s.state === 'STOPPED' && s.lastRunStatus === 'FAILED' ? 'FAILED' : s.state
}

/** "3 分钟前" style, for when data was last read or a checkpoint completed. */
export function ago(value: string | null | undefined, now = Date.now()): string {
  if (!value) return '-'
  const seconds = Math.max(0, Math.round((now - new Date(value).getTime()) / 1000))
  if (seconds < 60) return `${seconds} 秒前`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`
  return `${Math.floor(seconds / 86400)} 天前`
}

import { get, LONG_TIMEOUT, post, type PageResult } from './http'
import type { InstanceView } from './instances'

export interface HealthCheck {
  group: string
  name: string
  status: 'OK' | 'WARN' | 'ERROR'
  message: string
}

export interface ClusterHealth {
  checks: HealthCheck[]
  masters: { url: string; reachable: boolean; authRequired: boolean | null; version: string | null; latencyMillis: number; error: string | null }[]
  workers: {
    address: string
    totalHeapBytes: number
    availableHeapBytes: number
    totalSlots: number
    freeSlots: number
    dynamicSlot: boolean
    runningJobs: number
  }[]
  overview: Record<string, string>
  /** null when the console runs without XXL-JOB */
  xxlJob: XxlJobStatus | null
}

/** The scheduler as the console sees it; whether triggers arrive is judged from the runs. */
export interface XxlJobStatus {
  adminAddresses: string[]
  appname: string
  jobGroupId: number
  /** what the executor registers to the admin, where triggers are sent */
  executorAddress: string
  executorPort: number
  executorRunning: boolean
  /** jobs that own an XXL-JOB job */
  boundJobs: number
  enabledSchedules: number
  syncFailed: string[]
  lastStartedRunAt: string | null
  /** runs failed in the last 24 hours because XXL-JOB never started them */
  notStarted24h: number
}

export const clusterApi = {
  overview: (clusterId?: string) => get<Record<string, string>>('/cluster/overview', clusterId ? { clusterId } : undefined),
  health: (clusterId?: string) => get<ClusterHealth>('/cluster/health', clusterId ? { clusterId } : undefined),
  diagnose: (clusterId?: string) =>
    post<HealthCheck[]>(clusterId ? `/cluster/diagnose?clusterId=${encodeURIComponent(clusterId)}` : '/cluster/diagnose', undefined, LONG_TIMEOUT.diagnose),
}

export interface DashboardDay {
  date: string
  success: number
  failed: number
  canceled: number
  running: number
  writeRows: number
}

export interface DashboardJobStat {
  jobId: string
  jobName: string
  runs: number
  writeRows: number
  avgDurationMillis: number | null
}

export interface Dashboard {
  jobs: number
  scheduledJobs: number
  runningRuns: number
  pendingRuns: number
  today: DashboardDay
  /** success ÷ (success + failed) over the last 7 days; null without such runs */
  successRate7d: number | null
  writeRows7d: number
  trend: DashboardDay[]
  active: InstanceView[]
  recentFailures: InstanceView[]
  upcoming: { jobId: string; jobName: string; time: string; cron: string }[]
  topByRows: DashboardJobStat[]
  slowest: DashboardJobStat[]
  /** the engine's overview; empty when the cluster cannot be reached */
  cluster: Record<string, string>
  /** real-time jobs (design.md §12 P2); the numbers above are about offline jobs */
  realtime: { jobs: number; running: number; abnormal: number; quietestReadAt: string | null; quietestJobId: string | null }
}

export const dashboardApi = {
  get: (mine: boolean) => get<Dashboard>('/dashboard', { mine }),
}

export type MessageLevel = 'INFO' | 'WARN' | 'ERROR'

/** In-console messages (docs/design.md §10.7); everyone sees the messages about their own jobs, admins all. */
export interface MessageView {
  id: string
  category: string
  level: MessageLevel
  title: string
  content: string
  jobId: string | null
  instanceId: string | null
  link: string | null
  /** occurrences merged into this message while it stayed unresolved */
  repeatCount: number
  createdAt: string
  lastAt: string
  /** whether the message is about a problem that gets resolved */
  resolvable: boolean
  resolvedAt: string | null
  read: boolean
}

export interface MessageFilters {
  unread?: boolean
  unresolved?: boolean
  level?: string
  category?: string
  page?: number
  size?: number
}

export const messageApi = {
  list: (params: MessageFilters) => get<PageResult<MessageView>>('/messages', params as Record<string, unknown>),
  unreadCount: () => get<number>('/messages/unread-count', undefined, true),
  markRead: (id: string) => post<void>(`/messages/${id}/read`),
  markAllRead: () => post<void>('/messages/read-all'),
}

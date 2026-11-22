import type { DatasourceType } from './datasources'
import { get, post, type PageResult } from './http'
import type { ScheduleView, StreamSettings } from './jobs'

export type RunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED'

export interface InstanceView {
  id: string
  projectId: string
  projectName: string | null
  jobDefinitionId: string
  jobName: string | null
  jobDeleted: boolean
  versionNo: number | null
  triggerType: 'SCHEDULE' | 'MANUAL' | 'RERUN' | 'BACKFILL' | 'START'
  status: RunStatus
  attemptCount: number
  scheduledTime: string
  /** sync window the run read; null start = from the beginning, null end = the run reads no window */
  windowStart: string | null
  windowEnd: string | null
  startedAt: string | null
  finishedAt: string | null
  durationMillis: number | null
  readRows: number | null
  writeRows: number | null
  errorMessage: string | null
  rerunOfInstanceId: string | null
  backfillBatchId: string | null
  validationStatus: 'PASSED' | 'MISMATCH' | 'ERROR' | 'SKIPPED' | null
  sourceCount: number | null
  targetCount: number | null
  validationMessage: string | null
  createdByName: string | null
  editable: boolean
  createdAt: string
}

export interface AttemptView {
  attemptNo: number
  engineJobId: string | null
  engineStatus: string | null
  errorMessage: string | null
  logArchived: boolean
  logSizeBytes: number | null
  logTruncated: boolean
  submittedAt: string | null
  finishedAt: string | null
}

export interface EventView {
  attemptNo: number | null
  type: string
  level: 'INFO' | 'WARN' | 'ERROR'
  message: string
  time: string
}

export interface InstanceDetail {
  instance: InstanceView
  configMasked: string | null
  variablesJson: string | null
  startupParamsJson: string | null
  attempts: AttemptView[]
  events: EventView[]
}

export interface LogView {
  source: 'LIVE' | 'ARCHIVED' | 'UNAVAILABLE'
  content: string
  truncated: boolean
  message: string | null
}

export type InstanceListFilters = {
  projectId?: string
  jobId?: string
  jobName?: string
  status?: string
  backfillId?: string
  startedFrom?: string
  startedTo?: string
  mine?: boolean
}

export interface InstanceFeed {
  items: InstanceView[]
  hasMore: boolean
  nextCursorTime: string | null
  nextCursorId: string | null
}

export const instanceApi = {
  list: (params: InstanceListFilters & { page?: number; size?: number }) => get<PageResult<InstanceView>>('/instances', params),
  feed: (params: InstanceListFilters & { cursorTime?: string; cursorId?: string; size?: number }) =>
    get<InstanceFeed>('/instances/feed', params),
  count: (params: InstanceListFilters) => get<number>('/instances/count', params),
  rerun: (id: string, useLatestVersion: boolean) => post<{ instanceId: string }>(`/instances/${id}/rerun`, { useLatestVersion }),
  detail: (id: string) => get<InstanceDetail>(`/instances/${id}`),
  log: (id: string, attemptNo: number) => get<LogView>(`/instances/${id}/attempts/${attemptNo}/log`),
  stop: (id: string) => post<void>(`/instances/${id}/stop`),
  metrics: (id: string, attemptNo?: number) => get<RunMetrics>(`/instances/${id}/metrics`, { attemptNo }),
}

/** One plugin's view of a table; SeaTunnel names tables database.table (database.schema.table for PostgreSQL). */
export interface TableMetric {
  side: 'SOURCE' | 'SINK'
  vertex: string
  table: string
  rows: number | null
  bytes: number | null
  qps: number | null
}

/** Engine job metrics; QPS values are the engine's averages over the job's lifetime. */
export interface EngineMetrics {
  readRows: number | null
  writeRows: number | null
  readBytes: number | null
  writeBytes: number | null
  readQps: number | null
  writeQps: number | null
  tables: TableMetric[]
}

export interface MetricSample {
  time: string
  readRows: number | null
  writeRows: number | null
  readBytes: number | null
  writeBytes: number | null
}

export interface RunMetrics {
  attemptNo: number | null
  metrics: EngineMetrics
  samples: MetricSample[]
}

export interface RunPoint {
  id: string
  status: RunStatus
  triggerType: InstanceView['triggerType']
  scheduledTime: string
  startedAt: string | null
  durationMillis: number | null
  readRows: number | null
  writeRows: number | null
}

export interface FlowEndpoint {
  connector: string | null
  datasourceId: string | null
  datasourceName: string | null
  datasourceType: DatasourceType | null
  /** Database / schema, endpoint path, index, or a short form of the query. */
  location: string | null
  /** One more line, e.g. "8 张表" or "目标不存在时自动创建". */
  note: string | null
}

/** How a wizard job writes and checks, with what the source can do already applied. */
export interface FlowSettings {
  mode: 'FULL' | 'INCREMENTAL' | null
  window: 'DAY' | 'HOUR' | 'CUSTOM' | null
  incrementalField: string | null
  writeMode: 'UPSERT' | 'APPEND'
  dataMode: 'KEEP' | 'CLEAR' | null
  schemaMode: 'CREATE' | 'ERROR' | null
  parallelism: number
  validation: boolean
  /** a real-time job's settings, in place of the mode (design.md §12 P2) */
  stream: StreamSettings | null
}

/** What the current version syncs (JobOverviewService.Flow). */
export interface JobFlow {
  sources: FlowEndpoint[]
  sinks: FlowEndpoint[]
  /** What the wizard does to the data, summed over tables, e.g. "改名 3 个字段"; empty when nothing. */
  processing: string[]
  /** Transform plugins of a script job, in order. */
  transforms: string[]
  /** Null for script jobs. */
  settings: FlowSettings | null
  /** Source → target of each table, for wizard jobs; notes like "过滤 status = 1". */
  tables: { source: string; target: string; notes: string[] }[]
}

export interface JobOverview {
  /** Runs finished in the last 7 days. */
  stats: { runs: number; success: number; failed: number; canceled: number; avgDurationMillis: number | null; writeRows: number }
  lastRun: InstanceView | null
  active: InstanceView[]
  trend: RunPoint[]
  metricsInstanceId: string | null
  metrics: EngineMetrics
  flow: JobFlow
  schedule: ScheduleView | null
}

export type BackfillStatus = 'RUNNING' | 'PAUSED' | 'FINISHED' | 'CANCELED'

export interface BackfillPlan {
  totalRuns: number
  cron: string | null
  firstTime: string
  lastTime: string
}

export interface BackfillView {
  id: string
  jobDefinitionId: string
  versionNo: number | null
  startDate: string
  endDate: string
  cron: string | null
  startupParams: Record<string, string>
  totalRuns: number
  triggered: number
  statusCounts: Partial<Record<RunStatus, number>>
  status: BackfillStatus
  errorMessage: string | null
  createdAt: string
  finishedAt: string | null
}

export const backfillApi = {
  plan: (jobId: string, startDate: string, endDate: string) =>
    get<BackfillPlan>(`/jobs/${jobId}/backfills/plan`, { startDate, endDate }, true),
  create: (jobId: string, body: { startDate: string; endDate: string; params?: Record<string, string> }) =>
    post<BackfillView>(`/jobs/${jobId}/backfills`, body),
  list: (jobId: string) => get<BackfillView[]>(`/jobs/${jobId}/backfills`),
  cancel: (id: string) => post<BackfillView>(`/backfills/${id}/cancel`),
  resume: (id: string) => post<BackfillView>(`/backfills/${id}/resume`),
}

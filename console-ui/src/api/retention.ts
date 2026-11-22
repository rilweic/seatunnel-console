import { get, post, put } from './http'

export interface RetentionPolicyView {
  enabled: boolean
  runHour: number
  metricRetentionDays: number
  instanceRetentionDays: number
  logRetentionDays: number
  messageRetentionDays: number
  schedulerMode: 'XXL_JOB' | 'LOCAL'
  xxlJobId: number | null
  syncStatus: 'SYNCED' | 'FAILED' | 'LOCAL' | null
  syncError: string | null
  updatedAt: string | null
}

export interface UpdateRetentionPolicyRequest {
  enabled: boolean
  runHour: number
  metricRetentionDays: number
  instanceRetentionDays: number
  logRetentionDays: number
  messageRetentionDays: number
}

export interface StorageOverview {
  metricSamplesTotal: number
  metricSamplesExpired: number
  instancesTotal: number
  instancesExpired: number
  attemptsTotal: number
  eventsTotal: number
  backfillsTotal: number
  backfillsExpired: number
  logFilesTotal: number
  logBytesTotal: number
  messagesTotal: number
  messagesExpired: number
  running: boolean
}

export interface CleanupLogView {
  id: string
  triggerType: 'SCHEDULED' | 'MANUAL'
  triggeredByName: string | null
  status: 'SUCCESS' | 'FAILED'
  deletedMetricSamples: number
  deletedInstances: number
  deletedAttempts: number
  deletedEvents: number
  deletedBackfills: number
  deletedLogFiles: number
  deletedLogBytes: number
  deletedMessages: number
  errorMessage: string | null
  startedAt: string
  finishedAt: string
  durationMs: number
}

export const retentionApi = {
  getPolicy: () => get<RetentionPolicyView>('/retention/policy'),
  updatePolicy: (body: UpdateRetentionPolicyRequest) => put<RetentionPolicyView>('/retention/policy', body),
  getStats: () => get<StorageOverview>('/retention/stats'),
  listHistory: (limit = 20) => get<CleanupLogView[]>(`/retention/history?limit=${limit}`),
  runNow: () => post<CleanupLogView>('/retention/run'),
}

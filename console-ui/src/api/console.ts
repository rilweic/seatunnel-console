import { del, get, post, put, type PageResult } from './http'

export interface UserView {
  id: string
  username: string
  displayName: string | null
  role: 'ADMIN' | 'USER'
  mustChangePassword: boolean
}

export const authApi = {
  login: (username: string, password: string) => post<UserView>('/auth/login', { username, password }),
  me: () => get<UserView>('/auth/me', undefined, true),
  logout: () => post<void>('/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string) => put<UserView>('/auth/password', { oldPassword, newPassword }),
}

export interface AccountView {
  id: string
  username: string
  displayName: string | null
  role: 'ADMIN' | 'USER'
  enabled: boolean
  mustChangePassword: boolean
  lastLoginAt: string | null
  createdAt: string
  projects: number
  datasources: number
  jobs: number
}

export const userApi = {
  list: () => get<AccountView[]>('/users'),
  create: (body: { username: string; displayName?: string; role: string; password: string }) => post<AccountView>('/users', body),
  update: (id: string, body: { displayName?: string | null; role: string; enabled: boolean }) => put<AccountView>(`/users/${id}`, body),
  resetPassword: (id: string, password: string) => post<AccountView>(`/users/${id}/reset-password`, { password }),
  transfer: (id: string, toUserId: string) =>
    post<{ projects: number; datasources: number; jobs: number }>(`/users/${id}/transfer`, { toUserId }),
}

/** Owner fields every owned resource carries; editable is computed for the current user. */
export interface Owned {
  ownerId: string | null
  ownerName: string | null
  editable: boolean
}

export interface ProjectView extends Owned {
  id: string
  name: string
  description: string | null
  jobCount: number
  createdAt: string
}

export interface ParamItem {
  key: string
  value: string
  description?: string | null
}

export const projectApi = {
  list: (params: { keyword?: string; mine?: boolean; page?: number; size?: number }) => get<PageResult<ProjectView>>('/projects', params),
  create: (body: { name: string; description?: string }) => post<ProjectView>('/projects', body),
  update: (id: string, body: { name: string; description?: string }) => put<ProjectView>(`/projects/${id}`, body),
  remove: (id: string) => del<void>(`/projects/${id}`),
  params: (id: string) => get<ParamItem[]>(`/projects/${id}/params`),
  saveParams: (id: string, items: ParamItem[]) => put<ParamItem[]>(`/projects/${id}/params`, items),
}

export type DatasourceType = 'MYSQL' | 'POSTGRESQL' | 'ELASTICSEARCH'

export interface DatasourceView extends Owned {
  id: string
  name: string
  type: DatasourceType
  description: string | null
  config: Record<string, string>
  secretsSet: string[]
  createdAt: string
  updatedAt: string
}

export interface ConnectionTestResult {
  success: boolean
  message: string
  elapsedMillis: number
}

export interface TableMeta {
  name: string
  primaryKeys: string[]
}

export interface ColumnMeta {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  temporal: boolean
}

export const datasourceApi = {
  namespaces: (id: string) => get<string[]>(`/datasources/${id}/namespaces`),
  tables: (id: string, namespace?: string) => get<TableMeta[]>(`/datasources/${id}/tables`, { namespace }),
  columns: (id: string, namespace: string | undefined, table: string) =>
    get<ColumnMeta[]>(`/datasources/${id}/columns`, { namespace, table }),
  list: (params: { keyword?: string; type?: string; ownerId?: string | null; page?: number; size?: number }) =>
    get<PageResult<DatasourceView>>('/datasources', params),
  create: (body: { name: string; type: DatasourceType; description?: string; props: Record<string, string> }) =>
    post<DatasourceView>('/datasources', body),
  update: (id: string, body: { name: string; description?: string; props: Record<string, string> }) =>
    put<DatasourceView>(`/datasources/${id}`, body),
  remove: (id: string) => del<void>(`/datasources/${id}`),
  test: (body: { datasourceId?: string; type: DatasourceType; props: Record<string, string> }) =>
    post<ConnectionTestResult>('/datasources/test', body),
}

export interface JobView extends Owned {
  id: string
  projectId: string
  projectName: string | null
  name: string
  jobType: 'BATCH' | 'STREAMING'
  editMode: 'FORM' | 'SCRIPT'
  currentVersionNo: number | null
  description: string | null
  updatedAt: string
}

export interface WizardTable {
  name: string
  columns?: string[] | null
  filter?: string | null
  incrementalField?: string | null
}

export interface WizardForm {
  source: { datasourceId: string; namespace: string; tables: WizardTable[] }
  target: {
    datasourceId: string
    namespace?: string | null
    tableName?: string | null
    prefix?: string | null
    suffix?: string | null
    schemaMode: 'CREATE' | 'ERROR'
    dataMode: 'KEEP' | 'CLEAR'
    writeMode: 'UPSERT' | 'APPEND'
  }
  sync: { mode: 'FULL' | 'INCREMENTAL'; window?: 'DAY' | 'HOUR' | 'CUSTOM' | null; field?: string | null; customCondition?: string | null }
  parallelism: number
  validation: boolean
  sourceOptions?: Record<string, string>
  sinkOptions?: Record<string, string>
}

export interface PrecheckResult {
  checks: { level: 'ERROR' | 'WARN' | 'INFO'; message: string }[]
  generated: { hocon: string; validation: ValidationSpec | null; notes: string[] } | null
}

export interface ConnectorOption {
  key: string
  type: string | null
  defaultValue: string | null
  description: string | null
  required: boolean
}

export const connectorApi = {
  options: (type: 'source' | 'sink', plugin: string) => get<ConnectorOption[]>(`/connectors/${type}/${plugin}/options`),
}

export interface VersionSummary {
  id: string
  versionNo: number
  remark: string | null
  createdBy: string | null
  createdByName: string | null
  createdAt: string
}

export type ValidationMode = 'AUTO' | 'SQL' | 'ES'

export interface ValidationSide {
  mode: ValidationMode
  datasourceId?: string | null
  sql?: string | null
  index?: string | null
  query?: string | null
}

export interface ValidationSpec {
  enabled: boolean
  source: ValidationSide
  target: ValidationSide
  failOnMismatch: boolean
  tables?: { name: string; source: ValidationSide; target: ValidationSide }[] | null
}

export interface ValidationResult {
  status: 'PASSED' | 'MISMATCH' | 'ERROR'
  sourceCount: number | null
  targetCount: number | null
  message: string
  sourceQuery: string | null
  targetQuery: string | null
}

export interface VersionDetail {
  id: string
  jobDefinitionId: string
  versionNo: number
  hocon: string
  params: Record<string, string>
  datasourceIds: string[]
  validation: ValidationSpec | null
  form: WizardForm | null
  remark: string | null
  createdAt: string
}

export const jobApi = {
  list: (params: { projectId?: string; keyword?: string; mine?: boolean; page?: number; size?: number }) =>
    get<PageResult<JobView>>('/jobs', params),
  get: (id: string) => get<{ job: JobView; currentVersion: VersionDetail | null }>(`/jobs/${id}`),
  create: (body: { projectId: string; name: string; jobType: 'BATCH'; editMode: 'FORM' | 'SCRIPT'; description?: string }) =>
    post<JobView>('/jobs', body),
  update: (id: string, body: { name: string; description?: string }) => put<JobView>(`/jobs/${id}`, body),
  remove: (id: string) => del<void>(`/jobs/${id}`),
  saveVersion: (
    id: string,
    body: {
      hocon?: string
      remark?: string
      params?: Record<string, string>
      validation?: ValidationSpec | null
      form?: WizardForm
    },
  ) => post<VersionDetail>(`/jobs/${id}/versions`, body),
  wizardPrecheck: (id: string, body: { form: WizardForm; params?: Record<string, string> }) =>
    post<PrecheckResult>(`/jobs/${id}/wizard/precheck`, body),
  switchToScript: (id: string) => post<JobView>(`/jobs/${id}/switch-to-script`),
  testValidation: (
    id: string,
    body: { hocon: string; params?: Record<string, string>; validation: ValidationSpec; scheduledTime?: string },
  ) => post<{ result: ValidationResult; variables: Record<string, string> }>(`/jobs/${id}/validation/test`, body),
  versions: (id: string) => get<VersionSummary[]>(`/jobs/${id}/versions`),
  version: (id: string, versionId: string) => get<VersionDetail>(`/jobs/${id}/versions/${versionId}`),
  restoreVersion: (id: string, versionId: string) => post<VersionDetail>(`/jobs/${id}/versions/${versionId}/restore`),
  run: (id: string, body: { scheduledTime?: string; params?: Record<string, string> } = {}) =>
    post<{ instanceId: string }>(`/jobs/${id}/run`, body),
  preview: (
    id: string,
    body: { hocon: string; params?: Record<string, string>; startupParams?: Record<string, string>; scheduledTime?: string },
  ) => post<{ variables: Record<string, string>; config: string; unresolved: string[] }>(`/jobs/${id}/preview`, body),
}

export type BlockStrategy = 'SERIAL_EXECUTION' | 'DISCARD_LATER' | 'COVER_EARLY'
export type MisfireStrategy = 'DO_NOTHING' | 'FIRE_ONCE_NOW'

export interface ScheduleForm {
  cron: string
  enabled: boolean
  failRetryCount: number
  retryIntervalSeconds: number
  timeoutSeconds: number
  blockStrategy: BlockStrategy
  misfireStrategy: MisfireStrategy
}

export interface ScheduleView extends ScheduleForm {
  jobDefinitionId: string
  xxlJobId: number | null
  syncStatus: 'PENDING' | 'SYNCED' | 'FAILED'
  syncError: string | null
  syncedAt: string | null
  nextFireTimes: string[]
}

export const scheduleApi = {
  get: (jobId: string) => get<ScheduleView | null>(`/jobs/${jobId}/schedule`),
  save: (jobId: string, body: ScheduleForm) => put<ScheduleView>(`/jobs/${jobId}/schedule`, body),
  preview: (cron: string) => get<string[]>('/schedules/cron-preview', { cron }, true),
}

export type RunStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED'

export interface InstanceView {
  id: string
  projectId: string
  jobDefinitionId: string
  jobName: string | null
  versionNo: number | null
  triggerType: 'SCHEDULE' | 'MANUAL' | 'RERUN' | 'BACKFILL'
  status: RunStatus
  attemptCount: number
  scheduledTime: string
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

export const instanceApi = {
  list: (params: { projectId?: string; jobId?: string; status?: string; backfillId?: string; mine?: boolean; page?: number; size?: number }) =>
    get<PageResult<InstanceView>>('/instances', params),
  rerun: (id: string, useLatestVersion: boolean) => post<{ instanceId: string }>(`/instances/${id}/rerun`, { useLatestVersion }),
  detail: (id: string) => get<InstanceDetail>(`/instances/${id}`),
  log: (id: string, attemptNo: number) => get<LogView>(`/instances/${id}/attempts/${attemptNo}/log`),
  stop: (id: string) => post<void>(`/instances/${id}/stop`),
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
}

export const clusterApi = {
  overview: () => get<Record<string, string>>('/cluster/overview'),
  health: () => get<ClusterHealth>('/cluster/health'),
  diagnose: () => post<HealthCheck[]>('/cluster/diagnose'),
}

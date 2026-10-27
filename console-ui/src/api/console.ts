import { del, get, LONG_TIMEOUT, post, put, type PageResult } from './http'

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

export interface EncryptKeyView {
  id: string
  name: string
  description: string | null
  algorithm: 'AES_GCM' | 'AES_CBC'
  keyBits: number
  enabled: boolean
  createdByName: string | null
  createdAt: string
}

/** Field encryption keys (docs/design.md §7.11); the key itself never comes back. */
export const encryptKeyApi = {
  list: () => get<EncryptKeyView[]>('/encrypt-keys'),
  create: (body: { name: string; description?: string; algorithm: string; bits?: number; importedKey?: string }) =>
    post<EncryptKeyView>('/encrypt-keys', body),
  update: (id: string, body: { name: string; description?: string | null; enabled: boolean }) => put<EncryptKeyView>(`/encrypt-keys/${id}`, body),
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

/** A datasource type name from the server, e.g. MYSQL; the list comes from {@link datasourceApi.types}. */
export type DatasourceType = string

/** What a datasource type can do (DataSourceCapabilities on the server, design.md §6.1). */
export interface DatasourceCapabilities {
  label: string
  /** SeaTunnel source connector the wizard generates; null when the wizard cannot read from it. */
  sourceConnector: string | null
  /** SeaTunnel sink connector the wizard generates; null when the wizard cannot write to it. */
  sinkConnector: string | null
  /** Jobs pick a database or schema first. */
  namespaces: boolean
  multiTable: boolean
  /** How rows are counted for validation; null when they cannot be. */
  countBy: 'SQL' | 'INDEX' | null
  maxParallelism: number
  lowerCaseNames: boolean
  jdbcDriver: boolean
  /** SeaTunnel CDC source real-time jobs read it with, e.g. MySQL-CDC; null when it cannot be a real-time source. */
  cdcConnector: string | null
}

/** One condition for real-time (CDC) reads, from {@link datasourceApi.cdcCheck} (design.md §12 P2). */
export interface CdcCheck {
  item: string
  level: 'OK' | 'WARN' | 'ERROR'
  actual: string | null
  message: string
  /** SQL or a setting to apply by hand; null when the condition holds. */
  fix: string | null
}

export interface DatasourceTypeView {
  type: DatasourceType
  capabilities: DatasourceCapabilities
}

export interface DatasourceView extends Owned {
  id: string
  name: string
  type: DatasourceType
  description: string | null
  /** Where it points, e.g. db1:3306/shop, worked out by the server per type. */
  address: string
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
  types: () => get<DatasourceTypeView[]>('/datasources/types'),
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
  cdcCheck: (id: string) => post<CdcCheck[]>(`/datasources/${id}/cdc-check`, undefined, LONG_TIMEOUT.check),
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
  /** How the job is running; null where the server does not compute it. */
  activity: JobActivity | null
}

export interface JobActivity {
  lastRunId: string | null
  lastRunStatus: RunStatus | null
  lastRunAt: string | null
  scheduleEnabled: boolean
  cron: string | null
}

export type NameCase = 'KEEP' | 'UPPER' | 'LOWER'

/** Derives a target name: replace (plain text), change case, then add prefix and suffix. */
export interface NameRule {
  find?: string | null
  replace?: string | null
  nameCase?: NameCase | null
  prefix?: string | null
  suffix?: string | null
}

export interface WizardTable {
  name: string
  columns?: string[] | null
  filter?: string | null
  incrementalField?: string | null
  /** target table or index name set by hand; overrides the table rule */
  targetName?: string | null
  /** target field names set by hand, by source field */
  fieldNames?: Record<string, string> | null
  /** this table's field rule; null uses the form's */
  fieldRule?: NameRule | null
  /** value processing of synced fields, by source field */
  processes?: Record<string, FieldProcess> | null
  extracts?: Extract[] | null
  addedFields?: AddedField[] | null
  /** console key the encrypted fields of this table use */
  encryptKeyId?: string | null
  /** the source columns as the wizard showed them at the last save; marks what changed since */
  seenColumns?: SeenColumn[] | null
}

export interface SeenColumn {
  name: string
  type: string
}

export type FieldType = 'STRING' | 'BIGINT' | 'DECIMAL' | 'DATE' | 'TIMESTAMP' | 'BOOLEAN'
export type MaskPreset = 'PHONE' | 'ID_CARD' | 'EMAIL' | 'NAME' | 'BANK_CARD' | 'CUSTOM'

/** Applied in this order: replace, mask, convert; then encrypt. */
export interface FieldProcess {
  replace?: { find: string; replacement?: string | null; regex: boolean } | null
  mask?: { preset: MaskPreset; keepFirst?: number | null; keepLast?: number | null; maskChar?: string | null } | null
  cast?: { type: FieldType; precision?: number | null; scale?: number | null } | null
  encrypt?: boolean | null
}

export interface ExtractOutput {
  name: string
  /** JSON only */
  path?: string | null
  type?: FieldType | null
  precision?: number | null
  scale?: number | null
}

export interface Extract {
  source: string
  kind: 'JSON' | 'REGEX' | 'SPLIT'
  /** regex or separator */
  pattern?: string | null
  outputs: ExtractOutput[]
  dropSource: boolean
}

export type AddKind = 'CONSTANT' | 'CONCAT' | 'SUBSTRING' | 'CASE' | 'DATE_FORMAT' | 'ARITHMETIC' | 'DEFAULT' | 'UUID' | 'NOW' | 'EXPRESSION'

export interface AddedField {
  name: string
  kind: AddKind
  fields?: string[] | null
  value?: string | null
  op?: string | null
  start?: number | null
  length?: number | null
  format?: string | null
  type?: FieldType | null
  precision?: number | null
  scale?: number | null
}

export interface HttpSourceField {
  name: string
  type: string
  jsonPath?: string | null
  precision?: number | null
  scale?: number | null
}

export interface HttpSourceSpec {
  path: string
  method: 'GET' | 'POST'
  params?: Record<string, string>
  body?: string | null
  paging?: {
    mode: 'NONE' | 'PAGE_NUMBER' | 'CURSOR'
    pageField?: string | null
    pageLocation?: 'PARAMS' | 'BODY' | null
    startPageNumber?: number | null
    pageSizeField?: string | null
    pageSize?: number | null
    totalPageSize?: number | null
    cursorField?: string | null
    cursorResponseField?: string | null
  } | null
  contentField?: string | null
  fields: HttpSourceField[]
  primaryKeys?: string[] | null
}

export interface HttpPreviewResult {
  statusCode: number
  elapsedMillis: number
  body: string
}

export interface WizardForm {
  source: { datasourceId: string; namespace: string; tables: WizardTable[]; spec?: HttpSourceSpec | null }
  target: {
    datasourceId: string
    namespace?: string | null
    tableName?: string | null
    prefix?: string | null
    suffix?: string | null
    schemaMode: 'CREATE' | 'ERROR'
    /** ignored for a real-time job, which always keeps the target's rows and writes by key */
    dataMode: 'KEEP' | 'CLEAR'
    writeMode: 'UPSERT' | 'APPEND'
    tableRule?: NameRule | null
  }
  /** ignored for a real-time job, which has {@link stream} instead */
  sync: { mode: 'FULL' | 'INCREMENTAL'; window?: IncrementalWindow | null; field?: string | null; customCondition?: string | null }
  parallelism: number
  validation: boolean
  sourceOptions?: Record<string, string>
  sinkOptions?: Record<string, string>
  fieldRule?: NameRule | null
  /** Present exactly for a real-time job (design.md §12 P2). */
  stream?: StreamSettings | null
}

/** How a real-time job reads and applies changes (WizardForm.Stream on the server). */
export interface StreamSettings {
  startup: 'INITIAL' | 'LATEST' | 'TIMESTAMP'
  /** epoch millis, for TIMESTAMP */
  startupTimestamp?: number | null
  schemaChanges: boolean
  dropColumns: boolean
  skipDeletes: boolean
  checkpointSeconds?: number | null
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
  options: (type: 'source' | 'sink' | 'transform', plugin: string) => get<ConnectorOption[]>(`/connectors/${type}/${plugin}/options`),
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

/** CONTINUOUS carries on from the job's watermark; DAY and HOUR are the day or hour before the scheduled time (design.md §7.5). */
export type IncrementalWindow = 'CONTINUOUS' | 'DAY' | 'HOUR' | 'CUSTOM'

/** Continuous incremental state of a job; null start / watermark = from the beginning. */
export interface IncrementalView {
  /** the wizard's window of the current version; null for full sync or a script */
  window: IncrementalWindow | null
  /** the current version reads ${system.window.start} / ${system.window.end} */
  usesWindow: boolean
  watermark: string | null
  lagSeconds: number
  nextStart: string | null
  nextEnd: string
}

export const jobApi = {
  list: (params: { projectId?: string; keyword?: string; mine?: boolean; jobType?: JobView['jobType']; page?: number; size?: number }) =>
    get<PageResult<JobView>>('/jobs', params),
  get: (id: string) => get<{ job: JobView; currentVersion: VersionDetail | null }>(`/jobs/${id}`),
  create: (body: { projectId: string; name: string; jobType: JobView['jobType']; editMode: 'FORM' | 'SCRIPT'; description?: string }) =>
    post<JobView>('/jobs', body),
  update: (id: string, body: { name: string; description?: string | null; projectId?: string }) => put<JobView>(`/jobs/${id}`, body),
  copy: (id: string, body: { name: string; projectId: string }) =>
    post<{ job: JobView; scheduleCopied: boolean }>(`/jobs/${id}/copy`, body),
  overview: (id: string) => get<JobOverview>(`/jobs/${id}/overview`),
  remove: (id: string, deleteRuns = true) => del<void>(`/jobs/${id}?deleteRuns=${deleteRuns}`),
  saveVersion: (
    id: string,
    body: {
      hocon?: string
      remark?: string
      params?: Record<string, string>
      validation?: ValidationSpec | null
      form?: WizardForm
    },
  ) => post<VersionDetail>(`/jobs/${id}/versions`, body, LONG_TIMEOUT.check),
  wizardPrecheck: (id: string, body: { form: WizardForm; params?: Record<string, string> }) =>
    post<PrecheckResult>(`/jobs/${id}/wizard/precheck`, body, LONG_TIMEOUT.check),
  httpPreview: (id: string, body: { form: WizardForm; params?: Record<string, string> }) =>
    post<HttpPreviewResult>(`/jobs/${id}/wizard/http-preview`, body, LONG_TIMEOUT.check),
  switchToScript: (id: string) => post<JobView>(`/jobs/${id}/switch-to-script`),
  checkScript: (id: string, body: { hocon: string; params?: Record<string, string> }) =>
    post<PrecheckResult['checks']>(`/jobs/${id}/check`, body, LONG_TIMEOUT.check),
  testValidation: (
    id: string,
    body: { hocon: string; params?: Record<string, string>; validation: ValidationSpec; scheduledTime?: string },
  ) => post<{ result: ValidationResult; variables: Record<string, string> }>(`/jobs/${id}/validation/test`, body, LONG_TIMEOUT.validation),
  versions: (id: string) => get<VersionSummary[]>(`/jobs/${id}/versions`),
  version: (id: string, versionId: string) => get<VersionDetail>(`/jobs/${id}/versions/${versionId}`),
  restoreVersion: (id: string, versionId: string) => post<VersionDetail>(`/jobs/${id}/versions/${versionId}/restore`),
  run: (id: string, body: { scheduledTime?: string; params?: Record<string, string> } = {}) =>
    post<{ instanceId: string }>(`/jobs/${id}/run`, body),
  preview: (
    id: string,
    body: { hocon: string; params?: Record<string, string>; startupParams?: Record<string, string>; scheduledTime?: string },
  ) => post<{ variables: Record<string, string>; config: string; unresolved: string[] }>(`/jobs/${id}/preview`, body),
  renderText: (id: string, body: { text: string; params?: Record<string, string>; scheduledTime?: string }) =>
    post<{ text: string | null; unresolved: string[]; error: string | null }>(`/jobs/${id}/render-text`, body),
  incremental: (id: string) => get<IncrementalView>(`/jobs/${id}/incremental`),
  updateIncremental: (id: string, body: { watermark: string | null; lagSeconds: number }) =>
    put<IncrementalView>(`/jobs/${id}/incremental`, body),
}

/** A real-time job's state (StreamService on the server, design.md §12 P2). */
export type StreamState = 'STOPPED' | 'RUNNING' | 'RECOVERING' | 'RESTARTING' | 'STOPPING'

export interface StreamRestartSettings {
  autoRestart: boolean
  maxRestarts: number
  restartIntervalSeconds: number
}

export interface StreamView {
  jobId: string
  state: StreamState
  currentInstanceId: string | null
  runningVersionId: string | null
  /** the running version is not the job's current one */
  newVersionPending: boolean
  attemptNo: number | null
  engineJobId: string | null
  stopRequested: 'SAVEPOINT' | 'FORCE' | null
  stopRequestedAt: string | null
  restartAt: string | null
  startedAt: string | null
  lastReadAt: string | null
  checkpointAt: string | null
  checkpointProblem: string | null
  pipelineRestores: number
  restoreKind: 'SAVEPOINT' | 'CHECKPOINT' | null
  restoreEngineJobId: string | null
  restoreBlocked: string | null
  /** the current version reads or writes differently from the restore point's version */
  sourceChanged: boolean
  settings: StreamRestartSettings
  /** the PostgreSQL slot a stopped job keeps, with the WAL it holds */
  replication: { name: string; active: boolean; retainedBytes: number } | null
  lastRunId: string | null
  lastRunStatus: RunStatus | null
  lastRunMessage: string | null
  /** table-structure changes noticed while running, newest first; WARN when the target did not follow */
  schemaChanges: { time: string; level: 'INFO' | 'WARN' | 'ERROR'; message: string }[]
  /** per table, its column changes since the latest start; tables without changes are left out */
  tableChanges: TableSchemaChanges[]
}

export interface TableSchemaChanges {
  /** source table, as in the job's table mapping */
  table: string
  targetName: string
  /** when the source table was last read */
  checkedAt: string
  /** status: what happened on the target or why it did not follow; problem: someone has to act */
  changes: { change: string; status: string | null; problem: boolean }[]
}

export interface StreamSummary {
  state: StreamState
  startedAt: string | null
  lastReadAt: string | null
  checkpointProblem: string | null
  lastRunStatus: RunStatus | null
  lastRunMessage: string | null
}

export type StreamRestore = 'AUTO' | 'SAVEPOINT' | 'CHECKPOINT' | 'FRESH'

export const streamApi = {
  view: (jobId: string) => get<StreamView>(`/streams/${jobId}`),
  summaries: (jobIds: string[]) => get<Record<string, StreamSummary>>('/streams', { jobIds: jobIds.join(',') }),
  start: (jobId: string, body: { restore: StreamRestore; startupParams?: Record<string, string> }) =>
    post<StreamView>(`/streams/${jobId}/start`, body, LONG_TIMEOUT.check),
  stop: (jobId: string, force = false) => post<StreamView>(`/streams/${jobId}/stop`, { force }),
  settings: (jobId: string, body: StreamRestartSettings) => put<StreamView>(`/streams/${jobId}/settings`, body),
  /** @returns SQL left to run by hand when the slot or publication could not be dropped */
  remove: (jobId: string, deleteRuns: boolean, dropReplication: boolean) =>
    del<string[]>(`/streams/${jobId}?deleteRuns=${deleteRuns}&dropReplication=${dropReplication}`),
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

/** Zeta job metrics; QPS values are the engine's averages over the job's lifetime. */
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
  diagnose: () => post<HealthCheck[]>('/cluster/diagnose', undefined, LONG_TIMEOUT.diagnose),
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

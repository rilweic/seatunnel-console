import { del, get, LONG_TIMEOUT, post, put, type PageResult } from './http'
import type { JobOverview, RunStatus } from './instances'
import type { Owned } from './projects'

export interface JobView extends Owned {
  id: string
  projectId: string
  projectName: string | null
  clusterId: string | null
  clusterName: string | null
  engineType: 'ZETA' | 'FLINK' | string
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
  options: (type: 'source' | 'sink' | 'transform', plugin: string, clusterId?: string | null) =>
    get<ConnectorOption[]>(`/connectors/${type}/${plugin}/options`, clusterId ? { clusterId } : undefined),
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
  create: (body: { projectId: string; clusterId?: string; name: string; jobType: JobView['jobType']; editMode: 'FORM' | 'SCRIPT'; description?: string }) =>
    post<JobView>('/jobs', body),
  update: (id: string, body: { name: string; description?: string | null; projectId?: string }) => put<JobView>(`/jobs/${id}`, body),
  copy: (id: string, body: { name: string; projectId: string; clusterId?: string }) =>
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

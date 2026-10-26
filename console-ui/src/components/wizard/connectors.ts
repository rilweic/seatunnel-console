import type { Component } from 'vue'

import { datasourceApi, type ColumnMeta, type HttpSourceSpec, type WizardForm } from '@/api/console'

import HttpSourceStep from './HttpSourceStep.vue'
import JdbcSourceStep from './JdbcSourceStep.vue'

/** What a source step may use from the wizard, e.g. to preview a request. */
export interface SourceContext {
  jobId: string
  /** The form as it would be sent now. */
  payload: () => WizardForm
  params: () => Record<string, string>
}

/**
 * The wizard's view of one SeaTunnel source connector (design.md §7.14), the counterpart of the
 * server's WizardSource. What a datasource type can do comes from its capabilities; this holds
 * only what the screens need.
 */
export interface WizardSourceUi {
  /** Step ① below the datasource select. Props: form, context; emits keyless(names). */
  step: Component
  /** Settings a new source of this kind starts with (form.source.spec). */
  newSpec?: () => WizardForm['source']['spec']
  /** The spec as sent: blank optional strings become null. */
  cleanSpec?: (spec: NonNullable<WizardForm['source']['spec']>) => WizardForm['source']['spec']
  /** Reads by a time window on a column, with per-table filters and incremental fields. */
  incremental: boolean
  /** Advanced options the wizard sets itself, so they are not offered. */
  handledOptions: string[]
  /** What is missing on step ①, or null. */
  stepProblem: (form: WizardForm) => string | null
  /** For a real-time source: what its CDC connector can do (design.md §12 P2, 实测结论). */
  stream?: { startFromTime: boolean; onlyAddedColumns: boolean }
  /** Columns come from database tables, which can change on their own; saves remember them (§12 P2, Q17). */
  liveColumns?: boolean
  /** Columns of the selected tables for step ③; `known` holds those already loaded. */
  loadColumns: (form: WizardForm, known: Record<string, ColumnMeta[]>) => Promise<Record<string, ColumnMeta[]>>
  text: {
    intro: string
    fields: string
    fieldsHelp: string
    run: string
    parallelismHelp: string
    validationHelp: string
  }
}

/** The wizard's view of one SeaTunnel sink connector. */
export interface WizardSinkUi {
  /** What a table is called on this target: 目标表, 索引. */
  tableWord: string
  /** Applies every column change a real-time job passes on; Elasticsearch only adds fields. */
  allColumnChanges: boolean
}

const clean = (v?: string | null) => (v && v.trim() ? v.trim() : null)

export const wizardSources: Record<string, WizardSourceUi> = {
  Jdbc: {
    step: JdbcSourceStep,
    incremental: true,
    liveColumns: true,
    handledOptions: ['url', 'driver', 'username', 'password', 'table_path', 'table_list', 'query', 'where_condition', 'parallelism'],
    stepProblem: (form) => {
      if (!form.source.namespace) return '请选择库或 schema'
      if (form.source.tables.length === 0) return '请至少勾选一张表'
      return null
    },
    loadColumns: async (form, known) => {
      const columns = { ...known }
      for (const t of form.source.tables) {
        columns[t.name] ??= await datasourceApi.columns(form.source.datasourceId, form.source.namespace, t.name)
      }
      return columns
    },
    text: {
      intro: '选择数据源和库，再勾选需要同步的表。',
      fields: '选择全量或增量同步，并按需调整每张表的字段和过滤条件。',
      fieldsHelp: '每张表同步哪些字段、写成什么名字；过滤条件和增量字段也在这里按表设置',
      run: '根据数据量设置并行度，并选择是否进行行数校验。',
      parallelismHelp: '单表时按主键自动切分、并行读取；多表时多张表同时读。数据量不大时用 1 即可，并行度越高对源库压力越大。失败重试和超时在“调度”页配置。',
      validationHelp: '运行成功后按表比对源端和目标端的行数。只在“全量 + 清空后写入”或“按时间增量”时准确，其他情况会自动跳过并说明原因。',
    },
  },
  Http: {
    step: HttpSourceStep,
    newSpec: (): HttpSourceSpec => ({
      path: '/',
      method: 'GET',
      params: {},
      body: '',
      paging: { mode: 'NONE', pageLocation: 'PARAMS', startPageNumber: 1, pageSize: 100 },
      contentField: null,
      fields: [],
      primaryKeys: [],
    }),
    cleanSpec: (spec) => {
      const http = spec as HttpSourceSpec
      return {
        ...http,
        path: clean(http.path) ?? '/',
        params: Object.fromEntries(Object.entries(http.params ?? {}).filter(([, v]) => v?.trim())),
        body: http.body || null,
        contentField: clean(http.contentField),
        fields: http.fields.map((f) => ({ ...f, name: f.name.trim(), jsonPath: clean(f.jsonPath) })),
        primaryKeys: http.primaryKeys ?? [],
      }
    },
    incremental: false,
    handledOptions: ['url', 'method', 'params', 'body', 'headers', 'schema', 'content_field', 'json_field', 'format', 'pageing',
      'page_type', 'keep_page_param_as_http_param', 'keep_params_as_form', 'connect_timeout_ms', 'socket_timeout_ms',
      'poll_interval_millis', 'binary_chunk_size', 'enable_multi_lines'],
    stepProblem: (form) => {
      const http = form.source.spec as HttpSourceSpec | null | undefined
      if (!http?.path.trim()) return '请填写 HTTP 请求路径'
      if (!http.fields.length) return '请发送请求预览并选择数据数组和字段'
      return null
    },
    loadColumns: async (form) => {
      const http = form.source.spec as HttpSourceSpec | null | undefined
      const fields = http?.fields ?? []
      const primaryKeys = http?.primaryKeys ?? []
      return Object.fromEntries(form.source.tables.map((t) => [t.name, fields.map((f) => ({
        name: f.name,
        type: f.type,
        nullable: true,
        primaryKey: primaryKeys.includes(f.name),
        temporal: f.type === 'DATE' || f.type === 'TIMESTAMP',
      }))]))
    },
    text: {
      intro: '配置一个 HTTP 接口，预览响应并确认要同步的字段。',
      fields: '确认接口字段映射；需要增量时，在来源请求参数或 Body 中使用时间变量。',
      fieldsHelp: '选择同步字段、目标名称和字段处理',
      run: 'Http source 固定单并行，接口无法直接计数，因此不做行数校验。',
      parallelismHelp: 'HTTP 接口按分页顺序读取，并行度固定为 1。',
      validationHelp: '接口无法直接提供总数，运行页仍会显示引擎读写条数。',
    },
  },
}

/**
 * Real-time jobs (design.md §12 P2), by the CDC connector the datasource declares: tables are
 * picked as for Jdbc, but read through the binlog / WAL, so there are no filters or incremental fields.
 */
const cdcSource: WizardSourceUi = {
  ...wizardSources.Jdbc!,
  incremental: false,
  handledOptions: ['url', 'username', 'password', 'database-names', 'schema-name', 'table-names', 'table-pattern',
    'startup.mode', 'startup.timestamp', 'schema-changes.enabled', 'schema-changes.include', 'schema-changes.exclude',
    'decoding.plugin.name', 'slot.name'],
  text: {
    intro: '选择数据源和库，再勾选需要实时同步的表。表必须有主键：更新和删除按主键写到目标端。',
    fields: '选择从哪里开始同步、是否同步表结构变更，并按需调整每张表的字段。',
    fieldsHelp: '每张表同步哪些字段、写成什么名字。实时同步不支持过滤条件',
    run: '设置全量阶段的并行度和 checkpoint 间隔。',
    parallelismHelp: '只作用于全量阶段：按主键把表切成分片同时读。之后的增量变更按顺序读取。并行度越高对源库压力越大。',
    validationHelp: '实时任务的数据一直在变，不做行数校验。',
  },
}

export const wizardStreamSources: Record<string, WizardSourceUi> = {
  'MySQL-CDC': { ...cdcSource, stream: { startFromTime: true, onlyAddedColumns: false } },
  'Postgres-CDC': { ...cdcSource, stream: { startFromTime: false, onlyAddedColumns: true } },
}

export const wizardSinks: Record<string, WizardSinkUi> = {
  Jdbc: { tableWord: '目标表', allColumnChanges: true },
  Elasticsearch: { tableWord: '索引', allColumnChanges: false },
}

/** Sink options the wizard sets itself or takes from the datasource, for every sink. */
export const HANDLED_SINK_OPTIONS = ['url', 'driver', 'username', 'password', 'database', 'table', 'generate_sink_sql',
  'enable_upsert', 'schema_save_mode', 'data_save_mode', 'primary_keys', 'hosts', 'index', 'tablePrefix', 'tableSuffix']

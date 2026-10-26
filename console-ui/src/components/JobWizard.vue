<script setup lang="ts">
/**
 * The job wizard (docs/design.md §7.2): collects a WizardForm in five steps. The HOCON is generated
 * and checked by the server; this component never builds config itself. What differs between
 * sources and targets comes from the datasource capabilities and the connector registry
 * (./wizard/connectors.ts, §7.14), so no type is named here.
 */
import { Check } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import {
  connectorApi,
  datasourceApi,
  jobApi,
  type ColumnMeta,
  type ConnectorOption,
  type DatasourceView,
  type IncrementalView,
  type NameRule,
  type PrecheckResult,
  type StreamSettings,
  type WizardForm,
} from '@/api/console'
import HelpTip from '@/components/HelpTip.vue'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import { useDatasourceTypes } from '@/composables/useDatasourceTypes'
import { columnChanges } from '@/utils/columnChanges'
import { formatTime } from '@/utils/format'
import { paramsProblem } from '@/utils/params'
import { fieldNamesProblem, isEmptyRule, migrateNaming, syncedFields, tableNamesProblem } from '@/utils/naming'

import AdvancedOptions from './wizard/AdvancedOptions.vue'
import ConditionEditor from './wizard/ConditionEditor.vue'
import { HANDLED_SINK_OPTIONS, wizardSinks, wizardSources, wizardStreamSources, type SourceContext } from './wizard/connectors'
import FieldMapping from './wizard/FieldMapping.vue'
import StreamSyncSettings from './wizard/StreamSyncSettings.vue'
import TableMapping from './wizard/TableMapping.vue'

const props = defineProps<{
  jobId: string
  ownerId: string | null
  readonly: boolean
  initialForm: WizardForm | null
  /** a real-time job: CDC sources, stream settings instead of the sync mode (design.md §12 P2) */
  streaming?: boolean
}>()
const params = defineModel<ParamRow[]>('params', { required: true })
const emit = defineEmits<{ saved: [versionNo: number]; dirty: [dirty: boolean] }>()

/** Bound by the job page, which keeps it in the URL. */
const step = defineModel<number>('step', { default: 0 })
const steps = computed(() => ['来源', '目标', '字段与同步方式', props.streaming ? '并行度与 checkpoint' : '并行度与校验', '预检查与预览'])

const { types, ready: typesReady, capabilities, label: typeLabel } = useDatasourceTypes()
const datasources = ref<DatasourceView[]>([])
const typeOf = (id: string) => datasources.value.find((d) => d.id === id)?.type
const sourceCaps = computed(() => capabilities(typeOf(form.source.datasourceId)))
const targetCaps = computed(() => capabilities(typeOf(form.target.datasourceId)))
/** The source's screens and checks, or undefined until a source is chosen. */
const sourceUi = computed(() =>
  props.streaming ? wizardStreamSources[sourceCaps.value?.cdcConnector ?? ''] : wizardSources[sourceCaps.value?.sourceConnector ?? ''],
)
/** The SeaTunnel source connector the job is generated with. */
const sourcePlugin = computed(() => (props.streaming ? sourceCaps.value?.cdcConnector : sourceCaps.value?.sourceConnector) ?? '')
const sinkUi = computed(() => wizardSinks[targetCaps.value?.sinkConnector ?? ''])
const lowerCaseNames = computed(() => !!targetCaps.value?.lowerCaseNames)
const tableWord = computed(() => sinkUi.value?.tableWord ?? '目标表')
const canRead = (caps?: { sourceConnector: string | null; cdcConnector: string | null } | null) =>
  props.streaming ? !!wizardStreamSources[caps?.cdcConnector ?? ''] : !!wizardSources[caps?.sourceConnector ?? '']
const sourceDatasources = computed(() => datasources.value.filter((d) => canRead(capabilities(d.type))))
const targetDatasources = computed(() => datasources.value.filter((d) => wizardSinks[capabilities(d.type)?.sinkConnector ?? '']))
/** e.g. "MySQL、PostgreSQL 或 HTTP", for the select placeholders. */
function labels(can: (t: (typeof types.value)[number]) => boolean) {
  const names = types.value.filter(can).map((t) => t.capabilities.label)
  return names.length > 1 ? `${names.slice(0, -1).join('、')} 或 ${names[names.length - 1]}` : names.join('')
}
const sourcePlaceholder = computed(() => labels((t) => canRead(t.capabilities)))
const targetPlaceholder = computed(() => labels((t) => !!wizardSinks[t.capabilities.sinkConnector ?? '']))

function emptyForm(): WizardForm {
  return {
    source: { datasourceId: '', namespace: '', tables: [] },
    target: {
      datasourceId: '',
      namespace: '',
      schemaMode: 'CREATE',
      dataMode: 'KEEP',
      writeMode: 'UPSERT',
      tableRule: { find: '', replace: '', nameCase: 'KEEP', prefix: '', suffix: '' },
    },
    sync: { mode: 'FULL', window: 'CONTINUOUS', field: '', customCondition: '' },
    parallelism: 1,
    validation: !props.streaming,
    sourceOptions: {},
    sinkOptions: {},
    stream: props.streaming ? newStream() : null,
  }
}

function newStream(): StreamSettings {
  return { startup: 'INITIAL', startupTimestamp: null, schemaChanges: true, dropColumns: false, skipDeletes: false, checkpointSeconds: null }
}

const form = reactive<WizardForm>(props.initialForm ? (JSON.parse(JSON.stringify(props.initialForm)) as WizardForm) : emptyForm())
form.sync.window ??= 'CONTINUOUS'
form.sourceOptions ??= {}
form.sinkOptions ??= {}
if (props.streaming) form.stream ??= newStream()
migrateNaming(form)
/** Target defaults follow the chosen tables until the user changes them; a saved form keeps its own. */
const targetTouched = ref(props.initialForm !== null)

// ---- step 1: source ----
/** Selected tables without a primary key, reported by the source step. */
const tablesWithoutPk = ref<string[]>([])
const sourceContext: SourceContext = {
  jobId: props.jobId,
  payload: () => payload(),
  params: () => toMap(params.value),
}

function onKeyless(names: string[]) {
  tablesWithoutPk.value = names
  if (!targetTouched.value) applyTargetDefaults()
}

/** A source that cannot run in parallel or be counted keeps the form within what it can do. */
function fitToSource() {
  const caps = sourceCaps.value
  if (!caps) return
  form.parallelism = Math.min(form.parallelism, caps.maxParallelism)
  if (!caps.countBy || props.streaming) form.validation = false
  if (form.stream && !sourceUi.value?.stream?.startFromTime && form.stream.startup === 'TIMESTAMP') form.stream.startup = 'INITIAL'
}

function onSourceDatasource() {
  form.source.namespace = ''
  form.source.tables = []
  tablesWithoutPk.value = []
  columns.value = {}
  // advanced options belong to the connector; the server refuses another connector's
  form.sourceOptions = {}
  form.source.spec = sourceUi.value?.newSpec?.() ?? null
  fitToSource()
}

// ---- step 2: target ----
const targetNamespaces = ref<string[]>([])

async function loadTargetNamespaces() {
  targetNamespaces.value = form.target.datasourceId && targetCaps.value?.namespaces ? await datasourceApi.namespaces(form.target.datasourceId) : []
}

async function onTargetDatasource() {
  form.target.namespace = ''
  targetNamespaces.value = []
  if (!form.target.datasourceId) return
  await loadTargetNamespaces()
}

/** §7.4: upsert + keep when every table has a primary key; otherwise append, and clear on full sync. */
function applyTargetDefaults() {
  const allPk = tablesWithoutPk.value.length === 0
  form.target.writeMode = allPk ? 'UPSERT' : 'APPEND'
  form.target.dataMode = !allPk && form.sync.mode === 'FULL' ? 'CLEAR' : 'KEEP'
}

watch(
  () => form.sync.mode,
  (mode) => {
    if (mode === 'INCREMENTAL' && form.target.dataMode === 'CLEAR') form.target.dataMode = 'KEEP'
    else if (!targetTouched.value) applyTargetDefaults()
  },
)

// ---- step 3: columns and sync ----
const columns = ref<Record<string, ColumnMeta[]>>({})
const loadingColumns = ref(false)

async function loadColumns() {
  if (!sourceUi.value) return
  loadingColumns.value = true
  try {
    columns.value = await sourceUi.value.loadColumns(form, columns.value)
  } finally {
    loadingColumns.value = false
  }
}

/** Time columns present in every selected table: candidates for the job-wide incremental field. */
const commonTimeFields = computed(() => {
  const lists = form.source.tables.map((t) => (columns.value[t.name] ?? []).filter((c) => c.temporal).map((c) => c.name))
  if (lists.length === 0) return []
  return lists.reduce((a, b) => a.filter((x) => b.includes(x)))
})

// ---- step 4: parallelism, validation, advanced options ----
const parallelisms = computed(() => [1, 2, 4, 8].filter((p) => p <= (sourceCaps.value?.maxParallelism ?? 8)))
const sourceOptionDefs = ref<ConnectorOption[]>([])
const sinkOptionDefs = ref<ConnectorOption[]>([])
const optionsError = ref('')

async function loadOptionDefs() {
  optionsError.value = ''
  const plugin = sourcePlugin.value
  const sinkPlugin = targetCaps.value?.sinkConnector
  if (!plugin || !sinkPlugin) return
  try {
    const handled = sourceUi.value?.handledOptions ?? []
    const [src, snk] = await Promise.all([connectorApi.options('source', plugin), connectorApi.options('sink', sinkPlugin)])
    sourceOptionDefs.value = src.filter((o) => !handled.includes(o.key) && !o.key.startsWith('auth.'))
    sinkOptionDefs.value = snk.filter((o) => !HANDLED_SINK_OPTIONS.includes(o.key) && !o.key.startsWith('auth.'))
  } catch {
    optionsError.value = '无法从 SeaTunnel 集群读取连接器参数，高级参数暂不可用'
  }
}

const advancedCount = computed(
  () =>
    Object.values(form.sourceOptions ?? {}).filter((v) => v?.trim()).length +
    Object.values(form.sinkOptions ?? {}).filter((v) => v?.trim()).length,
)

// ---- step 5: precheck and preview ----
const precheck = ref<PrecheckResult | null>(null)
const checking = ref(false)
const remark = ref('')
const saving = ref(false)

/** A rule as sent: null when it changes nothing, so the server sees "not set". */
function cleanRule(rule?: NameRule | null): NameRule | null {
  return isEmptyRule(rule) ? null : trimRule(rule!)
}

function trimRule(rule: NameRule): NameRule {
  return {
    find: rule.find || null,
    replace: rule.find ? rule.replace ?? '' : null,
    nameCase: rule.nameCase ?? 'KEEP',
    prefix: rule.prefix?.trim() || null,
    suffix: rule.suffix?.trim() || null,
  }
}

/** Names set by hand for fields that are synced (all of them while the columns are not loaded). */
function cleanFieldNames(t: WizardForm['source']['tables'][number]) {
  const all = columns.value[t.name]?.map((c) => c.name)
  const synced = all ? new Set(syncedFields(t, all)) : null
  const entries = Object.entries(t.fieldNames ?? {}).filter(([k, v]) => v?.trim() && (!synced || synced.has(k)))
  return entries.length ? Object.fromEntries(entries.map(([k, v]) => [k, v.trim()])) : null
}

function toMap(rows: ParamRow[]) {
  return Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
}

/** The columns a save remembers: those shown now, else what the last save remembered. */
function seenColumns(t: WizardForm['source']['tables'][number]) {
  if (!sourceUi.value?.liveColumns) return null
  const shown = columns.value[t.name]
  return shown ? shown.map((c) => ({ name: c.name, type: c.type })) : t.seenColumns ?? null
}

/** The form as sent: blank optional strings become null so the server sees "not set". */
function payload(): WizardForm {
  const clean = (v?: string | null) => (v && v.trim() ? v.trim() : null)
  const opts = (o?: Record<string, string>) => Object.fromEntries(Object.entries(o ?? {}).filter(([, v]) => v?.trim()))
  const incremental = !!sourceUi.value?.incremental
  const spec = form.source.spec
  return {
    source: {
      datasourceId: form.source.datasourceId,
      namespace: form.source.namespace,
      tables: form.source.tables.map((t) => ({
        name: t.name,
        columns: t.columns && t.columns.length ? t.columns : null,
        filter: clean(t.filter),
        incrementalField: clean(t.incrementalField),
        targetName: clean(t.targetName),
        fieldNames: cleanFieldNames(t),
        fieldRule: t.fieldRule ? trimRule(t.fieldRule) : null,
        processes: t.processes && Object.keys(t.processes).length ? t.processes : null,
        extracts: t.extracts?.length ? t.extracts : null,
        addedFields: t.addedFields?.length ? t.addedFields : null,
        encryptKeyId: t.encryptKeyId || null,
        seenColumns: seenColumns(t),
      })),
      spec: spec ? (sourceUi.value?.cleanSpec?.(spec) ?? spec) : null,
    },
    target: {
      ...form.target,
      namespace: targetCaps.value && !targetCaps.value.namespaces ? null : clean(form.target.namespace),
      tableName: null,
      prefix: null,
      suffix: null,
      tableRule: cleanRule(form.target.tableRule),
    },
    sync:
      !incremental || form.sync.mode === 'FULL'
        ? { mode: 'FULL', window: null, field: null, customCondition: null }
        : {
            mode: 'INCREMENTAL',
            window: form.sync.window,
            field: form.sync.window === 'CUSTOM' ? null : clean(form.sync.field),
            customCondition: form.sync.window === 'CUSTOM' ? clean(form.sync.customCondition) : null,
          },
    parallelism: Math.min(form.parallelism, sourceCaps.value?.maxParallelism ?? form.parallelism),
    validation: props.streaming || (sourceCaps.value && !sourceCaps.value.countBy) ? false : form.validation,
    sourceOptions: opts(form.sourceOptions),
    sinkOptions: opts(form.sinkOptions),
    fieldRule: cleanRule(form.fieldRule),
    stream: props.streaming && form.stream
      ? {
          ...form.stream,
          startupTimestamp: form.stream.startup === 'TIMESTAMP' ? form.stream.startupTimestamp ?? null : null,
          dropColumns: form.stream.schemaChanges && form.stream.dropColumns,
          checkpointSeconds: form.stream.checkpointSeconds || null,
        }
      : null,
  }
}

/** Why field processing is not offered now: a real-time job passing on schema changes (§12 P2, Q22). */
const processingBlocked = computed(() =>
  props.streaming && form.stream?.schemaChanges
    ? '已打开"同步表结构变更"，不能使用字段处理：源表改名或删列时，字段处理会让任务失败。需要字段处理时，先在上面关掉"同步表结构变更"'
    : null,
)

async function runPrecheck() {
  checking.value = true
  try {
    precheck.value = await jobApi.wizardPrecheck(props.jobId, { form: payload(), params: toMap(params.value) })
  } finally {
    checking.value = false
  }
}

const hasErrors = computed(() => !precheck.value || precheck.value.checks.some((c) => c.level === 'ERROR'))

/**
 * Saves from any step: checks the steps first (stopping on the first incomplete one), then runs
 * the server precheck and shows it on the last step when it reports errors.
 */
async function save() {
  for (let i = 0; i < steps.value.length - 1; i++) {
    const error = stepError(i)
    if (error) {
      step.value = i
      ElMessage.warning(error)
      return
    }
  }
  saving.value = true
  try {
    await runPrecheck()
    if (hasErrors.value) {
      step.value = steps.value.length - 1
      ElMessage.warning('预检查发现错误，修正后才能保存')
      return
    }
    const version = await jobApi.saveVersion(props.jobId, { form: payload(), params: toMap(params.value), remark: remark.value || undefined })
    ElMessage.success(`已保存为 v${version.versionNo}`)
    remark.value = ''
    // what was saved is the new baseline, so the change marks go
    form.source.tables.forEach((t) => (t.seenColumns = seenColumns(t)))
    snapshot.value = currentSnapshot()
    emit('saved', version.versionNo)
  } finally {
    saving.value = false
  }
}

// ---- unsaved changes ----
/** Remembered columns are left out: only looking at a changed table is no change to the job. */
const currentSnapshot = () =>
  JSON.stringify([payload(), toMap(params.value)], (k, v) => (k === 'seenColumns' ? undefined : v))
/** Taken once the saved form has been loaded into the steps; null until then. */
const snapshot = ref<string | null>(null)
/** A source table that changed since the last save also makes one worth saving: it updates the config. */
const tablesChanged = computed(
  () => !!sourceUi.value?.liveColumns && form.source.tables.some((t) => columnChanges(t.seenColumns, columns.value[t.name])),
)
const dirty = computed(() => snapshot.value !== null && (currentSnapshot() !== snapshot.value || tablesChanged.value))
watch(dirty, (value) => emit('dirty', value))

// ---- navigation ----
function stepError(i: number): string | null {
  if (i === 0) {
    if (!form.source.datasourceId) return '请选择源数据源'
    const problem = sourceUi.value?.stepProblem(form)
    if (problem) return problem
  }
  if (i === 1) {
    if (!form.target.datasourceId) return '请选择目标数据源'
    if (targetCaps.value?.namespaces && !form.target.namespace) return '请选择目标库或 schema'
    const names = tableNamesProblem(form, lowerCaseNames.value, tableWord.value)
    if (names) return names
  }
  if (i === 2) {
    const fields = fieldNamesProblem(form, columns.value)
    if (fields) return fields
    const paramError = paramsProblem(params.value)
    if (paramError) return paramError
    const noKey = form.source.tables.find((t) => Object.values(t.processes ?? {}).some((p) => p.encrypt) && !t.encryptKeyId)
    if (noKey) return `${noKey.name} 有字段要加密，请在字段映射里选择加密密钥`
  }
  if (i === 2 && props.streaming && form.stream) {
    if (form.stream.startup === 'TIMESTAMP' && !form.stream.startupTimestamp) return '请选择开始同步的时间点'
    const processed = form.source.tables.find(
      (t) => Object.keys(t.processes ?? {}).length || t.extracts?.length || t.addedFields?.length,
    )
    if (form.stream.schemaChanges && processed) return `${processed.name} 有字段处理，不能同时同步表结构变更：去掉字段处理，或关掉"同步表结构变更"`
  }
  if (i === 2 && sourceUi.value?.incremental && form.sync.mode === 'INCREMENTAL') {
    if (form.sync.window === 'CUSTOM' && !form.sync.customCondition?.trim()) return '请填写自定义增量条件'
    if (form.sync.window !== 'CUSTOM') {
      const missing = form.source.tables.filter((t) => !(t.incrementalField?.trim() || form.sync.field))
      if (missing.length) return `请选择增量字段：${missing.map((t) => t.name).join('，')}`
    }
  }
  return null
}

/** @param restoring opening on a step from the URL: stop quietly at the first incomplete step */
async function go(target: number, restoring = false) {
  for (let i = step.value; i < target; i++) {
    const error = stepError(i)
    if (error) {
      if (!restoring) {
        ElMessage.warning(error)
        return
      }
      target = i
      break
    }
  }
  step.value = target
  if (target === 2) loadColumns()
  if (target === 3) loadOptionDefs()
  if (target === 4) {
    precheck.value = null
    runPrecheck()
  }
}

/** The job's watermark and lag, for describing the continuous window (design.md §7.5). */
const incremental = ref<IncrementalView | null>(null)
const lagText = computed(() => {
  const lag = incremental.value?.lagSeconds ?? 60
  return lag === 0 ? '（不留延迟）' : lag % 60 === 0 ? ` ${lag / 60} 分钟` : ` ${lag} 秒`
})
const nextWindowText = computed(() => {
  const i = incremental.value
  if (!i) return ''
  return `[${i.nextStart ? formatTime(i.nextStart) : '最早的数据'}, ${formatTime(i.nextEnd)})（如果现在运行）`
})

onMounted(async () => {
  jobApi.incremental(props.jobId).then((v) => (incremental.value = v), () => undefined)
  const initialStep = step.value
  step.value = 0
  // A job may only use its creator's datasources.
  const [list] = await Promise.all([datasourceApi.list({ size: 200, ownerId: props.ownerId }), typesReady])
  datasources.value = list.items
  if (form.source.datasourceId) {
    form.source.spec ??= sourceUi.value?.newSpec?.() ?? null
    fitToSource()
  }
  await loadTargetNamespaces()
  await nextTick()
  snapshot.value = currentSnapshot()
  if (initialStep > 0) {
    go(initialStep, true)
  }
})
</script>

<template>
  <div class="wizard">
    <ol class="steps">
      <li v-for="(title, i) in steps" :key="title" :class="{ active: i === step, done: i < step }" @click="go(i)">
        <span class="step-icon"><el-icon v-if="i < step"><Check /></el-icon><template v-else>{{ i + 1 }}</template></span>
        <span class="step-title">{{ title }}</span>
      </li>
    </ol>

    <!-- ① 来源 -->
    <section v-show="step === 0">
      <div class="step-heading">
        <h3>选择数据来源</h3>
        <p>{{ sourceUi?.text.intro ?? '选择要读取的数据源。' }}</p>
      </div>
      <el-form label-position="top" class="source-form">
        <el-form-item label="源数据源">
          <el-select v-model="form.source.datasourceId" :placeholder="sourcePlaceholder" filterable @change="onSourceDatasource">
            <el-option v-for="d in sourceDatasources" :key="d.id" :label="`${d.name} (${typeLabel(d.type)})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <!-- the source step puts its own fields here, e.g. the database or schema -->
        <div id="wizard-source-fields" class="source-fields" />
      </el-form>
      <component
        :is="sourceUi.step"
        v-if="sourceUi"
        :key="form.source.datasourceId"
        :form="form"
        :context="sourceContext"
        @keyless="onKeyless"
      />
    </section>

    <!-- ② 目标 -->
    <section v-show="step === 1">
      <div class="step-heading">
        <h3>配置目标与写入方式</h3>
        <p>指定数据写入的位置，以及目标表已存在时的处理方式。</p>
      </div>
      <el-form label-width="130px" class="narrow form-card">
        <el-form-item label="目标数据源">
          <el-select v-model="form.target.datasourceId" :placeholder="targetPlaceholder" filterable class="w320" @change="onTargetDatasource">
            <el-option v-for="d in targetDatasources" :key="d.id" :label="`${d.name} (${typeLabel(d.type)})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.target.datasourceId && targetCaps?.namespaces" label="库 / schema">
          <el-select v-model="form.target.namespace" placeholder="必须已存在，不会自动建库" filterable class="w320" @visible-change="(open: boolean) => open && loadTargetNamespaces()">
            <el-option v-for="n in targetNamespaces" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item label="不存在时">
          <el-radio-group v-model="form.target.schemaMode" @change="targetTouched = true">
            <el-radio value="CREATE">自动创建</el-radio>
            <el-radio value="ERROR">报错</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="streaming" label="写入方式">
          <div class="tip">按主键写入：源表的插入、更新、删除都按主键落到目标表，目标表已有的数据保留。</div>
        </el-form-item>
        <el-form-item v-if="!streaming" label="写入方式">
          <el-radio-group v-model="form.target.writeMode" @change="targetTouched = true">
            <el-radio value="UPSERT">按主键 upsert</el-radio>
            <el-radio value="APPEND">追加</el-radio>
          </el-radio-group>
          <div v-if="form.target.writeMode === 'UPSERT' && tablesWithoutPk.length" class="tip full warn">
            {{ tablesWithoutPk.join('，') }} 没有主键，这些表只能按追加写入
          </div>
        </el-form-item>
        <el-form-item v-if="!streaming" label="目标已有数据">
          <el-radio-group v-model="form.target.dataMode" @change="targetTouched = true">
            <el-radio value="KEEP">保留</el-radio>
            <el-radio value="CLEAR" :disabled="form.sync.mode === 'INCREMENTAL'">清空后写入</el-radio>
          </el-radio-group>
          <el-alert v-if="form.target.dataMode === 'CLEAR'" type="warning" :closable="false" show-icon class="full"
            title="每次运行都会先清空目标表（或索引）里的全部数据，再写入" />
          <div v-else-if="form.sync.mode === 'FULL' && form.target.writeMode === 'APPEND'" class="tip full warn">
            全量同步 + 追加 + 保留已有数据：每次运行都会再写入一遍，造成重复
          </div>
        </el-form-item>
      </el-form>
      <TableMapping v-if="form.target.datasourceId && form.source.tables.length" :form="form" :lower-case="lowerCaseNames" :table-word="tableWord" />
    </section>

    <!-- ③ 字段与同步方式 -->
    <section v-show="step === 2">
      <div class="step-heading">
        <h3>设置字段与同步方式</h3>
        <p>{{ sourceUi?.text.fields }}</p>
      </div>
      <el-form v-if="sourceUi?.incremental" label-width="96px" class="form-card">
        <el-form-item label="同步方式">
          <el-radio-group v-model="form.sync.mode">
            <el-radio value="FULL">全量</el-radio>
            <el-radio value="INCREMENTAL">按时间增量</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.sync.mode === 'INCREMENTAL'">
          <el-form-item label="时间窗口">
            <el-radio-group v-model="form.sync.window">
              <el-radio value="CONTINUOUS">连续增量（推荐）</el-radio>
              <el-radio value="DAY">按天</el-radio>
              <el-radio value="HOUR">按小时</el-radio>
              <el-radio value="CUSTOM">自定义条件</el-radio>
            </el-radio-group>
            <div class="tip full">
              <template v-if="form.sync.window === 'CONTINUOUS'">
                每次从上次成功同步到的位置（水位）接着同步，到计划时间往前{{ lagText }}为止：[水位, 计划时间 − 延迟)。
                失败不推进水位，下一次运行会把这段一起补上；重跑沿用原来的窗口。第一次运行从最早的数据开始。
                水位和延迟在任务详情的「增量水位」里查看和调整。<template v-if="nextWindowText"><br />下次运行将同步 {{ nextWindowText }}。</template>
              </template>
              <template v-else-if="form.sync.window === 'DAY'">
                每次同步计划时间的前一天：[前一天 00:00, 当天 00:00)，和其他运行无关，补数和重跑按各自的计划时间计算。
                适合每天运行一次、按天整段处理的任务；调度比每天一次更频繁时，每次都会重复同步同一天。
              </template>
              <template v-else-if="form.sync.window === 'HOUR'">
                每次同步计划时间的上一个小时：[上一小时整点, 本小时整点)。适合每小时运行一次；调度更频繁时，每次都会重复同步同一个小时。
              </template>
              <template v-else>
                自己写 where 条件，可以插入变量。要从上次的位置接着同步，用同步窗口变量 <code>${system.window.start}</code> / <code>${system.window.end}</code>，再加上自己的过滤。
                自定义条件套用不到目标表，不做行数校验。
              </template>
            </div>
          </el-form-item>
          <el-form-item v-if="form.sync.window !== 'CUSTOM'" label="增量字段">
            <el-select v-model="form.sync.field" placeholder="所有表共用的时间字段" clearable filterable class="w320">
              <el-option v-for="f in commonTimeFields" :key="f" :label="f" :value="f" />
            </el-select>
            <span v-if="form.source.tables.length > 1" class="tip">只列出所有选中表都有的时间字段；个别表可以在下面的字段映射里单独指定</span>
          </el-form-item>
          <el-form-item v-else label="增量条件">
            <ConditionEditor v-model="form.sync.customCondition" :job-id="jobId" :params="() => toMap(params)" :readonly="readonly" />
          </el-form-item>
        </template>
      </el-form>

      <StreamSyncSettings
        v-if="streaming && form.stream"
        v-model="form.stream"
        :postgres="!!sourceUi?.stream?.onlyAddedColumns"
        :all-column-changes="sinkUi?.allColumnChanges ?? true"
        :readonly="readonly"
      />

      <div class="section-title">
        字段映射
        <HelpTip :text="sourceUi?.text.fieldsHelp ?? ''" />
      </div>
      <FieldMapping
        :form="form"
        :columns="columns"
        :loading="loadingColumns"
        :lower-case="lowerCaseNames"
        :filters="!!sourceUi?.incremental"
        :processing-blocked="processingBlocked"
        :streaming="streaming"
      />

      <div class="section-title">
        任务参数
        <HelpTip text="HTTP 请求参数、Body 和数据库条件里可以用 ${名称} 引用" />
      </div>
      <ParamsEditor v-model="params" />
    </section>

    <!-- ④ 并行度与校验 -->
    <section v-show="step === 3">
      <div class="step-heading">
        <h3>{{ streaming ? '调整运行' : '调整运行与校验' }}</h3>
        <p>{{ sourceUi?.text.run }}</p>
      </div>
      <el-form label-width="96px" class="form-card">
        <el-form-item label="并行度">
          <el-radio-group v-model="form.parallelism" :disabled="parallelisms.length < 2">
            <el-radio-button v-for="p in parallelisms" :key="p" :value="p">{{ p }}</el-radio-button>
          </el-radio-group>
          <HelpTip :text="sourceUi?.text.parallelismHelp ?? ''" />
        </el-form-item>
        <el-form-item v-if="!streaming" label="行数校验">
          <el-switch v-model="form.validation" :disabled="!sourceCaps?.countBy" />
          <HelpTip :text="sourceUi?.text.validationHelp ?? ''" />
        </el-form-item>
        <el-form-item v-if="streaming && form.stream" label="checkpoint">
          每 <el-input-number v-model="form.stream.checkpointSeconds" :min="1" :max="3600" placeholder="10" controls-position="right" class="seconds" /> 秒
          <HelpTip text="多久保存一次同步进度。失败或强制停止后从最近一次 checkpoint 恢复，间隔越短重放的数据越少，但对引擎的开销越大。默认 10 秒" />
        </el-form-item>
      </el-form>
      <el-collapse class="advanced">
        <el-collapse-item name="advanced">
          <template #title>
            高级参数<span class="tip">（{{ advancedCount ? `已设置 ${advancedCount} 项` : '一般不需要修改' }}）</span>
          </template>
          <el-alert v-if="optionsError" :title="optionsError" type="warning" :closable="false" />
          <template v-else>
            <div class="tip adv-intro">不填就用默认值，大多数情况不需要改。改过的参数会高亮显示。</div>
            <AdvancedOptions v-model="form.sourceOptions!" title="读取（来源）" :defs="sourceOptionDefs" type="source" :plugin="sourcePlugin" />
            <AdvancedOptions
              v-model="form.sinkOptions!"
              title="写入（目标）"
              :defs="sinkOptionDefs"
              type="sink"
              :plugin="targetCaps?.sinkConnector ?? ''"
              :target-type="typeOf(form.target.datasourceId)"
            />
          </template>
        </el-collapse-item>
      </el-collapse>
    </section>

    <!-- ⑤ 预检查与预览 -->
    <section v-show="step === 4" v-loading="checking">
      <div class="step-heading">
        <h3>预检查与配置预览</h3>
        <p>确认检查结果和系统生成的配置，再保存为新版本。</p>
      </div>
      <div class="check-bar">
        <span class="section-title inline">检查结果</span>
        <el-button size="small" :loading="checking" @click="runPrecheck">重新检查</el-button>
      </div>
      <ul v-if="precheck" class="checks">
        <li v-for="(c, i) in precheck.checks" :key="i" :class="c.level.toLowerCase()">
          <el-tag size="small" :type="c.level === 'ERROR' ? 'danger' : c.level === 'WARN' ? 'warning' : 'info'">
            {{ c.level === 'ERROR' ? '错误' : c.level === 'WARN' ? '警告' : '提示' }}
          </el-tag>
          {{ c.message }}
        </li>
        <li v-if="precheck.checks.length === 0" class="info"><el-tag size="small" type="success">通过</el-tag> 没有发现问题</li>
      </ul>
      <template v-if="precheck?.generated">
        <div class="section-title">
          生成的配置
          <span class="tip">只读，保存后成为新版本。
            <template v-if="precheck.generated.validation?.tables?.length">行数校验：{{ precheck.generated.validation.tables.length }} 张表。</template>
          </span>
        </div>
        <pre class="hocon">{{ precheck.generated.hocon }}</pre>
      </template>
      <div v-if="precheck && hasErrors" class="tip warn">有错误，修正后才能保存</div>
    </section>

    <div class="nav">
      <div class="nav-steps">
        <el-button v-if="step > 0" @click="step--">上一步</el-button>
        <el-button v-if="step < steps.length - 1" type="primary" @click="go(step + 1)">下一步</el-button>
      </div>
      <div v-if="!readonly" class="nav-save">
        <span v-if="dirty" class="tip warn">有未保存的修改</span>
        <el-input v-model="remark" placeholder="版本备注（可选）" maxlength="512" class="remark" />
        <el-button type="primary" :loading="saving || checking" :disabled="!dirty && initialForm !== null" @click="save">保存为新版本</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.wizard {
  max-width: 1440px;
  margin: 0 auto;
}

/* Ant Design style steps: numbered circles joined by a line, passed steps checked. */
.steps {
  display: flex;
  align-items: center;
  margin: 0 0 24px;
  padding: 16px 18px;
  list-style: none;
  background: #f7faff;
  border: 1px solid #e7f0fc;
  border-radius: 10px;
}

.steps li {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 8px;
  min-width: 0;
  cursor: pointer;
}

.steps li:last-child {
  flex: none;
}

.steps li:not(:last-child)::after {
  content: '';
  flex: 1;
  height: 1px;
  margin: 0 12px 0 4px;
  background: var(--el-border-color);
}

.steps li.done:not(:last-child)::after {
  background: var(--el-color-primary);
}

.step-icon {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--el-border-color);
  border-radius: 50%;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  transition: all 0.2s;
}

.step-title {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  white-space: nowrap;
  transition: color 0.2s;
}

.steps li.done .step-icon {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.steps li.done .step-title {
  color: var(--el-text-color-primary);
}

.steps li.active .step-icon {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary);
  color: #fff;
}

.steps li.active .step-title {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.steps li:not(.active):hover .step-title,
.steps li:not(.active):hover .step-icon {
  color: var(--el-color-primary);
  border-color: var(--el-color-primary);
}

.step-heading {
  margin-bottom: 18px;
}

.step-heading h3 {
  margin: 0;
  font-size: 17px;
  line-height: 26px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.step-heading p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.source-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  padding: 18px 20px 2px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 10px;
}

.source-form :deep(.el-form-item) {
  margin-bottom: 16px;
}

.source-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.source-form :deep(.el-select) {
  width: 100%;
}

/* a source step's own fields take their place in the grid */
.source-fields {
  display: contents;
}

.form-card {
  box-sizing: border-box;
  padding: 18px 20px 6px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 10px;
}

.narrow {
  max-width: 960px;
}

.w320 {
  width: 320px;
}

.full {
  width: 100%;
  margin-top: 6px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.warn {
  color: var(--el-color-warning-dark-2);
}

.section-title {
  margin: 20px 0 8px;
  font-weight: 500;
}

.section-title.inline {
  margin: 0;
}

.code :deep(input),
.code :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.advanced {
  margin-top: 8px;
}

.adv-intro {
  margin-bottom: 12px;
}





.check-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}

.checks {
  list-style: none;
  padding: 0;
  margin: 12px 0;
}

.checks li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.hocon {
  margin: 0;
  padding: 12px;
  max-height: 420px;
  overflow: auto;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  white-space: pre-wrap;
}

.nav {
  /* stays in view at the bottom of the page while the step scrolls */
  position: sticky;
  bottom: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 24px;
  padding: 14px 16px;
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  box-shadow: 0 -4px 16px rgba(31, 68, 122, 0.04);
  backdrop-filter: blur(8px);
}

.nav-steps,
.nav-save {
  display: flex;
  align-items: center;
  gap: 10px;
}

.nav-save {
  justify-content: flex-end;
  flex-wrap: wrap;
}

.remark {
  width: 280px;
}

.seconds {
  width: 110px;
  margin: 0 6px;
}

@media (max-width: 1100px) {
  .steps {
    padding: 14px 12px;
  }

  .step-title {
    font-size: 12px;
  }

  .steps li:not(:last-child)::after {
    margin-right: 7px;
  }
}

@media (max-width: 900px) {
  .form-card,
  .narrow {
    max-width: 100%;
  }

  .remark {
    width: 220px;
  }
}

@media (max-width: 720px) {
  .steps {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 8px;
    align-items: start;
  }

  .steps li {
    flex-direction: column;
    justify-content: flex-start;
    gap: 6px;
    text-align: center;
  }

  .steps li:not(:last-child)::after {
    display: none;
  }

  .step-title {
    white-space: normal;
    line-height: 16px;
  }

  .source-form {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .tables {
    padding: 14px;
  }

  .tables-heading {
    flex-direction: column;
  }

  .table-filter {
    width: 100%;
  }

  .bar-hint {
    display: none;
  }

  .nav {
    align-items: stretch;
  }

  .nav-save {
    width: 100%;
  }

  .remark {
    flex: 1;
    width: auto;
  }

  .w320 {
    max-width: 100%;
  }
}

@media (max-width: 520px) {
  .source-form,
  .form-card {
    padding-right: 14px;
    padding-left: 14px;
  }

  .nav-save .warn,
  .nav-save .remark,
  .nav-save .el-button {
    width: 100%;
  }

  .nav-save .el-button {
    margin-left: 0;
  }
}
</style>

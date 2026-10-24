<script setup lang="ts">
/**
 * The job wizard (docs/design.md §7.2): collects a WizardForm in five steps. The HOCON is generated
 * and checked by the server; this component never builds config itself.
 */
import { Check, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import {
  connectorApi,
  datasourceApi,
  jobApi,
  type ColumnMeta,
  type ConnectorOption,
  type DatasourceView,
  type NameRule,
  type PrecheckResult,
  type TableMeta,
  type WizardForm,
} from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import { paramsProblem } from '@/utils/params'
import { fieldNamesProblem, isEmptyRule, migrateNaming, syncedFields, tableNamesProblem } from '@/utils/naming'

import AdvancedOptions from './wizard/AdvancedOptions.vue'
import FieldMapping from './wizard/FieldMapping.vue'
import TableMapping from './wizard/TableMapping.vue'

const props = defineProps<{ jobId: string; ownerId: string | null; readonly: boolean; initialForm: WizardForm | null }>()
const params = defineModel<ParamRow[]>('params', { required: true })
const emit = defineEmits<{ saved: [versionNo: number]; dirty: [dirty: boolean] }>()

/** Bound by the job page, which keeps it in the URL. */
const step = defineModel<number>('step', { default: 0 })
const steps = ['来源', '目标', '字段与同步方式', '并行度与校验', '预检查与预览']

const datasources = ref<DatasourceView[]>([])
const sourceDatasources = computed(() => datasources.value.filter((d) => d.type === 'MYSQL' || d.type === 'POSTGRESQL'))
const typeOf = (id: string) => datasources.value.find((d) => d.id === id)?.type
const targetIsEs = computed(() => typeOf(form.target.datasourceId) === 'ELASTICSEARCH')

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
    sync: { mode: 'FULL', window: 'DAY', field: '', customCondition: '' },
    parallelism: 1,
    validation: true,
    sourceOptions: {},
    sinkOptions: {},
  }
}

const form = reactive<WizardForm>(props.initialForm ? (JSON.parse(JSON.stringify(props.initialForm)) as WizardForm) : emptyForm())
form.sync.window ??= 'DAY'
form.sourceOptions ??= {}
form.sinkOptions ??= {}
migrateNaming(form)
/** Target defaults follow the chosen tables until the user changes them; a saved form keeps its own. */
const targetTouched = ref(props.initialForm !== null)

// ---- step 1: source ----
const sourceNamespaces = ref<string[]>([])
const sourceTables = ref<TableMeta[]>([])
const tableFilter = ref('')
const loadingTables = ref(false)
const tableRef = ref<{ toggleRowSelection: (row: TableMeta, selected: boolean) => void; clearSelection: () => void } | null>(null)
const selected = ref<string[]>(form.source.tables.map((t) => t.name))

const filteredTables = computed(() => {
  const q = tableFilter.value.trim()
  if (!q) return sourceTables.value
  let re: RegExp | null = null
  try {
    re = new RegExp(q, 'i')
  } catch {
    re = null
  }
  return sourceTables.value.filter((t) => (re ? re.test(t.name) : t.name.toLowerCase().includes(q.toLowerCase())))
})
const tableMeta = computed(() => new Map(sourceTables.value.map((t) => [t.name, t])))
const tablesWithoutPk = computed(() => selected.value.filter((n) => (tableMeta.value.get(n)?.primaryKeys.length ?? 1) === 0))

async function loadSourceNamespaces() {
  sourceNamespaces.value = form.source.datasourceId ? await datasourceApi.namespaces(form.source.datasourceId) : []
}

async function loadSourceTables() {
  sourceTables.value = []
  if (!form.source.datasourceId || !form.source.namespace) return
  loadingTables.value = true
  try {
    sourceTables.value = await datasourceApi.tables(form.source.datasourceId, form.source.namespace)
  } finally {
    loadingTables.value = false
  }
  await nextTick()
  restoreSelection()
}

/** Re-reads the table list, e.g. after tables were created; the selection is kept. */
async function refreshTables() {
  await loadSourceTables()
  const names = new Set(sourceTables.value.map((t) => t.name))
  const gone = selected.value.filter((n) => !names.has(n))
  if (gone.length) ElMessage.warning(`已选的表在源库里找不到了：${gone.join('，')}`)
  else ElMessage.success(`已刷新，共 ${sourceTables.value.length} 张表`)
}

/** Shows `selected` in the table; it never changes `selected` itself. */
function restoreSelection() {
  tableRef.value?.clearSelection()
  for (const t of filteredTables.value) {
    if (selected.value.includes(t.name)) tableRef.value?.toggleRowSelection(t, true)
  }
}

/*
 * `selected` changes only on the user's own clicks (select / select-all). The table's
 * selection-change also fires when its rows change, e.g. while typing a filter, and would then
 * report the hidden tables as unchecked; listening to it lost the selection.
 */
// Order is kept (new tables go last), so checking and unchecking a table leaves the form as it was.
function onSelect(rows: TableMeta[], row: TableMeta) {
  const checked = rows.some((r) => r.name === row.name)
  selected.value = checked
    ? [...selected.value.filter((n) => n !== row.name), row.name]
    : selected.value.filter((n) => n !== row.name)
}

function onSelectAll(rows: TableMeta[]) {
  const visible = filteredTables.value.map((t) => t.name)
  selected.value = rows.length > 0
    ? [...selected.value, ...visible.filter((n) => !selected.value.includes(n))]
    : selected.value.filter((n) => !visible.includes(n))
}

watch(filteredTables, () => nextTick(restoreSelection))

async function onSourceDatasource() {
  form.source.namespace = ''
  selected.value = []
  sourceTables.value = []
  await loadSourceNamespaces()
}

async function onSourceNamespace() {
  selected.value = []
  await loadSourceTables()
}

/** Keeps per-table settings of tables that stay selected. */
watch(selected, (names) => {
  const old = new Map(form.source.tables.map((t) => [t.name, t]))
  form.source.tables = names.map((n) => old.get(n) ?? { name: n, columns: null, filter: '', incrementalField: '' })
  if (!targetTouched.value) applyTargetDefaults()
})

// ---- step 2: target ----
const targetNamespaces = ref<string[]>([])

async function loadTargetNamespaces() {
  targetNamespaces.value = form.target.datasourceId && !targetIsEs.value ? await datasourceApi.namespaces(form.target.datasourceId) : []
}

async function onTargetDatasource() {
  form.target.namespace = ''
  targetNamespaces.value = []
  if (!form.target.datasourceId) return
  targetNamespaces.value = await datasourceApi.namespaces(form.target.datasourceId)
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
  loadingColumns.value = true
  try {
    for (const t of form.source.tables) {
      if (!columns.value[t.name]) {
        columns.value[t.name] = await datasourceApi.columns(form.source.datasourceId, form.source.namespace, t.name)
      }
    }
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
const HANDLED = {
  source: ['url', 'driver', 'username', 'password', 'table_path', 'table_list', 'query', 'where_condition', 'parallelism'],
  sink: ['url', 'driver', 'username', 'password', 'database', 'table', 'generate_sink_sql', 'enable_upsert', 'schema_save_mode',
    'data_save_mode', 'primary_keys', 'hosts', 'index', 'tablePrefix', 'tableSuffix'],
}
const sourceOptionDefs = ref<ConnectorOption[]>([])
const sinkOptionDefs = ref<ConnectorOption[]>([])
const optionsError = ref('')

async function loadOptionDefs() {
  optionsError.value = ''
  try {
    const sinkPlugin = targetIsEs.value ? 'Elasticsearch' : 'Jdbc'
    const [src, snk] = await Promise.all([connectorApi.options('source', 'Jdbc'), connectorApi.options('sink', sinkPlugin)])
    sourceOptionDefs.value = src.filter((o) => !HANDLED.source.includes(o.key) && !o.key.startsWith('auth.'))
    sinkOptionDefs.value = snk.filter((o) => !HANDLED.sink.includes(o.key) && !o.key.startsWith('auth.'))
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

/** The form as sent: blank optional strings become null so the server sees "not set". */
function payload(): WizardForm {
  const clean = (v?: string | null) => (v && v.trim() ? v.trim() : null)
  const opts = (o?: Record<string, string>) => Object.fromEntries(Object.entries(o ?? {}).filter(([, v]) => v?.trim()))
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
      })),
    },
    target: {
      ...form.target,
      namespace: targetIsEs.value ? null : clean(form.target.namespace),
      tableName: null,
      prefix: null,
      suffix: null,
      tableRule: cleanRule(form.target.tableRule),
    },
    sync:
      form.sync.mode === 'FULL'
        ? { mode: 'FULL', window: null, field: null, customCondition: null }
        : {
            mode: 'INCREMENTAL',
            window: form.sync.window,
            field: form.sync.window === 'CUSTOM' ? null : clean(form.sync.field),
            customCondition: form.sync.window === 'CUSTOM' ? clean(form.sync.customCondition) : null,
          },
    parallelism: form.parallelism,
    validation: form.validation,
    sourceOptions: opts(form.sourceOptions),
    sinkOptions: opts(form.sinkOptions),
    fieldRule: cleanRule(form.fieldRule),
  }
}

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
  for (let i = 0; i < steps.length - 1; i++) {
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
      step.value = steps.length - 1
      ElMessage.warning('预检查发现错误，修正后才能保存')
      return
    }
    const version = await jobApi.saveVersion(props.jobId, { form: payload(), params: toMap(params.value), remark: remark.value || undefined })
    ElMessage.success(`已保存为 v${version.versionNo}`)
    remark.value = ''
    snapshot.value = currentSnapshot()
    emit('saved', version.versionNo)
  } finally {
    saving.value = false
  }
}

// ---- unsaved changes ----
const currentSnapshot = () => JSON.stringify([payload(), toMap(params.value)])
/** Taken once the saved form has been loaded into the steps; null until then. */
const snapshot = ref<string | null>(null)
const dirty = computed(() => snapshot.value !== null && currentSnapshot() !== snapshot.value)
watch(dirty, (value) => emit('dirty', value))

// ---- navigation ----
function stepError(i: number): string | null {
  if (i === 0) {
    if (!form.source.datasourceId) return '请选择源数据源'
    if (!form.source.namespace) return '请选择库或 schema'
    if (form.source.tables.length === 0) return '请至少勾选一张表'
  }
  if (i === 1) {
    if (!form.target.datasourceId) return '请选择目标数据源'
    if (!targetIsEs.value && !form.target.namespace) return '请选择目标库或 schema'
    const names = tableNamesProblem(form, targetIsEs.value)
    if (names) return names
  }
  if (i === 2) {
    const fields = fieldNamesProblem(form, columns.value)
    if (fields) return fields
    const paramError = paramsProblem(params.value)
    if (paramError) return paramError
  }
  if (i === 2 && form.sync.mode === 'INCREMENTAL') {
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

onMounted(async () => {
  const initialStep = step.value
  step.value = 0
  // A job may only use its creator's datasources.
  datasources.value = (await datasourceApi.list({ size: 200, ownerId: props.ownerId })).items
  if (form.source.datasourceId) {
    await loadSourceNamespaces()
    await loadSourceTables()
  }
  if (form.target.datasourceId && !targetIsEs.value) {
    targetNamespaces.value = await datasourceApi.namespaces(form.target.datasourceId)
  }
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
        <p>选择数据源和库，再勾选需要同步的表。</p>
      </div>
      <el-form label-position="top" class="source-form">
        <el-form-item label="源数据源">
          <el-select v-model="form.source.datasourceId" placeholder="MySQL 或 PostgreSQL" filterable @change="onSourceDatasource">
            <el-option v-for="d in sourceDatasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库 / schema">
          <el-select v-model="form.source.namespace" placeholder="选择库或 schema" filterable @change="onSourceNamespace" @visible-change="(open: boolean) => open && loadSourceNamespaces()">
            <el-option v-for="n in sourceNamespaces" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="form.source.namespace" class="tables">
        <div class="tables-heading">
          <div>
            <div class="block-title">选择需要同步的表</div>
            <div class="tip">勾选会保存到任务配置，筛选不会影响已选择的表。</div>
          </div>
          <div class="table-tags">
            <el-tag :type="selected.length ? 'primary' : 'info'" disable-transitions>已选 {{ selected.length }} / {{ sourceTables.length }} 张表</el-tag>
            <el-tag v-if="tablesWithoutPk.length" type="warning" disable-transitions>{{ tablesWithoutPk.length }} 张没有主键</el-tag>
          </div>
        </div>
        <div class="tables-bar">
          <el-input v-model="tableFilter" placeholder="筛选表名，支持正则，如 ^order_" clearable class="table-filter" />
          <span class="tip bar-hint">输入关键字或正则表达式快速筛选</span>
          <el-button :icon="Refresh" :loading="loadingTables" class="refresh" @click="refreshTables">刷新</el-button>
        </div>
        <el-table ref="tableRef" v-loading="loadingTables" :data="filteredTables" row-key="name" max-height="440" class="table-box" @select="onSelect" @select-all="onSelectAll">
          <el-table-column type="selection" width="48" :reserve-selection="false" />
          <el-table-column prop="name" label="表名" min-width="280" />
          <el-table-column label="主键" min-width="180">
            <template #default="{ row }">
              <span v-if="row.primaryKeys.length">{{ row.primaryKeys.join(', ') }}</span>
              <el-tag v-else size="small" type="warning">无主键</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </section>

    <!-- ② 目标 -->
    <section v-show="step === 1">
      <div class="step-heading">
        <h3>配置目标与写入方式</h3>
        <p>指定数据写入的位置，以及目标表已存在时的处理方式。</p>
      </div>
      <el-form label-width="130px" class="narrow form-card">
        <el-form-item label="目标数据源">
          <el-select v-model="form.target.datasourceId" placeholder="MySQL、PostgreSQL 或 Elasticsearch" filterable class="w320" @change="onTargetDatasource">
            <el-option v-for="d in datasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.target.datasourceId && !targetIsEs" label="库 / schema">
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
        <el-form-item label="写入方式">
          <el-radio-group v-model="form.target.writeMode" @change="targetTouched = true">
            <el-radio value="UPSERT">按主键 upsert</el-radio>
            <el-radio value="APPEND">追加</el-radio>
          </el-radio-group>
          <div v-if="form.target.writeMode === 'UPSERT' && tablesWithoutPk.length" class="tip full warn">
            {{ tablesWithoutPk.join('，') }} 没有主键，这些表只能按追加写入
          </div>
        </el-form-item>
        <el-form-item label="目标已有数据">
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
      <TableMapping v-if="form.target.datasourceId && form.source.tables.length" :form="form" :es="targetIsEs" />
    </section>

    <!-- ③ 字段与同步方式 -->
    <section v-show="step === 2">
      <div class="step-heading">
        <h3>设置字段与同步方式</h3>
        <p>选择全量或增量同步，并按需调整每张表的字段和过滤条件。</p>
      </div>
      <el-form label-width="96px" class="form-card">
        <el-form-item label="同步方式">
          <el-radio-group v-model="form.sync.mode">
            <el-radio value="FULL">全量</el-radio>
            <el-radio value="INCREMENTAL">按时间增量</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="form.sync.mode === 'INCREMENTAL'">
          <el-form-item label="时间窗口">
            <el-radio-group v-model="form.sync.window">
              <el-radio value="DAY">按天</el-radio>
              <el-radio value="HOUR">按小时</el-radio>
              <el-radio value="CUSTOM">自定义条件</el-radio>
            </el-radio-group>
            <div class="tip full">
              <template v-if="form.sync.window === 'DAY'">每次同步计划时间的前一天：[前一天 00:00, 当天 00:00)。补数和重跑按各自的计划时间计算。</template>
              <template v-else-if="form.sync.window === 'HOUR'">每次同步计划时间的上一个小时：[上一小时整点, 本小时整点)。</template>
              <template v-else>自己写 where 条件（不含 where），对所有表生效，可以使用变量，例如 <code>id &gt; ${last_id}</code>。</template>
            </div>
          </el-form-item>
          <el-form-item v-if="form.sync.window !== 'CUSTOM'" label="增量字段">
            <el-select v-model="form.sync.field" placeholder="所有表共用的时间字段" clearable filterable class="w320">
              <el-option v-for="f in commonTimeFields" :key="f" :label="f" :value="f" />
            </el-select>
            <span class="tip">只列出所有选中表都有的时间字段；个别表可以在下面的字段映射里单独指定</span>
          </el-form-item>
          <el-form-item v-else label="增量条件">
            <el-input v-model="form.sync.customCondition" type="textarea" :rows="2" class="code" placeholder="updated_at >= '${start}'" />
          </el-form-item>
        </template>
      </el-form>

      <div class="section-title">字段映射 <span class="tip">每张表同步哪些字段、写成什么名字；过滤条件和增量字段也在这里按表设置</span></div>
      <FieldMapping :form="form" :columns="columns" :loading="loadingColumns" :es="targetIsEs" />

      <div class="section-title">任务参数 <span class="tip">过滤条件和自定义条件里可以用 <code>${名称}</code> 引用</span></div>
      <ParamsEditor v-model="params" />
    </section>

    <!-- ④ 并行度与校验 -->
    <section v-show="step === 3">
      <div class="step-heading">
        <h3>调整运行与校验</h3>
        <p>根据数据量设置并行度，并选择是否进行行数校验。</p>
      </div>
      <el-form label-width="96px" class="form-card">
        <el-form-item label="并行度">
          <el-radio-group v-model="form.parallelism">
            <el-radio-button v-for="p in [1, 2, 4, 8]" :key="p" :value="p">{{ p }}</el-radio-button>
          </el-radio-group>
          <div class="tip full">单表时按主键自动切分、并行读取；多表时多张表同时读。数据量不大时用 1 即可，并行度越高对源库压力越大。失败重试和超时在"调度"页配置。</div>
        </el-form-item>
        <el-form-item label="行数校验">
          <el-switch v-model="form.validation" />
          <div class="tip full">运行成功后按表比对源端和目标端的行数。只在"全量 + 清空后写入"或"按时间增量"时准确，其他情况会自动跳过并说明原因。</div>
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
            <AdvancedOptions v-model="form.sourceOptions!" title="读取（来源）" :defs="sourceOptionDefs" type="source" plugin="Jdbc" />
            <AdvancedOptions
              v-model="form.sinkOptions!"
              title="写入（目标）"
              :defs="sinkOptionDefs"
              type="sink"
              :plugin="targetIsEs ? 'Elasticsearch' : 'Jdbc'"
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

.tables {
  margin-top: 20px;
  padding: 18px 20px 20px;
  border: 1px solid #e8edf4;
  border-radius: 10px;
}

.tables-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.block-title {
  margin-bottom: 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.table-tags {
  display: flex;
  flex: none;
  gap: 8px;
}

.tables-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  flex-wrap: wrap;
  background: #f8fafc;
  border-radius: 8px;
}

.table-filter {
  width: 360px;
  max-width: 100%;
}

.bar-hint {
  margin: 0;
}

.refresh {
  margin-left: auto;
}

.table-box {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}

.table-box :deep(th.el-table__cell) {
  background: #f8fafc;
}

/* the box's own border closes the table */
.table-box :deep(.el-table__body tr:last-child > td.el-table__cell) {
  border-bottom: none;
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

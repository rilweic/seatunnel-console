<script setup lang="ts">
/**
 * The job wizard (docs/design.md §7.2): collects a WizardForm in five steps. The HOCON is generated
 * and checked by the server; this component never builds config itself.
 */
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'

import {
  connectorApi,
  datasourceApi,
  jobApi,
  type ColumnMeta,
  type ConnectorOption,
  type DatasourceView,
  type PrecheckResult,
  type TableMeta,
  type WizardForm,
  type WizardTable,
} from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'

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
    target: { datasourceId: '', namespace: '', tableName: '', prefix: '', suffix: '', schemaMode: 'CREATE', dataMode: 'KEEP', writeMode: 'UPSERT' },
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

let restoring = false
function restoreSelection() {
  restoring = true
  tableRef.value?.clearSelection()
  for (const t of filteredTables.value) {
    if (selected.value.includes(t.name)) tableRef.value?.toggleRowSelection(t, true)
  }
  restoring = false
}

/** Selection survives filtering: rows hidden by the filter keep their state. */
function onSelectionChange(rows: TableMeta[]) {
  if (restoring) return
  const visible = new Set(filteredTables.value.map((t) => t.name))
  const kept = selected.value.filter((n) => !visible.has(n))
  selected.value = [...kept, ...rows.map((r) => r.name)].sort()
}

watch(tableFilter, () => nextTick(restoreSelection))

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

const single = computed(() => form.source.tables.length === 1)
const targetPreview = computed(() =>
  form.source.tables.slice(0, 3).map((t) =>
    single.value && form.target.tableName?.trim() ? form.target.tableName.trim() : `${form.target.prefix ?? ''}${t.name}${form.target.suffix ?? ''}`,
  ),
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

function timeFields(table: string) {
  return (columns.value[table] ?? []).filter((c) => c.temporal)
}

function columnSelection(t: WizardTable): string[] {
  return t.columns && t.columns.length ? t.columns : (columns.value[t.name] ?? []).map((c) => c.name)
}

function setColumns(t: WizardTable, names: string[]) {
  const all = (columns.value[t.name] ?? []).map((c) => c.name)
  // keep table order; "all columns" is stored as null so new columns are picked up too
  t.columns = names.length === all.length ? null : all.filter((n) => names.includes(n))
}

function tableSummary(t: WizardTable) {
  const total = columns.value[t.name]?.length
  const parts = [t.columns && t.columns.length ? `${t.columns.length}/${total ?? '?'} 个字段` : '全部字段']
  if (t.filter?.trim()) parts.push(`过滤：${t.filter.trim()}`)
  if (form.sync.mode === 'INCREMENTAL' && form.sync.window !== 'CUSTOM') {
    parts.push(`增量字段：${t.incrementalField?.trim() || form.sync.field || '未选'}`)
  }
  return parts.join(' · ')
}

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
      })),
    },
    target: {
      ...form.target,
      namespace: targetIsEs.value ? null : clean(form.target.namespace),
      tableName: single.value ? clean(form.target.tableName) : null,
      prefix: clean(form.target.prefix),
      suffix: clean(form.target.suffix),
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
    <el-steps :active="step" finish-status="success" simple class="steps">
      <el-step v-for="(title, i) in steps" :key="title" :title="title" class="step" @click="go(i)" />
    </el-steps>

    <!-- ① 来源 -->
    <section v-show="step === 0">
      <el-form label-width="96px" class="narrow">
        <el-form-item label="源数据源">
          <el-select v-model="form.source.datasourceId" placeholder="MySQL 或 PostgreSQL" filterable class="w320" @change="onSourceDatasource">
            <el-option v-for="d in sourceDatasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="库 / schema">
          <el-select v-model="form.source.namespace" placeholder="选择库或 schema" filterable class="w320" @change="onSourceNamespace">
            <el-option v-for="n in sourceNamespaces" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
      </el-form>
      <div v-if="form.source.namespace" class="tables">
        <div class="tables-bar">
          <el-input v-model="tableFilter" placeholder="筛选表名，支持正则，如 ^order_" clearable class="w320" />
          <span class="tip">已选 {{ selected.length }} 张表<template v-if="tablesWithoutPk.length">，其中 {{ tablesWithoutPk.length }} 张没有主键</template>。正则只用来筛选列表，保存的是勾选的表。</span>
        </div>
        <el-table ref="tableRef" v-loading="loadingTables" :data="filteredTables" row-key="name" max-height="420" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="48" :reserve-selection="false" />
          <el-table-column prop="name" label="表名" min-width="240" />
          <el-table-column label="主键" min-width="200">
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
      <el-form label-width="130px" class="narrow">
        <el-form-item label="目标数据源">
          <el-select v-model="form.target.datasourceId" placeholder="MySQL、PostgreSQL 或 Elasticsearch" filterable class="w320" @change="onTargetDatasource">
            <el-option v-for="d in datasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.target.datasourceId && !targetIsEs" label="库 / schema">
          <el-select v-model="form.target.namespace" placeholder="必须已存在，不会自动建库" filterable class="w320">
            <el-option v-for="n in targetNamespaces" :key="n" :label="n" :value="n" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="single" :label="targetIsEs ? '索引名' : '目标表名'">
          <el-input v-model="form.target.tableName" :placeholder="`默认 ${targetIsEs ? form.source.tables[0]?.name.toLowerCase() : form.source.tables[0]?.name}`" class="w320" />
        </el-form-item>
        <el-form-item v-else :label="targetIsEs ? '索引名' : '目标表名'">
          <el-input v-model="form.target.prefix" placeholder="前缀，如 ods_" class="w140" />
          <span class="plus">+ 源表名 +</span>
          <el-input v-model="form.target.suffix" placeholder="后缀（可选）" class="w140" />
          <div class="tip full">例如：{{ targetPreview.join('，') }}{{ form.source.tables.length > 3 ? ' …' : '' }}<template v-if="targetIsEs">（ES 索引名必须全部小写）</template></div>
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
    </section>

    <!-- ③ 字段与同步方式 -->
    <section v-show="step === 2" v-loading="loadingColumns">
      <el-form label-width="96px">
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
            <span class="tip">只列出所有选中表都有的时间字段；个别表可以在下面单独指定</span>
          </el-form-item>
          <el-form-item v-else label="增量条件">
            <el-input v-model="form.sync.customCondition" type="textarea" :rows="2" class="code" placeholder="updated_at >= '${start}'" />
          </el-form-item>
        </template>
      </el-form>

      <div class="section-title">表设置 <span class="tip">默认同步全部字段；展开可以去掉字段、加固定过滤条件、单独指定增量字段</span></div>
      <el-collapse class="table-collapse">
        <el-collapse-item v-for="t in form.source.tables" :key="t.name" :name="t.name">
          <template #title>
            <span class="t-name">{{ t.name }}</span>
            <span class="tip t-summary">{{ tableSummary(t) }}</span>
          </template>
          <el-form label-width="96px" class="table-form">
            <el-form-item label="字段">
              <el-checkbox-group :model-value="columnSelection(t)" class="columns" @update:model-value="(v: unknown) => setColumns(t, v as string[])">
                <el-checkbox v-for="c in columns[t.name] ?? []" :key="c.name" :value="c.name">
                  {{ c.name }} <span class="col-type">{{ c.type }}<template v-if="c.primaryKey"> · 主键</template></span>
                </el-checkbox>
              </el-checkbox-group>
            </el-form-item>
            <el-form-item label="过滤条件">
              <el-input v-model="t.filter" placeholder="可选，不含 where，如 status = 1；可以使用变量" class="code" />
            </el-form-item>
            <el-form-item v-if="form.sync.mode === 'INCREMENTAL' && form.sync.window !== 'CUSTOM'" label="增量字段">
              <el-select v-model="t.incrementalField" :placeholder="form.sync.field ? `使用共用字段 ${form.sync.field}` : '选择时间字段'" clearable class="w320">
                <el-option v-for="c in timeFields(t.name)" :key="c.name" :label="`${c.name} (${c.type})`" :value="c.name" />
              </el-select>
            </el-form-item>
          </el-form>
        </el-collapse-item>
      </el-collapse>

      <div class="section-title">任务参数 <span class="tip">过滤条件和自定义条件里可以用 <code>${名称}</code> 引用</span></div>
      <ParamsEditor v-model="params" />
    </section>

    <!-- ④ 并行度与校验 -->
    <section v-show="step === 3">
      <el-form label-width="96px">
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
            高级参数<span class="tip">（{{ advancedCount ? `已设置 ${advancedCount} 项` : '一般不需要修改' }}，定义来自 SeaTunnel 集群）</span>
          </template>
          <el-alert v-if="optionsError" :title="optionsError" type="warning" :closable="false" />
          <template v-else>
            <div class="opt-group">来源（Jdbc source）</div>
            <div v-for="o in sourceOptionDefs" :key="'s' + o.key" class="opt">
              <div class="opt-key">{{ o.key }}</div>
              <el-input v-model="form.sourceOptions![o.key]" :placeholder="o.defaultValue != null ? `默认 ${o.defaultValue}` : ''" size="small" class="opt-input" />
              <div class="opt-desc">{{ o.description }}</div>
            </div>
            <div class="opt-group">目标（{{ targetIsEs ? 'Elasticsearch' : 'Jdbc' }} sink）</div>
            <div v-for="o in sinkOptionDefs" :key="'t' + o.key" class="opt">
              <div class="opt-key">{{ o.key }}</div>
              <el-input v-model="form.sinkOptions![o.key]" :placeholder="o.defaultValue != null ? `默认 ${o.defaultValue}` : ''" size="small" class="opt-input" />
              <div class="opt-desc">{{ o.description }}</div>
            </div>
          </template>
        </el-collapse-item>
      </el-collapse>
    </section>

    <!-- ⑤ 预检查与预览 -->
    <section v-show="step === 4" v-loading="checking">
      <div class="check-bar">
        <span class="section-title inline">预检查</span>
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
        <el-button v-if="step < steps.length - 1" @click="go(step + 1)">下一步</el-button>
      </div>
      <template v-if="!readonly">
        <span v-if="dirty" class="tip warn">有未保存的修改</span>
        <el-input v-model="remark" placeholder="版本备注（可选）" maxlength="512" class="w320" />
        <el-button type="primary" :loading="saving || checking" :disabled="!dirty && initialForm !== null" @click="save">保存为新版本</el-button>
      </template>
    </div>
  </div>
</template>

<style scoped>
.steps {
  margin: 4px 0 20px;
}

.step {
  cursor: pointer;
}

.narrow {
  max-width: 820px;
}

.w320 {
  width: 320px;
}

.w140 {
  width: 140px;
}

.plus {
  margin: 0 8px;
  color: var(--el-text-color-secondary);
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

.tables-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
  flex-wrap: wrap;
}

.section-title {
  margin: 20px 0 8px;
  font-weight: 500;
}

.section-title.inline {
  margin: 0;
}

.table-collapse :deep(.el-collapse-item__header) {
  gap: 12px;
}

.t-name {
  margin-right: 12px;
  font-weight: 500;
}

.t-summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-form {
  padding-top: 8px;
}

.columns {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 8px;
}

.col-type {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.code :deep(input),
.code :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.advanced {
  margin-top: 8px;
}

.opt-group {
  margin: 12px 0 6px;
  font-weight: 500;
}

.opt {
  display: grid;
  grid-template-columns: 240px 220px 1fr;
  gap: 12px;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.opt-key {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
}

.opt-desc {
  color: var(--el-text-color-secondary);
  font-size: 12px;
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
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.nav-steps {
  margin-right: auto;
}

@media (max-width: 900px) {
  .opt {
    grid-template-columns: 1fr;
  }
}
</style>

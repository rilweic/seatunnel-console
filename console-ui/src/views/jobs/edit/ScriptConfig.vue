<script setup lang="ts">
/** Script-mode config: HOCON, job parameters and row-count validation, saved as a new version. */
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { datasourceApi, jobApi, type DatasourceView, type JobView, type ValidationResult, type ValidationSpec, type VersionDetail } from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import ValidationTag from '@/components/ValidationTag.vue'

import TransformInsertDialog from './TransformInsertDialog.vue'
import { nowText, paramsProblem, toMap, toRows } from '@/utils/params'
import { useDatasourceTypes } from '@/composables/useDatasourceTypes'

const props = defineProps<{ job: JobView; version: VersionDetail | null }>()
const emit = defineEmits<{ saved: []; dirty: [dirty: boolean] }>()

const STARTER = `env {
  job.mode = "BATCH"
  parallelism = 1
}

source {
  Jdbc {
    # 用上方「插入数据源」按钮插入，连接信息在运行时由 console 注入
    datasource_id = ""
    query = "select * from your_table"
    plugin_output = "src"
  }
}

sink {
  Console {
    plugin_input = ["src"]
  }
}
`

function emptyValidation(): ValidationSpec {
  return { enabled: false, source: { mode: 'AUTO' }, target: { mode: 'SQL' }, failOnMismatch: false }
}

const hocon = ref('')
const savedHocon = ref('')
const jobParams = ref<ParamRow[]>([])
const savedParams = ref('{}')
const validation = ref<ValidationSpec>(emptyValidation())
const savedValidation = ref('null')
const remark = ref('')
const saving = ref(false)

/**
 * A wizard job turned into a script keeps the wizard's per-table validation, which has no single
 * source and target; it is kept as is (or replaced) rather than edited here.
 */
const perTable = computed(() => !!validation.value.tables?.length)

function replacePerTable() {
  validation.value = { ...emptyValidation(), enabled: true }
}

/** Saved only when enabled, so turning it off removes it from the next version. */
function validationPayload(): ValidationSpec | null {
  return validation.value.enabled ? validation.value : null
}

function reset(version: VersionDetail | null) {
  savedHocon.value = version?.hocon ?? ''
  hocon.value = version?.hocon ?? STARTER
  jobParams.value = toRows(version?.params)
  savedParams.value = JSON.stringify(toMap(jobParams.value))
  validation.value = version?.validation ?? emptyValidation()
  savedValidation.value = JSON.stringify(validationPayload())
}
reset(props.version)

const dirty = computed(
  () =>
    hocon.value !== (savedHocon.value || STARTER) ||
    JSON.stringify(toMap(jobParams.value)) !== savedParams.value ||
    JSON.stringify(validationPayload()) !== savedValidation.value,
)
watch(dirty, (value) => emit('dirty', value))

const datasources = ref<DatasourceView[]>([])
const { capabilities, label: typeLabel } = useDatasourceTypes()
/** Datasources a validation side can count with a query (SQL mode) or by index (ES mode). */
const sqlCountDatasources = computed(() => datasources.value.filter((d) => capabilities(d.type)?.countBy === 'SQL'))
const indexCountDatasources = computed(() => datasources.value.filter((d) => capabilities(d.type)?.countBy === 'INDEX'))
const pickedDatasource = ref('')
const editor = ref<{ textarea?: HTMLTextAreaElement } | null>(null)

const transformOpen = ref(false)

/**
 * Inserts a transform template: at the cursor when the script has a transform block, else as a
 * new block in front of the sink.
 */
async function insertTransform(snippet: string) {
  const textarea = editor.value?.textarea
  if (/^\s*transform\s*\{/m.test(hocon.value)) {
    const at = textarea ? textarea.selectionStart : hocon.value.length
    hocon.value = hocon.value.slice(0, at) + snippet + '\n' + hocon.value.slice(at)
  } else {
    const block = `transform {\n${snippet}\n}\n\n`
    const sink = hocon.value.search(/^\s*sink\s*\{/m)
    hocon.value = sink >= 0 ? hocon.value.slice(0, sink) + block + hocon.value.slice(sink) : hocon.value + '\n' + block
  }
  await nextTick()
  textarea?.focus()
}

/** Inserts the datasource reference at the cursor, or fills an empty datasource_id = "". */
async function insertDatasource() {
  const ds = datasources.value.find((d) => d.id === pickedDatasource.value)
  if (!ds) return
  const line = `datasource_id = "${ds.id}"  # ${ds.name}`
  if (hocon.value.includes('datasource_id = ""')) {
    hocon.value = hocon.value.replace('datasource_id = ""', line)
    return
  }
  const textarea = editor.value?.textarea
  const at = textarea ? textarea.selectionStart : hocon.value.length
  hocon.value = hocon.value.slice(0, at) + line + hocon.value.slice(at)
  await nextTick()
  textarea?.focus()
}

const validationTest = ref<{ loading: boolean; result: ValidationResult | null }>({ loading: false, result: null })

async function testValidation() {
  validationTest.value = { loading: true, result: null }
  try {
    validationTest.value.result = (
      await jobApi.testValidation(props.job.id, { hocon: hocon.value, params: toMap(jobParams.value), validation: validation.value })
    ).result
  } finally {
    validationTest.value.loading = false
  }
}

const scriptCheck = ref<{ loading: boolean; checks: { level: 'ERROR' | 'WARN' | 'INFO'; message: string }[] | null }>({
  loading: false,
  checks: null,
})

/** Runs every Jdbc source query of the editor's content once under where 1 = 0. */
async function checkScript() {
  scriptCheck.value = { loading: true, checks: null }
  try {
    scriptCheck.value.checks = await jobApi.checkScript(props.job.id, { hocon: hocon.value, params: toMap(jobParams.value) })
  } finally {
    scriptCheck.value.loading = false
  }
}

const preview = ref<{
  visible: boolean
  loading: boolean
  scheduledTime: string
  result: { variables: Record<string, string>; config: string; unresolved: string[] } | null
}>({ visible: false, loading: false, scheduledTime: '', result: null })

async function openPreview() {
  preview.value = { visible: true, loading: false, scheduledTime: preview.value.scheduledTime || nowText(), result: null }
  await loadPreview()
}

async function loadPreview() {
  preview.value.loading = true
  try {
    preview.value.result = await jobApi.preview(props.job.id, {
      hocon: hocon.value,
      params: toMap(jobParams.value),
      scheduledTime: preview.value.scheduledTime || undefined,
    })
  } catch {
    preview.value.result = null
  } finally {
    preview.value.loading = false
  }
}

async function save() {
  const paramError = paramsProblem(jobParams.value)
  if (paramError) {
    ElMessage.warning(paramError)
    return
  }
  saving.value = true
  try {
    const version = await jobApi.saveVersion(props.job.id, {
      hocon: hocon.value,
      params: toMap(jobParams.value),
      validation: validationPayload(),
      remark: remark.value || undefined,
    })
    ElMessage.success(`已保存为 v${version.versionNo}`)
    remark.value = ''
    reset(version)
    emit('saved')
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  // A job may only use its creator's datasources, whoever is editing it.
  datasources.value = (await datasourceApi.list({ size: 200, ownerId: props.job.ownerId })).items
})
</script>

<template>
  <div class="editor-bar">
    <el-select v-model="pickedDatasource" placeholder="选择数据源" filterable class="ds-select">
      <el-option v-for="d in datasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
    </el-select>
    <el-button :disabled="!pickedDatasource" @click="insertDatasource">插入数据源</el-button>
    <el-button @click="transformOpen = true">插入 transform</el-button>
    <span class="tip">在 source / sink 的连接器里写 <code>datasource_id</code>，密码不会出现在配置中。目前支持 Jdbc、Elasticsearch 和 Http 连接器。</span>
  </div>
  <el-input ref="editor" v-model="hocon" type="textarea" :rows="24" class="code" spellcheck="false" />
  <TransformInsertDialog v-model="transformOpen" @insert="insertTransform" />

  <div class="section-title">
    任务参数
    <span class="tip">在配置里用 <code>${名称}</code> 引用，字符串里面也可以。优先级：启动参数 &gt; 任务参数 &gt; 项目参数 &gt; 内置变量。
      内置变量：<code>${system.biz.date}</code>（计划时间前一天，yyyyMMdd）、<code>${system.biz.curdate}</code>、<code>${system.datetime}</code>；
      时间表达式：<code>$[yyyyMMdd-1]</code>、<code>$[HHmmss-1/24]</code>、<code>$[add_months(yyyyMMdd,-1)]</code>、<code>$[month_first_day(yyyy-MM-dd,-1)]</code> 等，以计划时间为基准</span>
  </div>
  <ParamsEditor v-model="jobParams" />

  <div class="section-title">
    行数校验
    <el-switch v-model="validation.enabled" class="inline-switch" />
    <span class="tip">运行成功后，console 分别在源端和目标端执行一次计数并比较。SQL 里可以使用变量。
      只有能单独统计出这次写入的数据时才准确，比如目标表按 <code>${dt}</code> 分区，或者写入前会清空目标表。</span>
  </div>
  <div v-if="validation.enabled && perTable" class="per-table">
    <div>
      按表校验，由向导生成：{{ validation.tables!.map((t) => t.name).join('、') }}。每张表分别在源端和目标端计数比较，保存时原样保留。
    </div>
    <div class="per-table-bar">
      <el-checkbox v-model="validation.failOnMismatch">不一致时把运行标记为失败</el-checkbox>
      <el-button size="small" @click="replacePerTable">改为自定义校验</el-button>
    </div>
  </div>
  <div v-else-if="validation.enabled" class="validation">
    <div class="side">
      <div class="side-title">源端</div>
      <el-radio-group v-model="validation.source.mode" size="small">
        <el-radio-button value="AUTO">自动</el-radio-button>
        <el-radio-button value="SQL">自定义 SQL</el-radio-button>
      </el-radio-group>
      <div v-if="validation.source.mode === 'AUTO'" class="tip">
        从唯一的 Jdbc source 推导：有 <code>query</code> 时执行 <code>select count(*) from (query)</code>，否则统计 <code>table_path</code>，再加上 <code>where_condition</code>。
      </div>
      <template v-else>
        <el-select v-model="validation.source.datasourceId" placeholder="数据源" filterable class="side-ds">
          <el-option v-for="d in sqlCountDatasources" :key="d.id" :label="`${d.name} (${typeLabel(d.type)})`" :value="d.id" />
        </el-select>
        <el-input v-model="validation.source.sql" type="textarea" :rows="3" class="code" placeholder="select count(*) from orders where dt = '${dt}'" />
      </template>
    </div>
    <div class="side">
      <div class="side-title">目标端</div>
      <el-radio-group v-model="validation.target.mode" size="small">
        <el-radio-button value="SQL">SQL</el-radio-button>
        <el-radio-button value="ES">Elasticsearch</el-radio-button>
      </el-radio-group>
      <template v-if="validation.target.mode === 'SQL'">
        <el-select v-model="validation.target.datasourceId" placeholder="数据源" filterable class="side-ds">
          <el-option v-for="d in sqlCountDatasources" :key="d.id" :label="`${d.name} (${typeLabel(d.type)})`" :value="d.id" />
        </el-select>
        <el-input v-model="validation.target.sql" type="textarea" :rows="3" class="code" placeholder="select count(*) from ods_orders where dt = '${dt}'" />
      </template>
      <template v-else>
        <el-select v-model="validation.target.datasourceId" placeholder="数据源" filterable class="side-ds">
          <el-option v-for="d in indexCountDatasources" :key="d.id" :label="d.name" :value="d.id" />
        </el-select>
        <el-input v-model="validation.target.index" placeholder="索引，如 orders_${dt}" class="side-ds" />
        <el-input v-model="validation.target.query" type="textarea" :rows="3" class="code" placeholder='可选的 query，如 {"term": {"dt": "${dt}"}}' />
      </template>
    </div>
    <div class="validation-bar">
      <el-checkbox v-model="validation.failOnMismatch">不一致时把运行标记为失败</el-checkbox>
      <el-button size="small" :loading="validationTest.loading" @click="testValidation">测试校验（现在执行一次计数）</el-button>
      <template v-if="validationTest.result">
        <ValidationTag :instance="{ validationStatus: validationTest.result.status, sourceCount: validationTest.result.sourceCount, targetCount: validationTest.result.targetCount, validationMessage: validationTest.result.message }" />
        <span class="tip">{{ validationTest.result.message }}</span>
      </template>
    </div>
    <div v-if="validationTest.result" class="tip">
      <div v-if="validationTest.result.sourceQuery">源端：<code>{{ validationTest.result.sourceQuery }}</code></div>
      <div v-if="validationTest.result.targetQuery">目标端：<code>{{ validationTest.result.targetQuery }}</code></div>
    </div>
  </div>

  <ul v-if="scriptCheck.checks" class="script-checks">
    <li v-for="(c, i) in scriptCheck.checks" :key="i">
      <el-tag :type="c.level === 'ERROR' ? 'danger' : c.level === 'WARN' ? 'warning' : 'success'">
        {{ c.level === 'ERROR' ? '错误' : c.level === 'WARN' ? '警告' : '通过' }}
      </el-tag>
      {{ c.message }}
    </li>
    <li class="tip">在数据源上用 <code>where 1 = 0</code> 执行一遍每个 Jdbc source 的查询，提前发现表名、字段、语法错误；不会读取数据。</li>
  </ul>

  <div class="save-bar">
    <el-button @click="openPreview">预览渲染结果</el-button>
    <el-button :loading="scriptCheck.loading" @click="checkScript">检查配置</el-button>
    <span class="spacer" />
    <span v-if="dirty" class="unsaved">有未保存的修改</span>
    <el-input v-model="remark" placeholder="版本备注（可选）" maxlength="512" class="remark" />
    <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存为新版本</el-button>
  </div>

  <el-dialog v-model="preview.visible" title="预览渲染结果（按编辑器中的内容，密码已打码）" width="860px">
    <div class="preview-bar">
      <span>计划时间</span>
      <el-date-picker v-model="preview.scheduledTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :clearable="false" />
      <el-button :loading="preview.loading" @click="loadPreview">重新渲染</el-button>
    </div>
    <template v-if="preview.result">
      <el-alert v-if="preview.result.unresolved.length" type="warning" :closable="false" show-icon class="preview-alert"
        :title="`这些 \${...} 不是 console 变量，会原样交给 HOCON 解析（多半是拼写错误）：${preview.result.unresolved.join('，')}`" />
      <el-table :data="Object.entries(preview.result.variables).map(([k, v]) => ({ k, v }))" max-height="220">
        <el-table-column prop="k" label="变量" width="260" />
        <el-table-column prop="v" label="值" />
      </el-table>
      <pre class="code-view preview-config">{{ preview.result.config }}</pre>
    </template>
  </el-dialog>
</template>

<style scoped>
.inline-switch {
  margin-left: 12px;
}

.validation {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 1100px) {
  .validation {
    grid-template-columns: 1fr;
  }
}

.per-table {
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
}

.per-table-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.side {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}

.side-title {
  font-weight: 500;
}

.side-ds {
  width: 320px;
}

.validation-bar {
  grid-column: 1 / -1;
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.script-checks {
  list-style: none;
  padding: 0;
  margin: 16px 0 0;
}

.script-checks li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 4px 0;
  font-size: 13px;
}

.section-title {
  margin: 16px 0 8px;
  font-weight: 500;
}

.section-title .tip {
  display: block;
  margin-top: 4px;
  font-weight: normal;
  line-height: 1.6;
}

.preview-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.preview-alert {
  margin-bottom: 12px;
}

.preview-config {
  margin-top: 12px;
}

.editor-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 0 0 12px;
}

.save-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.spacer {
  flex: 1;
}

.ds-select {
  width: 260px;
}

.remark {
  width: 320px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.unsaved {
  color: var(--el-color-warning);
  font-size: 13px;
}

.code :deep(textarea),
.code-view {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.5;
}

.code-view {
  margin: 0;
  padding: 12px;
  max-height: 480px;
  overflow: auto;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  white-space: pre-wrap;
}
</style>

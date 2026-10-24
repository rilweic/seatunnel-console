<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import {
  backfillApi,
  datasourceApi,
  instanceApi,
  jobApi,
  scheduleApi,
  type BackfillPlan,
  type BackfillView,
  type DatasourceView,
  type InstanceView,
  type JobView,
  type ScheduleForm,
  type ScheduleView,
  type ValidationResult,
  type ValidationSpec,
  type VersionSummary,
  type WizardForm,
} from '@/api/console'
import JobWizard from '@/components/JobWizard.vue'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import ValidationTag from '@/components/ValidationTag.vue'
import VersionDiff from '@/components/VersionDiff.vue'
import { formatDuration, formatNumber, formatTime, statusTag, statusText, triggerText } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const jobId = route.params.id as string

const STARTER = `env {
  job.mode = "BATCH"
  parallelism = 1
}

source {
  Jdbc {
    # 用下方「插入数据源」按钮插入，连接信息在运行时由 console 注入
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

const job = ref<JobView | null>(null)
const isWizard = computed(() => job.value?.editMode === 'FORM')
const currentForm = ref<WizardForm | null>(null)
/** Remounts the wizard after a save, so it starts from the saved form. */
const wizardKey = ref(0)
const hocon = ref('')
const savedHocon = ref('')
const jobParams = ref<ParamRow[]>([])
const savedParams = ref('{}')

function emptyValidation(): ValidationSpec {
  return { enabled: false, source: { mode: 'AUTO' }, target: { mode: 'SQL' }, failOnMismatch: false }
}
const validation = ref<ValidationSpec>(emptyValidation())
const savedValidation = ref('null')
const validationTest = ref<{ loading: boolean; result: ValidationResult | null }>({ loading: false, result: null })

/** Saved only when enabled, so turning it off removes it from the next version. */
function validationPayload(): ValidationSpec | null {
  return validation.value.enabled ? validation.value : null
}

async function testValidation() {
  validationTest.value = { loading: true, result: null }
  try {
    validationTest.value.result = (
      await jobApi.testValidation(jobId, { hocon: hocon.value, params: toMap(jobParams.value), validation: validation.value })
    ).result
  } finally {
    validationTest.value.loading = false
  }
}
const remark = ref('')
const saving = ref(false)
const running = ref(false)
const tab = ref((route.query.tab as string) || 'config')

const datasources = ref<DatasourceView[]>([])
const pickedDatasource = ref('')
const editor = ref<{ textarea?: HTMLTextAreaElement } | null>(null)

const versions = ref<VersionSummary[]>([])
const versionPreview = ref<{
  visible: boolean
  title: string
  hocon: string
  params: Record<string, string>
  validation: ValidationSpec | null
}>({
  visible: false,
  title: '',
  hocon: '',
  params: {},
  validation: null,
})
const runs = ref<InstanceView[]>([])

const schedule = ref<ScheduleView | null>(null)
const scheduleForm = ref<ScheduleForm>({
  cron: '0 0 2 * * ?',
  enabled: false,
  failRetryCount: 0,
  retryIntervalSeconds: 60,
  timeoutSeconds: 0,
  blockStrategy: 'SERIAL_EXECUTION',
  misfireStrategy: 'DO_NOTHING',
})
const savingSchedule = ref(false)
const cronPreview = ref<string[]>([])
const cronError = ref('')
let previewTimer: number | undefined

const dirty = computed(
  () =>
    !isWizard.value &&
    (hocon.value !== savedHocon.value ||
      JSON.stringify(toMap(jobParams.value)) !== savedParams.value ||
      JSON.stringify(validationPayload()) !== savedValidation.value),
)
const jdbcDatasources = computed(() => datasources.value.filter((d) => d.type !== 'ELASTICSEARCH'))
const esDatasources = computed(() => datasources.value.filter((d) => d.type === 'ELASTICSEARCH'))

function toMap(rows: ParamRow[]): Record<string, string> {
  return Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
}

function toRows(map: Record<string, string> | undefined): ParamRow[] {
  return Object.entries(map ?? {}).map(([key, value]) => ({ key, value }))
}

/** "2026-10-24T09:30:00" in local time, the format the backend expects. */
function nowText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
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
    preview.value.result = await jobApi.preview(jobId, {
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

const backfills = ref<BackfillView[]>([])
const backfillRuns = ref<Record<string, InstanceView[]>>({})
const backfillDialog = ref<{
  visible: boolean
  range: [string, string] | null
  params: ParamRow[]
  plan: BackfillPlan | null
  planError: string
  creating: boolean
}>({ visible: false, range: null, params: [], plan: null, planError: '', creating: false })
let backfillTimer: number | undefined

const backfillStatusText = { RUNNING: '进行中', PAUSED: '已暂停', FINISHED: '已完成', CANCELED: '已取消' } as const
const backfillStatusTag = { RUNNING: 'primary', PAUSED: 'warning', FINISHED: 'success', CANCELED: 'info' } as const

/** Scheduled times already run to the end; a run XXL-JOB never took is retried and not counted. */
function backfillDone(b: BackfillView): number {
  return b.triggered - (b.statusCounts.RUNNING ?? 0) - (b.statusCounts.PENDING ?? 0)
}

function openBackfill() {
  if (dirty.value) {
    ElMessage.warning('配置有未保存的修改，请先保存为新版本再补数')
    return
  }
  backfillDialog.value = { visible: true, range: null, params: [], plan: null, planError: '', creating: false }
}

watch(
  () => backfillDialog.value.range,
  async (range) => {
    backfillDialog.value.plan = null
    backfillDialog.value.planError = ''
    if (!range) return
    try {
      backfillDialog.value.plan = await backfillApi.plan(jobId, range[0], range[1])
    } catch (e) {
      backfillDialog.value.planError =
        (e as { response?: { data?: { message?: string } } }).response?.data?.message ?? '无法计算补数计划'
    }
  },
)

async function createBackfill() {
  const range = backfillDialog.value.range
  if (!range) return
  backfillDialog.value.creating = true
  try {
    await backfillApi.create(jobId, { startDate: range[0], endDate: range[1], params: toMap(backfillDialog.value.params) })
    backfillDialog.value.visible = false
    ElMessage.success('补数已开始，会按时间顺序逐个运行')
    tab.value = 'backfills'
    loadBackfills()
  } finally {
    backfillDialog.value.creating = false
  }
}

/** Refreshes every few seconds while any backfill is still going. */
async function loadBackfills() {
  window.clearTimeout(backfillTimer)
  backfills.value = await backfillApi.list(jobId)
  for (const id of Object.keys(backfillRuns.value)) {
    loadBackfillRuns(id)
  }
  if (tab.value === 'backfills' && backfills.value.some((b) => b.status === 'RUNNING')) {
    backfillTimer = window.setTimeout(loadBackfills, 5000)
  }
}

async function loadBackfillRuns(batchId: string) {
  backfillRuns.value[batchId] = (await instanceApi.list({ backfillId: batchId, size: 100 })).items
}

async function cancelBackfill(b: BackfillView) {
  await backfillApi.cancel(b.id)
  ElMessage.success('补数已取消')
  loadBackfills()
}

async function resumeBackfill(b: BackfillView) {
  await backfillApi.resume(b.id)
  ElMessage.success('补数已继续')
  loadBackfills()
}

onBeforeUnmount(() => window.clearTimeout(backfillTimer))

const runDialog = ref<{ visible: boolean; scheduledTime: string; params: ParamRow[] }>({
  visible: false,
  scheduledTime: '',
  params: [],
})
const datasourceName = computed(() => new Map(datasources.value.map((d) => [d.id, `${d.name} (${d.type})`])))

async function load() {
  const detail = await jobApi.get(jobId)
  job.value = detail.job
  savedHocon.value = detail.currentVersion?.hocon ?? ''
  hocon.value = detail.currentVersion?.hocon ?? STARTER
  currentForm.value = detail.currentVersion?.form ?? null
  jobParams.value = toRows(detail.currentVersion?.params)
  savedParams.value = JSON.stringify(toMap(jobParams.value))
  validation.value = detail.currentVersion?.validation ?? emptyValidation()
  savedValidation.value = JSON.stringify(validationPayload())
}

async function loadVersions() {
  versions.value = await jobApi.versions(jobId)
}

async function loadRuns() {
  runs.value = (await instanceApi.list({ jobId, size: 20 })).items
}

async function save() {
  saving.value = true
  try {
    const version = await jobApi.saveVersion(jobId, {
      hocon: hocon.value,
      params: toMap(jobParams.value),
      validation: validationPayload(),
      remark: remark.value || undefined,
    })
    ElMessage.success(`已保存为 v${version.versionNo}`)
    remark.value = ''
    await load()
    loadVersions()
  } finally {
    saving.value = false
  }
}

function run() {
  if (dirty.value) {
    ElMessage.warning('配置有未保存的修改，请先保存为新版本再运行')
    return
  }
  runDialog.value = { visible: true, scheduledTime: nowText(), params: [] }
}

async function confirmRun() {
  running.value = true
  try {
    const { instanceId } = await jobApi.run(jobId, {
      scheduledTime: runDialog.value.scheduledTime || undefined,
      params: toMap(runDialog.value.params),
    })
    runDialog.value.visible = false
    ElMessage.success('已提交运行')
    router.push(`/instances/${instanceId}`)
  } finally {
    running.value = false
  }
}

/** Inserts the datasource reference at the cursor, or fills an empty datasource_id = "". */
async function insertDatasource() {
  const ds = datasources.value.find((d) => d.id === pickedDatasource.value)
  if (!ds) {
    return
  }
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

const diff = ref({ visible: false, leftId: '', rightId: '' })

/** Compares a version with the current one (or the previous one, for the current version itself). */
function compareVersion(v: VersionSummary) {
  const current = versions.value[0]!
  const other = v.id === current.id ? versions.value[1]! : current
  const [older, newer] = other.versionNo < v.versionNo ? [other, v] : [v, other]
  diff.value = { visible: true, leftId: older.id, rightId: newer.id }
}

async function rollbackVersion(v: VersionSummary) {
  try {
    await ElMessageBox.confirm(
      `用 v${v.versionNo} 的配置${isWizard.value ? '（向导表单）' : ''}和任务参数生成一个新版本，并设为当前版本。历史版本都会保留。`,
      `回滚到 v${v.versionNo}`,
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const version = await jobApi.restoreVersion(jobId, v.id)
  ElMessage.success(`已回滚，生成 v${version.versionNo}`)
  await load()
  loadVersions()
  wizardKey.value++
}

async function previewVersion(v: VersionSummary) {
  const detail = await jobApi.version(jobId, v.id)
  versionPreview.value = { visible: true, title: `v${detail.versionNo}`, hocon: detail.hocon, params: detail.params, validation: detail.validation }
}

function restoreVersion() {
  hocon.value = versionPreview.value.hocon
  jobParams.value = toRows(versionPreview.value.params)
  validation.value = versionPreview.value.validation ?? emptyValidation()
  versionPreview.value.visible = false
  tab.value = 'config'
  ElMessage.info('已载入到编辑器，保存后成为新版本')
}

async function loadSchedule() {
  schedule.value = await scheduleApi.get(jobId)
  if (schedule.value) {
    const { cron, enabled, failRetryCount, retryIntervalSeconds, timeoutSeconds, blockStrategy, misfireStrategy } = schedule.value
    scheduleForm.value = { cron, enabled, failRetryCount, retryIntervalSeconds, timeoutSeconds, blockStrategy, misfireStrategy }
  }
  previewCron()
}

async function previewCron() {
  try {
    cronPreview.value = await scheduleApi.preview(scheduleForm.value.cron)
    cronError.value = ''
  } catch (e) {
    cronPreview.value = []
    cronError.value = (e as { response?: { data?: { message?: string } } }).response?.data?.message ?? 'cron 无效'
  }
}

watch(
  () => scheduleForm.value.cron,
  () => {
    window.clearTimeout(previewTimer)
    previewTimer = window.setTimeout(previewCron, 400)
  },
)

async function saveSchedule() {
  savingSchedule.value = true
  try {
    schedule.value = await scheduleApi.save(jobId, scheduleForm.value)
    if (schedule.value.syncStatus === 'SYNCED') {
      ElMessage.success(schedule.value.enabled ? '调度已启用' : '调度已保存（未启用）')
    } else {
      ElMessage.warning('调度已保存，但同步到 XXL-JOB 失败，请稍后重新保存')
    }
  } finally {
    savingSchedule.value = false
  }
}

const syncText = { PENDING: '待同步', SYNCED: '已同步', FAILED: '同步失败' } as const
const syncTag = { PENDING: 'info', SYNCED: 'success', FAILED: 'danger' } as const

async function onWizardSaved() {
  await load()
  loadVersions()
  wizardKey.value++
}

async function switchToScript() {
  try {
    await ElMessageBox.confirm(
      '转换后用脚本编辑器直接修改 HOCON，当前生成的配置会原样带过去。转换不可逆，不能再回到向导。',
      '转为脚本模式',
      { type: 'warning', confirmButtonText: '转换', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  job.value = await jobApi.switchToScript(jobId)
  await load()
  ElMessage.success('已转为脚本模式')
}

function onTab(name: string | number) {
  if (name === 'versions') loadVersions()
  if (name === 'runs') loadRuns()
  if (name === 'schedule') loadSchedule()
  if (name === 'backfills') loadBackfills()
}

onMounted(async () => {
  await load()
  onTab(tab.value)
  datasources.value = (await datasourceApi.list({ size: 200 })).items
})
</script>

<template>
  <el-card v-if="job" shadow="never">
    <div class="head">
      <div>
        <div class="name">{{ job.name }}</div>
        <div class="meta">
          {{ job.projectName }} · 离线任务 · 当前版本 {{ job.currentVersionNo ? `v${job.currentVersionNo}` : '未保存' }}
        </div>
      </div>
      <div class="actions">
        <el-button v-if="isWizard" link type="primary" @click="switchToScript">转为脚本模式</el-button>
        <el-button :disabled="!job.currentVersionNo" @click="openBackfill">补数</el-button>
        <el-button type="primary" :loading="running" :disabled="!job.currentVersionNo" @click="run">立即运行</el-button>
      </div>
    </div>

    <el-tabs v-model="tab" @tab-change="onTab">
      <el-tab-pane :label="isWizard ? '配置（向导）' : '配置（脚本模式）'" name="config">
        <JobWizard v-if="isWizard" :key="wizardKey" v-model:params="jobParams" :job-id="jobId" :initial-form="currentForm" @saved="onWizardSaved" />
        <template v-else>
        <div class="editor-bar">
          <el-select v-model="pickedDatasource" placeholder="选择数据源" filterable class="ds-select">
            <el-option v-for="d in datasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
          </el-select>
          <el-button :disabled="!pickedDatasource" @click="insertDatasource">插入数据源</el-button>
          <span class="tip">在 source / sink 的连接器里写 <code>datasource_id</code>，密码不会出现在配置中。目前支持 Jdbc 和 Elasticsearch 连接器。</span>
        </div>
        <el-input ref="editor" v-model="hocon" type="textarea" :rows="24" class="code" spellcheck="false" />
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
        <div v-if="validation.enabled" class="validation">
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
                <el-option v-for="d in jdbcDatasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
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
                <el-option v-for="d in jdbcDatasources" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
              </el-select>
              <el-input v-model="validation.target.sql" type="textarea" :rows="3" class="code" placeholder="select count(*) from ods_orders where dt = '${dt}'" />
            </template>
            <template v-else>
              <el-select v-model="validation.target.datasourceId" placeholder="数据源" filterable class="side-ds">
                <el-option v-for="d in esDatasources" :key="d.id" :label="d.name" :value="d.id" />
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

        <div class="save-bar">
          <el-input v-model="remark" placeholder="版本备注（可选）" maxlength="512" class="remark" />
          <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存为新版本</el-button>
          <el-button @click="openPreview">预览渲染结果</el-button>
          <span v-if="dirty" class="unsaved">有未保存的修改</span>
        </div>
        </template>
      </el-tab-pane>

      <el-tab-pane label="调度" name="schedule">
        <div class="schedule">
          <el-alert v-if="schedule?.syncStatus === 'FAILED'" type="error" :closable="false" show-icon class="sync-alert"
            :title="`同步到 XXL-JOB 失败：${schedule.syncError ?? ''}`" description="console 中的调度已保存，修正问题后重新保存即可再次同步。" />
          <el-form label-width="120px" class="schedule-form">
            <el-form-item label="启用">
              <el-switch v-model="scheduleForm.enabled" :disabled="!job.currentVersionNo" />
              <span v-if="!job.currentVersionNo" class="tip">先保存一个版本才能启用</span>
              <span v-else class="tip">定时运行总是使用触发时的当前版本</span>
            </el-form-item>
            <el-form-item label="Cron 表达式">
              <el-input v-model="scheduleForm.cron" class="cron" placeholder="秒 分 时 日 月 周，如 0 0 2 * * ?" />
              <div class="cron-help">
                <div v-if="cronError" class="cron-error">{{ cronError }}</div>
                <div v-else-if="cronPreview.length" class="tip">接下来 {{ cronPreview.length }} 次：{{ cronPreview.map(formatTime).join('，') }}</div>
                <div class="tip">格式：秒 分 时 日 月 周，日和周中必须有一个是 <code>?</code>。示例：每天 2 点 <code>0 0 2 * * ?</code>，每小时 <code>0 0 * * * ?</code>，每周一 1:30 <code>0 30 1 ? * MON</code></div>
              </div>
            </el-form-item>
            <el-form-item label="失败重试次数">
              <el-input-number v-model="scheduleForm.failRetryCount" :min="0" :max="10" />
              <span class="tip">对定时运行和补数生效，重试记录在同一个运行实例里</span>
            </el-form-item>
            <el-form-item label="重试间隔（秒）">
              <el-input-number v-model="scheduleForm.retryIntervalSeconds" :min="0" :max="86400" :step="30" />
            </el-form-item>
            <el-form-item label="超时（秒）">
              <el-input-number v-model="scheduleForm.timeoutSeconds" :min="0" :step="60" />
              <span class="tip">0 表示不限；超时后停止 SeaTunnel 作业，运行记为已取消。对手动运行同样生效</span>
            </el-form-item>
            <el-form-item label="上次未结束时">
              <el-radio-group v-model="scheduleForm.blockStrategy">
                <el-radio value="SERIAL_EXECUTION">排队等待</el-radio>
                <el-radio value="DISCARD_LATER">丢弃本次</el-radio>
                <el-radio value="COVER_EARLY">停止上次，执行本次</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="错过的触发">
              <el-radio-group v-model="scheduleForm.misfireStrategy">
                <el-radio value="DO_NOTHING">忽略</el-radio>
                <el-radio value="FIRE_ONCE_NOW">立即补一次</el-radio>
              </el-radio-group>
              <span class="tip">XXL-JOB 停机期间错过的触发</span>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="savingSchedule" :disabled="!!cronError" @click="saveSchedule">保存调度</el-button>
              <template v-if="schedule">
                <el-tag :type="syncTag[schedule.syncStatus]" class="sync-tag">{{ syncText[schedule.syncStatus] }}</el-tag>
                <span class="tip">XXL-JOB 任务 ID：{{ schedule.xxlJobId ?? '-' }}<template v-if="schedule.syncedAt"> · 同步于 {{ formatTime(schedule.syncedAt) }}</template></span>
              </template>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>

      <el-tab-pane label="补数" name="backfills">
        <el-table :data="backfills" row-key="id" @expand-change="(row: BackfillView) => loadBackfillRuns(row.id)">
          <el-table-column type="expand">
            <template #default="{ row }">
              <el-table :data="backfillRuns[row.id] ?? []" size="small" class="inner"
                @row-click="(r: InstanceView) => router.push(`/instances/${r.id}`)">
                <el-table-column label="计划时间" width="170">
                  <template #default="{ row: r }">{{ formatTime(r.scheduledTime) }}</template>
                </el-table-column>
                <el-table-column label="状态" width="100">
                  <template #default="{ row: r }">
                    <el-tag size="small" :type="statusTag[r.status as keyof typeof statusTag]">{{ statusText[r.status as keyof typeof statusText] }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="耗时" width="100">
                  <template #default="{ row: r }">{{ formatDuration(r.durationMillis) }}</template>
                </el-table-column>
                <el-table-column label="读 / 写行数" width="140">
                  <template #default="{ row: r }">{{ formatNumber(r.readRows) }} / {{ formatNumber(r.writeRows) }}</template>
                </el-table-column>
                <el-table-column label="校验" width="130">
                  <template #default="{ row: r }"><ValidationTag :instance="r" /></template>
                </el-table-column>
                <el-table-column label="校验" width="130">
            <template #default="{ row }"><ValidationTag :instance="row" /></template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
              </el-table>
            </template>
          </el-table-column>
          <el-table-column label="日期范围" width="210">
            <template #default="{ row }">{{ row.startDate }} ~ {{ row.endDate }}</template>
          </el-table-column>
          <el-table-column label="版本" width="70">
            <template #default="{ row }">v{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="进度" width="200">
            <template #default="{ row }">
              <el-progress :percentage="Math.round(backfillDone(row) * 100 / row.totalRuns)" :format="() => `${backfillDone(row)}/${row.totalRuns}`" />
            </template>
          </el-table-column>
          <el-table-column label="结果" width="170">
            <template #default="{ row }">
              <span class="ok">成功 {{ row.statusCounts.SUCCESS ?? 0 }}</span>
              <span v-if="row.statusCounts.FAILED" class="bad"> · 失败 {{ row.statusCounts.FAILED }}</span>
              <span v-if="(row.statusCounts.RUNNING ?? 0) + (row.statusCounts.PENDING ?? 0)" class="tip"> · 运行中 1</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="backfillStatusTag[row.status as keyof typeof backfillStatusTag]">{{ backfillStatusText[row.status as keyof typeof backfillStatusText] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.errorMessage ?? (row.cron ? `按调度 ${row.cron}` : '每天 00:00') }}</template>
          </el-table-column>
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="120" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'PAUSED'" link type="primary" @click.stop="resumeBackfill(row)">继续</el-button>
              <el-popconfirm v-if="row.status === 'RUNNING' || row.status === 'PAUSED'" title="取消补数？正在运行的那次也会被停止。" @confirm="cancelBackfill(row)">
                <template #reference>
                  <el-button link type="danger" @click.stop>取消</el-button>
                </template>
              </el-popconfirm>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="版本" name="versions">
        <el-table :data="versions">
          <el-table-column label="版本" width="90">
            <template #default="{ row }">v{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="200" />
          <el-table-column label="创建时间" width="170">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="170">
            <template #default="{ row }">
              <el-button link type="primary" @click="previewVersion(row)">查看</el-button>
              <el-button link type="primary" :disabled="versions.length < 2" @click="compareVersion(row)">对比</el-button>
              <el-button link type="primary" :disabled="row.versionNo === job.currentVersionNo" @click="rollbackVersion(row)">回滚</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="运行记录" name="runs">
        <el-table :data="runs" @row-click="(row: InstanceView) => router.push(`/instances/${row.id}`)">
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag[row.status as keyof typeof statusTag]">{{ statusText[row.status as keyof typeof statusText] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发" width="80">
            <template #default="{ row }">{{ triggerText[row.triggerType] }}</template>
          </el-table-column>
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.versionNo }}</template>
          </el-table-column>
          <el-table-column label="开始时间" width="170">
            <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ formatDuration(row.durationMillis) }}</template>
          </el-table-column>
          <el-table-column label="读 / 写行数" width="140">
            <template #default="{ row }">{{ formatNumber(row.readRows) }} / {{ formatNumber(row.writeRows) }}</template>
          </el-table-column>
          <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
        </el-table>
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <el-dialog v-model="preview.visible" title="预览渲染结果（按编辑器中的内容，密码已打码）" width="860px">
    <div class="preview-bar">
      <span>计划时间</span>
      <el-date-picker v-model="preview.scheduledTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :clearable="false" />
      <el-button :loading="preview.loading" @click="loadPreview">重新渲染</el-button>
    </div>
    <template v-if="preview.result">
      <el-alert v-if="preview.result.unresolved.length" type="warning" :closable="false" show-icon class="preview-alert"
        :title="`这些 \${...} 不是 console 变量，会原样交给 HOCON 解析（多半是拼写错误）：${preview.result.unresolved.join('，')}`" />
      <el-table :data="Object.entries(preview.result.variables).map(([k, v]) => ({ k, v }))" size="small" max-height="220">
        <el-table-column prop="k" label="变量" width="260" />
        <el-table-column prop="v" label="值" />
      </el-table>
      <pre class="code-view preview-config">{{ preview.result.config }}</pre>
    </template>
  </el-dialog>

  <el-dialog v-model="backfillDialog.visible" title="补数" width="700px">
    <el-form label-width="90px">
      <el-form-item label="日期范围">
        <el-date-picker v-model="backfillDialog.range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="tip">
          选的是<b>计划时间</b>的日期，<code>${system.biz.date}</code> 是计划时间的前一天。任务有调度时，按调度的 cron 生成范围内的每个触发时间；没有调度时每天 00:00 一次。
        </div>
      </el-form-item>
      <el-form-item v-if="backfillDialog.plan || backfillDialog.planError" label="计划">
        <span v-if="backfillDialog.planError" class="bad">{{ backfillDialog.planError }}</span>
        <span v-else-if="backfillDialog.plan">
          共 {{ backfillDialog.plan.totalRuns }} 次，{{ formatTime(backfillDialog.plan.firstTime) }} 到 {{ formatTime(backfillDialog.plan.lastTime) }}
          <span class="tip">（{{ backfillDialog.plan.cron ? `按调度 ${backfillDialog.plan.cron}` : '每天 00:00' }}，使用当前版本 v{{ job?.currentVersionNo }}）</span>
        </span>
      </el-form-item>
      <el-form-item label="启动参数">
        <ParamsEditor v-model="backfillDialog.params" />
        <div class="tip">对每一次运行都生效</div>
      </el-form-item>
    </el-form>
    <p class="tip">
      按时间顺序<b>逐个</b>运行：上一次结束后才触发下一次（XXL-JOB 对同一个任务只能逐个执行）。某一次失败不会中断补数，结束后可以单独重跑；
      失败重试次数和超时沿用调度配置。补数进行中时，定时调度仍会照常触发。
    </p>
    <template #footer>
      <el-button @click="backfillDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="backfillDialog.creating" :disabled="!backfillDialog.plan" @click="createBackfill">开始补数</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="runDialog.visible" title="立即运行" width="680px">
    <el-form label-width="90px">
      <el-form-item label="计划时间">
        <el-date-picker v-model="runDialog.scheduledTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :clearable="false" />
        <div class="tip">变量和时间表达式以它为基准，默认是现在。比如要跑昨天的数据，可以改成今天 00:00 之后的某个时间点。</div>
      </el-form-item>
      <el-form-item label="启动参数">
        <ParamsEditor v-model="runDialog.params" />
        <div class="tip">只对这次运行生效，优先级最高</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="runDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="running" @click="confirmRun">运行</el-button>
    </template>
  </el-dialog>

  <VersionDiff v-if="versions.length >= 2" v-model="diff.visible" :job-id="jobId" :versions="versions" :left-id="diff.leftId" :right-id="diff.rightId" />

  <el-dialog v-model="versionPreview.visible" :title="`版本 ${versionPreview.title}`" width="760px">
    <pre class="code-view">{{ versionPreview.hocon }}</pre>
    <p class="tip">配置中的数据源：{{ [...versionPreview.hocon.matchAll(/datasource_id\s*=\s*"([^"]+)"/g)].map((m) => datasourceName.get(m[1]!) ?? m[1]).join('，') || '无' }}</p>
    <template #footer>
      <el-button @click="versionPreview.visible = false">关闭</el-button>
      <el-button v-if="!isWizard" type="primary" @click="restoreVersion">载入到编辑器</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.name {
  font-size: 18px;
  font-weight: 600;
}

.meta {
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

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

.ok {
  color: var(--el-color-success);
}

.bad {
  color: var(--el-color-danger);
}

.inner {
  margin: 0 16px 0 48px;
  width: auto;
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

.editor-bar,
.save-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin: 8px 0 12px;
}

.schedule-form {
  max-width: 900px;
}

.sync-alert {
  margin-bottom: 16px;
}

.cron {
  width: 260px;
}

.cron-help {
  width: 100%;
  line-height: 1.6;
}

.cron-error {
  color: var(--el-color-danger);
  font-size: 13px;
}

.sync-tag {
  margin: 0 8px 0 12px;
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

:deep(.el-table__row) {
  cursor: pointer;
}
</style>

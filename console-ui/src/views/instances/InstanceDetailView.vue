<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { instanceApi, type InstanceDetail, type LogView, type RunMetrics, type RunStatus } from '@/api/console'
import BackButton from '@/components/BackButton.vue'
import JsonViewer from '@/components/json/JsonViewer.vue'
import RunMetricsPanel from '@/components/RunMetricsPanel.vue'
import ValidationTag from '@/components/ValidationTag.vue'
import { listUrl, setBreadcrumb } from '@/composables/useBreadcrumb'
import { formatDuration, formatNumber, formatTime, isActive, statusTag, statusText, triggerText } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const id = route.params.id as string

const detail = ref<InstanceDetail | null>(null)
/**
 * The attempt whose log and metrics are shown; 0 until the attempts are known, then the latest.
 * An earlier one is kept in the URL (?attempt=), the latest needs nothing.
 */
const activeAttempt = ref<number>(Number(route.query.attempt) || 0)
const log = ref<LogView | null>(null)
const metrics = ref<RunMetrics | null>(null)
const logLoading = ref(false)
const followTail = ref(true)
const logBox = ref<HTMLElement | null>(null)
const logFullscreen = ref(false)
let timer: number | undefined
let pageBodyOverflow = ''

async function setLogFullscreen(value: boolean) {
  if (logFullscreen.value === value) return
  if (value) {
    pageBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = pageBodyOverflow
  }
  logFullscreen.value = value
  if (followTail.value) {
    await nextTick()
    logBox.value?.scrollTo({ top: logBox.value.scrollHeight })
  }
}

function toggleLogFullscreen() {
  setLogFullscreen(!logFullscreen.value)
}

function onLogKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') setLogFullscreen(false)
}

const instance = computed(() => detail.value?.instance)
const active = computed(() => (instance.value ? isActive(instance.value.status) : false))

const variables = computed(() => {
  try {
    return Object.entries(JSON.parse(detail.value?.variablesJson ?? '{}') as Record<string, string>).map(([k, v]) => ({ k, v }))
  } catch {
    return []
  }
})

const levelColor: Record<string, string> = { INFO: 'primary', WARN: 'warning', ERROR: 'danger' }

async function loadDetail() {
  const first = detail.value === null
  const loaded = await instanceApi.detail(id)
  // Left the page meanwhile: the breadcrumb and the URL now belong to another page.
  if (disposed) return
  detail.value = loaded
  if (first) {
    const i = detail.value.instance
    setBreadcrumb([
      { title: '运行实例', to: listUrl('/instances') },
      { title: i.jobName ?? '已删除的任务', to: i.jobDeleted ? undefined : `/jobs/${i.jobDefinitionId}` },
      { title: `${triggerText[i.triggerType]}运行 · ${formatTime(i.scheduledTime).slice(5, 16)}` },
    ])
  }
  const attempts = detail.value.attempts
  if (attempts.length > 0 && !attempts.some((a) => a.attemptNo === activeAttempt.value)) {
    activeAttempt.value = attempts[attempts.length - 1]!.attemptNo
  }
}

/** Each load is numbered; only the latest one's response is shown, however late the others come. */
let logSeq = 0
let metricsSeq = 0

async function loadLog() {
  if (!detail.value || detail.value.attempts.length === 0) {
    log.value = null
    return
  }
  logLoading.value = true
  const seq = ++logSeq
  try {
    const loaded = await instanceApi.log(id, activeAttempt.value)
    // A newer request (another attempt, or the same one polled again) was started meanwhile: it decides.
    if (seq !== logSeq) return
    log.value = loaded
  } finally {
    // Only the latest request ends the loading state; an older one finishing does not.
    if (seq === logSeq) logLoading.value = false
  }
  if (followTail.value) {
    await nextTick()
    logBox.value?.scrollTo({ top: logBox.value.scrollHeight })
  }
}

async function loadMetrics() {
  const seq = ++metricsSeq
  const loaded = detail.value && detail.value.attempts.length > 0 ? await instanceApi.metrics(id, activeAttempt.value) : null
  if (seq === metricsSeq) {
    metrics.value = loaded
  }
}

let disposed = false

/**
 * While the run is active, refresh status, metrics and the live SeaTunnel log every few seconds.
 * A failed request (shown by the HTTP layer) does not end the refreshing; leaving the page does,
 * also for a request still on its way.
 */
async function refresh() {
  window.clearTimeout(timer)
  try {
    await loadDetail()
    await Promise.all([loadLog(), loadMetrics()])
  } catch {
    // already reported; try again on the next tick while the run is active
  } finally {
    if (!disposed && active.value) {
      timer = window.setTimeout(refresh, 4000)
    }
  }
}

const stopping = ref(false)

async function stop() {
  try {
    await ElMessageBox.confirm(
      instance.value?.status === 'RUNNING' ? '将停止 SeaTunnel 上正在运行的作业，已写入的数据不会回滚。' : '任务还没开始执行，将直接取消。',
      '停止运行',
      { type: 'warning', confirmButtonText: '停止', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  stopping.value = true
  try {
    await instanceApi.stop(id)
    ElMessage.success('已请求停止')
    await refresh()
  } finally {
    stopping.value = false
  }
}

const rerunDialog = ref({ visible: false, useLatestVersion: false, loading: false })

async function rerun() {
  rerunDialog.value.loading = true
  try {
    const { instanceId } = await instanceApi.rerun(id, rerunDialog.value.useLatestVersion)
    rerunDialog.value.visible = false
    ElMessage.success('已提交重跑')
    router.push(`/instances/${instanceId}`)
  } finally {
    rerunDialog.value.loading = false
  }
}

const startupParams = computed(() => {
  try {
    return JSON.parse(detail.value?.startupParamsJson ?? '{}') as Record<string, string>
  } catch {
    return {}
  }
})

watch(activeAttempt, (attempt) => {
  if (disposed) return
  router.replace({ query: attempt === detail.value?.attempts.at(-1)?.attemptNo ? {} : { attempt: String(attempt) } })
  loadLog()
  loadMetrics()
})

const logSourceText = computed(() => {
  switch (log.value?.source) {
    case 'LIVE':
      return '实时读取自 SeaTunnel'
    case 'ARCHIVED':
      return '已归档'
    default:
      return '不可用'
  }
})

onMounted(() => {
  refresh()
  document.addEventListener('keydown', onLogKeydown)
})
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(timer)
  document.removeEventListener('keydown', onLogKeydown)
  if (logFullscreen.value) document.body.style.overflow = pageBodyOverflow
})
</script>

<template>
  <div v-if="detail && instance" class="page">
    <el-card shadow="never">
      <div class="head">
        <div class="title">
          <BackButton :fallback="instance.jobDeleted ? '/instances' : `/jobs/${instance.jobDefinitionId}/runs`" />
          <el-tag :type="statusTag[instance.status as RunStatus]" size="large">{{ statusText[instance.status as RunStatus] }}</el-tag>
          <router-link v-if="!instance.jobDeleted" :to="`/jobs/${instance.jobDefinitionId}`" class="job">{{ instance.jobName }}</router-link>
          <span v-else class="job">{{ instance.jobName ?? '已删除的任务' }}</span>
          <el-tag v-if="instance.jobDeleted" type="info">已删除</el-tag>
          <span class="muted">v{{ instance.versionNo }} · {{ triggerText[instance.triggerType] }}运行<template v-if="instance.createdByName"> · {{ instance.createdByName }}</template> · 计划时间 {{ formatTime(instance.scheduledTime) }}</span>
          <router-link v-if="instance.rerunOfInstanceId" :to="`/instances/${instance.rerunOfInstanceId}`" class="muted">重跑自另一次运行</router-link>
          <router-link v-if="instance.backfillBatchId && !instance.jobDeleted" :to="`/jobs/${instance.jobDefinitionId}/backfills`" class="muted">属于一次补数</router-link>
        </div>
        <div>
          <el-button v-if="active" type="danger" plain :loading="stopping" :disabled="!instance.editable" @click="stop">停止</el-button>
          <el-button v-else :disabled="!instance.editable" @click="rerunDialog = { visible: true, useLatestVersion: false, loading: false }">重跑</el-button>
          <el-button @click="refresh">刷新</el-button>
        </div>
      </div>
      <el-descriptions :column="3" class="summary">
        <el-descriptions-item label="开始时间">{{ formatTime(instance.startedAt) }}</el-descriptions-item>
        <el-descriptions-item label="结束时间">{{ formatTime(instance.finishedAt) }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ formatDuration(instance.durationMillis) }}</el-descriptions-item>
        <el-descriptions-item label="读取行数">{{ formatNumber(instance.readRows) }}</el-descriptions-item>
        <el-descriptions-item label="写入行数">{{ formatNumber(instance.writeRows) }}</el-descriptions-item>
        <el-descriptions-item label="尝试次数">{{ instance.attemptCount }}</el-descriptions-item>
        <el-descriptions-item v-if="instance.validationStatus && instance.validationStatus !== 'SKIPPED'" label="行数校验" :span="3">
          <ValidationTag :instance="instance" />
          <span class="muted validation-msg">{{ instance.validationMessage }}</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="instance.errorMessage" :title="instance.errorMessage" type="error" :closable="false" show-icon class="error" />
    </el-card>

    <el-card v-if="metrics" shadow="never">
      <template #header>
        运行指标<span v-if="detail.attempts.length > 1" class="muted"> · 第 {{ metrics.attemptNo }} 次尝试</span>
        <span v-if="active" class="muted"> · 运行中，每 4 秒刷新</span>
      </template>
      <RunMetricsPanel :metrics="metrics.metrics" :samples="metrics.samples" />
    </el-card>

    <div class="columns">
      <el-card shadow="never" class="timeline-card">
        <template #header>事件时间线</template>
        <el-timeline>
          <el-timeline-item
            v-for="(e, idx) in detail.events"
            :key="idx"
            :timestamp="formatTime(e.time)"
            :type="levelColor[e.level] as 'primary' | 'warning' | 'danger'"
            placement="top"
          >
            <span class="event-type">{{ e.type }}</span>
            <span v-if="e.attemptNo" class="muted"> · 第 {{ e.attemptNo }} 次</span>
            <div class="event-msg">{{ e.message }}</div>
          </el-timeline-item>
        </el-timeline>
      </el-card>

      <el-card shadow="never" class="log-card" :class="{ 'page-fullscreen': logFullscreen }">
        <template #header>
          <div class="log-head">
            <span>SeaTunnel 作业日志</span>
            <div class="log-tools">
              <el-radio-group v-if="detail.attempts.length > 1" v-model="activeAttempt" size="small">
                <el-radio-button v-for="a in detail.attempts" :key="a.attemptNo" :value="a.attemptNo">第 {{ a.attemptNo }} 次</el-radio-button>
              </el-radio-group>
              <el-checkbox v-model="followTail" size="small">跟随最新</el-checkbox>
              <el-button size="small" :loading="logLoading" @click="loadLog">刷新日志</el-button>
              <el-button size="small" @click="toggleLogFullscreen">{{ logFullscreen ? '退出全屏' : '全屏查看' }}</el-button>
            </div>
          </div>
        </template>
        <div v-if="detail.attempts.length === 0" class="muted">任务尚未提交到 SeaTunnel</div>
        <template v-else>
          <div class="log-meta">
            <span>引擎作业 ID：{{ detail.attempts.find((a) => a.attemptNo === activeAttempt)?.engineJobId ?? '-' }}</span>
            <span>来源：{{ logSourceText }}</span>
            <span v-if="log?.truncated" class="warn">日志过大，只保留了末尾部分</span>
          </div>
          <el-alert v-if="log?.source === 'UNAVAILABLE'" :title="log.message ?? '日志不可用'" type="warning" :closable="false" />
          <pre v-else ref="logBox" class="log">{{ log?.content }}</pre>
        </template>
      </el-card>
    </div>

    <el-card shadow="never">
      <el-collapse>
        <el-collapse-item :title="`变量（${variables.length}）`" name="variables">
          <div class="muted">计划时间 {{ formatTime(instance.scheduledTime) }}。运行开始后这里是所有变量的最终值，开始前只有启动参数。</div>
          <el-table :data="variables">
            <el-table-column prop="k" label="变量" width="280" />
            <el-table-column prop="v" label="值" />
          </el-table>
        </el-collapse-item>
        <el-collapse-item title="实际提交的配置（敏感信息已打码）" name="config">
          <JsonViewer v-if="detail.configMasked" :text="detail.configMasked" />
          <div v-else class="muted">尚未生成</div>
        </el-collapse-item>
      </el-collapse>
    </el-card>
  </div>

  <el-dialog v-model="rerunDialog.visible" title="重跑" width="520px">
    <p>用同样的计划时间（{{ formatTime(instance?.scheduledTime) }}）和启动参数再运行一次，变量会按这个计划时间重新计算。</p>
    <p v-if="Object.keys(startupParams).length" class="muted">启动参数：{{ Object.entries(startupParams).map(([k, v]) => `${k}=${v}`).join('，') }}</p>
    <el-checkbox v-model="rerunDialog.useLatestVersion">使用任务的当前版本（默认使用这次运行的版本 v{{ instance?.versionNo }}）</el-checkbox>
    <template #footer>
      <el-button @click="rerunDialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="rerunDialog.loading" @click="rerun">重跑</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.title {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  margin-left: -6px;
}

.job {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  text-decoration: none;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.validation-msg {
  margin-left: 8px;
}

.error {
  margin-top: 16px;
}

.columns {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(0, 2.4fr);
  gap: 16px;
  height: clamp(520px, calc(100vh - 300px), 760px);
}

.timeline-card,
.log-card {
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.timeline-card :deep(.el-card__body) {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.log-card :deep(.el-card__body) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

@media (max-width: 1100px) {
  .columns {
    grid-template-columns: 1fr;
    height: auto;
  }

  .timeline-card,
  .log-card {
    height: min(560px, 70vh);
  }
}

.event-type {
  font-weight: 500;
}

.event-msg {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  word-break: break-word;
}

.log-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.log-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.log-meta {
  display: flex;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-wrap: wrap;
}

.warn {
  color: var(--el-color-warning);
}

.log {
  margin: 0;
  padding: 12px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  border-radius: 4px;
  overflow: auto;
}

.log-card.page-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  height: auto;
  border: 0;
  border-radius: 0;
}

.log {
  flex: 1;
  min-height: 0;
  background: #1e1e1e;
  color: #d4d4d4;
}
</style>

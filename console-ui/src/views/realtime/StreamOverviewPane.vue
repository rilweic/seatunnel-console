<script setup lang="ts">
/**
 * How a real-time job is doing (design.md §12 P2, 详情页): its state, when data last came in,
 * checkpoints, where the next start resumes, the running start's metrics and what it syncs.
 * The parent refreshes {@link view}; this pane refreshes the metrics of the running start.
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import { instanceApi, jobApi, type JobOverview, type JobView, type RunMetrics, type StreamView } from '@/api/console'
import JobFlowCard from '@/components/JobFlowCard.vue'
import RunMetricsPanel from '@/components/RunMetricsPanel.vue'
import { formatBytes, formatDuration, formatTime } from '@/utils/format'
import { ago, streamDisplay, streamStateTag, streamStateText } from '@/utils/stream'

const props = defineProps<{ job: JobView; view: StreamView }>()

const overview = ref<JobOverview | null>(null)
const live = ref<RunMetrics | null>(null)
const now = ref(Date.now())
let timer: number | undefined

const display = computed(() => streamDisplay(props.view))
const runningFor = computed(() => (props.view.startedAt ? formatDuration(now.value - new Date(props.view.startedAt).getTime()) : '-'))
/** A savepoint stop that takes this long probably cannot complete. */
const slowStop = computed(
  () => props.view.stopRequested === 'SAVEPOINT' && !!props.view.stopRequestedAt && now.value - new Date(props.view.stopRequestedAt).getTime() > 120_000,
)
const resumeText = computed(() => {
  const v = props.view
  if (v.restoreBlocked) return `只能全新启动：${v.restoreBlocked}`
  if (v.restoreKind === 'SAVEPOINT') return '从上次停止时的 savepoint 接着同步'
  if (v.restoreKind === 'CHECKPOINT') return '从最近一次 checkpoint 接着同步'
  return '还没有保存的位置，下次全新启动'
})

async function loadMetrics() {
  live.value = props.view.currentInstanceId ? await instanceApi.metrics(props.view.currentInstanceId).catch(() => null) : null
}

onMounted(async () => {
  overview.value = await jobApi.overview(props.job.id)
  await loadMetrics()
  timer = window.setInterval(() => {
    now.value = Date.now()
    loadMetrics()
  }, 30_000)
})
onBeforeUnmount(() => window.clearInterval(timer))
watch(() => props.view.currentInstanceId, loadMetrics)
watch(() => props.view, () => (now.value = Date.now()))
</script>

<template>
  <div class="pane">
    <el-alert v-if="view.newVersionPending" type="info" :closable="false" show-icon
      title="任务有新版本，重启后生效（先停止，再启动）。来源或目标设置有变化时，启动默认全新开始" />
    <el-alert v-if="view.state === 'RECOVERING'" type="warning" :closable="false" show-icon
      :title="`作业出错，SeaTunnel 正在自动恢复（第 ${view.pipelineRestores} 次）。超过引擎的上限后作业会失败`" />
    <el-alert v-if="view.state === 'RESTARTING' && view.restartAt" type="warning" :closable="false" show-icon
      :title="`作业失败，将在 ${formatTime(view.restartAt)} 从最近的 checkpoint 自动重启`" />
    <el-alert v-if="slowStop" type="warning" :closable="false" show-icon
      title="停止 2 分钟还没完成：savepoint 可能做不出来（检查 checkpoint 存储）。可以强制停止，之后只能从最近的 checkpoint 恢复" />
    <el-alert v-if="view.checkpointProblem" type="warning" :closable="false" show-icon
      :title="`checkpoint 异常：${view.checkpointProblem}。savepoint 停止可能失败，失败后恢复要重放更多数据`" />
    <el-alert v-if="display === 'FAILED' && view.lastRunMessage" type="error" :closable="false" show-icon :title="view.lastRunMessage" />

    <div class="cards">
      <div class="card">
        <div class="label">状态</div>
        <div class="value"><el-tag :type="streamStateTag[display]">{{ streamStateText[display] }}</el-tag></div>
        <div class="sub">
          <template v-if="view.state !== 'STOPPED'">已运行 {{ runningFor }}<template v-if="view.attemptNo && view.attemptNo > 1"> · 自动重启过 {{ view.attemptNo - 1 }} 次</template></template>
          <template v-else-if="view.lastRunId">
            <router-link :to="`/instances/${view.lastRunId}`" class="link">上次运行</router-link>
          </template>
          <template v-else>还没有启动过</template>
        </div>
      </div>
      <div class="card">
        <div class="label">最近读到数据</div>
        <div class="value">{{ view.state === 'STOPPED' ? '-' : ago(view.lastReadAt, now) }}</div>
        <div class="sub">每分钟更新一次</div>
      </div>
      <div class="card">
        <div class="label">最近一次 checkpoint</div>
        <div class="value" :class="{ warn: !!view.checkpointProblem }">{{ view.state === 'STOPPED' ? '-' : ago(view.checkpointAt, now) }}</div>
        <div class="sub">{{ view.checkpointProblem ?? '每分钟检查一次' }}</div>
      </div>
      <div class="card">
        <div class="label">下次启动</div>
        <div class="value small">{{ resumeText }}</div>
        <div v-if="view.replication" class="sub" :class="{ warn: view.replication.retainedBytes > 1024 * 1024 * 1024 }">
          复制槽 {{ view.replication.name }} 占用 WAL {{ formatBytes(view.replication.retainedBytes) }}；长期不用请删除任务，或启动它
        </div>
      </div>
    </div>

    <template v-if="live && view.currentInstanceId">
      <div class="section-title">本次运行</div>
      <RunMetricsPanel :metrics="live.metrics" :samples="live.samples" sampling-hint="实时任务每分钟采样一次，运行两分钟后显示速率曲线" />
    </template>

    <template v-if="view.schemaChanges.length">
      <div class="section-title">表结构变更 <span class="hint">运行中每 5 分钟对比一次源表和目标表</span></div>
      <ul class="changes">
        <li v-for="(c, i) in view.schemaChanges" :key="i">
          <el-tag size="small" :type="c.level === 'INFO' ? 'info' : 'warning'">{{ c.level === 'INFO' ? '已同步' : '需要处理' }}</el-tag>
          <span class="time">{{ formatTime(c.time) }}</span>
          <span class="message">{{ c.message }}</span>
        </li>
      </ul>
    </template>

    <JobFlowCard v-if="overview" :flow="overview.flow" :version-no="job.currentVersionNo" />
  </div>
</template>

<style scoped>
.pane {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1100px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .cards {
    grid-template-columns: 1fr;
  }
}

.card {
  min-width: 0;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
}

.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.value {
  margin-top: 8px;
  font-size: 20px;
  font-weight: 600;
}

.value.small {
  font-size: 14px;
  font-weight: 500;
  line-height: 1.6;
}

.sub {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.warn {
  color: var(--el-color-warning-dark-2);
}

.link {
  color: var(--el-color-primary);
  text-decoration: none;
}

.section-title {
  margin-top: 8px;
  font-weight: 500;
}

.hint {
  margin-left: 6px;
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.changes {
  margin: 0;
  padding: 0;
  list-style: none;
}

.changes li {
  display: flex;
  gap: 8px;
  align-items: baseline;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.time {
  flex: none;
  color: var(--el-text-color-secondary);
}

.message {
  line-height: 1.6;
}
</style>

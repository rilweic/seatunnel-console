<script setup lang="ts">
/**
 * How a job is doing (docs/design.md §10.4): key numbers, the run going on now with its live
 * metrics, what it syncs, the trend of the last runs and per-table metrics of the latest one.
 */
import { ArrowRight } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import {
  instanceApi,
  jobApi,
  scheduleApi,
  type IncrementalView,
  type JobOverview,
  type JobView,
  type RunMetrics,
  type RunStatus,
} from '@/api/console'
import EChart from '@/components/EChart.vue'
import RunMetricsPanel from '@/components/RunMetricsPanel.vue'
import JobFlowCard from '@/components/JobFlowCard.vue'
import { formatCompact, formatDuration, formatNumber, formatTime, statusTag, statusText, triggerText } from '@/utils/format'

import WatermarkDialog from './WatermarkDialog.vue'

const props = defineProps<{ job: JobView }>()
const emit = defineEmits<{ showRuns: [] }>()

const router = useRouter()
const overview = ref<JobOverview | null>(null)
const live = ref<RunMetrics | null>(null)
const togglingSchedule = ref(false)
let timer: number | undefined
let disposed = false

const activeRun = computed(() => overview.value?.active[0] ?? null)
/** Watermark of a job whose config reads the sync window (design.md §7.5). */
const incremental = ref<IncrementalView | null>(null)
const watermarkDialog = ref(false)

const lagText = (seconds: number) => (seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`)

async function load() {
  window.clearTimeout(timer)
  const [view, inc] = await Promise.all([jobApi.overview(props.job.id), jobApi.incremental(props.job.id)])
  overview.value = view
  incremental.value = inc
  live.value = activeRun.value ? await instanceApi.metrics(activeRun.value.id) : null
  // Gone meanwhile: a request that was still on its way must not start refreshing again.
  if (!disposed && activeRun.value) {
    timer = window.setTimeout(load, 5000)
  }
}

/** Over runs that ran to an end; a canceled run (a stop or a timeout) says nothing about the job. */
const successRate = computed(() => {
  const s = overview.value?.stats
  return s && s.success + s.failed > 0 ? Math.round((s.success * 100) / (s.success + s.failed)) : null
})

const STATUS_COLOR: Record<RunStatus, string> = {
  SUCCESS: '#52c41a',
  FAILED: '#ff4d4f',
  CANCELED: '#faad14',
  RUNNING: '#1677ff',
  PENDING: '#bfbfbf',
}

const trendOption = computed(() => {
  const trend = overview.value?.trend ?? []
  return {
    grid: { left: 56, right: 64, top: 48, bottom: 28 },
    legend: { top: 0, left: 'center', itemWidth: 12, itemHeight: 8, textStyle: { color: '#8c8c8c' }, data: ['耗时', '写入行数'] },
    tooltip: {
      trigger: 'axis',
      formatter: (items: { dataIndex: number }[]) => {
        const p = trend[items[0]!.dataIndex]!
        return [
          `开始 ${formatTime(p.startedAt)}（${triggerText[p.triggerType]}，计划时间 ${formatTime(p.scheduledTime)}）`,
          `状态：${statusText[p.status]}`,
          `耗时：${formatDuration(p.durationMillis)}`,
          `读 / 写：${formatNumber(p.readRows)} / ${formatNumber(p.writeRows)}`,
        ].join('<br/>')
      },
    },
    xAxis: {
      type: 'category',
      data: trend.map((p) => formatTime(p.startedAt ?? p.scheduledTime).slice(5, 16)),
      axisLine: { lineStyle: { color: '#d9d9d9' } },
      axisLabel: { color: '#8c8c8c' },
    },
    yAxis: [
      {
        type: 'value',
        name: '耗时（秒）',
        nameTextStyle: { color: '#8c8c8c' },
        splitLine: { lineStyle: { color: '#f0f0f0' } },
        axisLabel: { color: '#8c8c8c' },
      },
      {
        type: 'value',
        name: '行数',
        nameTextStyle: { color: '#8c8c8c' },
        splitLine: { show: false },
        axisLabel: { color: '#8c8c8c', formatter: (v: number) => formatCompact(v) },
      },
    ],
    series: [
      {
        name: '耗时',
        type: 'bar',
        barMaxWidth: 18,
        itemStyle: { color: '#1677ff' },
        data: trend.map((p) => ({
          value: p.durationMillis === null ? 0 : Math.round(p.durationMillis / 100) / 10,
          itemStyle: { color: STATUS_COLOR[p.status] },
        })),
      },
      {
        name: '写入行数',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        itemStyle: { color: '#13c2c2' },
        data: trend.map((p) => p.writeRows),
      },
    ],
  }
})

function openTrendRun(index: number) {
  const point = overview.value?.trend[index]
  if (point) router.push(`/instances/${point.id}`)
}

async function toggleSchedule() {
  const schedule = overview.value?.schedule
  if (!schedule) return
  if (schedule.enabled) {
    try {
      await ElMessageBox.confirm('暂停后不再定时运行，已经在运行的不受影响。', '暂停调度', {
        type: 'warning',
        confirmButtonText: '暂停',
        cancelButtonText: '取消',
      })
    } catch {
      return
    }
  }
  togglingSchedule.value = true
  try {
    const { cron, enabled, failRetryCount, retryIntervalSeconds, timeoutSeconds, blockStrategy, misfireStrategy } = schedule
    const saved = await scheduleApi.save(props.job.id, {
      cron,
      enabled: !enabled,
      failRetryCount,
      retryIntervalSeconds,
      timeoutSeconds,
      blockStrategy,
      misfireStrategy,
    })
    if (saved.syncStatus === 'SYNCED') {
      ElMessage.success(saved.enabled ? '调度已启用' : '调度已暂停')
    } else {
      ElMessage.warning('已保存，但同步到 XXL-JOB 失败，请到编辑页的「调度」重新保存')
    }
    await load()
  } finally {
    togglingSchedule.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(timer)
})
defineExpose({ load })
</script>

<template>
  <div v-if="overview" class="overview">
    <div class="cards">
      <div class="card">
        <div class="label">最近运行</div>
        <template v-if="overview.lastRun">
          <div class="value">
            <el-tag :type="statusTag[overview.lastRun.status]">{{ statusText[overview.lastRun.status] }}</el-tag>
          </div>
          <router-link :to="`/instances/${overview.lastRun.id}`" class="sub link">
            {{ formatTime(overview.lastRun.startedAt ?? overview.lastRun.createdAt) }}
          </router-link>
        </template>
        <div v-else class="value muted-value">还没有运行过</div>
      </div>
      <div class="card">
        <div class="label">
          调度
          <el-switch
            v-if="overview.schedule && job.editable"
            :model-value="overview.schedule.enabled"
            :loading="togglingSchedule"
            :disabled="!job.currentVersionNo"
            size="small"
            class="switch"
            @change="toggleSchedule"
          />
        </div>
        <template v-if="overview.schedule?.enabled">
          <div class="value small">{{ formatTime(overview.schedule.nextFireTimes[0]) }}</div>
          <div class="sub">下次运行 · <code>{{ overview.schedule.cron }}</code></div>
        </template>
        <template v-else-if="overview.schedule">
          <div class="value muted-value">已暂停</div>
          <div class="sub"><code>{{ overview.schedule.cron }}</code></div>
        </template>
        <template v-else>
          <div class="value muted-value">未配置</div>
          <router-link v-if="job.editable" :to="`/jobs/${job.id}/edit/schedule`" class="sub link">去配置调度</router-link>
          <div v-else class="sub">只能手动运行</div>
        </template>
      </div>
      <div class="card">
        <div class="label">近 7 天成功率<span class="hint">不含已取消</span></div>
        <div class="value">{{ successRate === null ? '-' : `${successRate}%` }}</div>
        <div class="sub">
          共 {{ overview.stats.runs }} 次 · 成功 {{ overview.stats.success }}
          <span v-if="overview.stats.failed" class="bad"> · 失败 {{ overview.stats.failed }}</span>
          <span v-if="overview.stats.canceled"> · 取消 {{ overview.stats.canceled }}</span>
        </div>
      </div>
      <div class="card">
        <div class="label">近 7 天平均耗时</div>
        <div class="value">{{ formatDuration(overview.stats.avgDurationMillis) }}</div>
        <div class="sub">只统计成功的运行</div>
      </div>
      <div class="card">
        <div class="label">近 7 天写入行数</div>
        <div class="value">{{ formatCompact(overview.stats.writeRows) }}</div>
        <div class="sub">所有结束的运行合计</div>
      </div>
    </div>

    <el-card v-if="activeRun" shadow="never" class="block active">
      <template #header>
        <div class="block-head">
          <span>
            <el-tag :type="statusTag[activeRun.status]">{{ statusText[activeRun.status] }}</el-tag>
            <span class="head-meta">
              {{ triggerText[activeRun.triggerType] }}运行 · 计划时间 {{ formatTime(activeRun.scheduledTime) }}
              <template v-if="activeRun.startedAt"> · 已运行 {{ formatDuration(activeRun.durationMillis) }}</template>
              <template v-if="overview.active.length > 1"> · 另有 {{ overview.active.length - 1 }} 次在排队</template>
            </span>
          </span>
          <router-link :to="`/instances/${activeRun.id}`" class="link">查看运行详情 <el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </template>
      <RunMetricsPanel v-if="live && live.attemptNo" :metrics="live.metrics" :samples="live.samples" />
      <div v-else class="muted">等待提交到 SeaTunnel…</div>
    </el-card>

    <JobFlowCard :flow="overview.flow" :version-no="job.currentVersionNo ?? null" class="block" />

    <el-card v-if="incremental?.usesWindow" shadow="never" class="block">
      <template #header>
        <div class="block-head">
          <span>增量水位<span class="head-meta">每次运行从水位同步到计划时间往前 {{ lagText(incremental.lagSeconds) }}，成功后水位推进到窗口终点</span></span>
          <el-button v-if="job.editable" link type="primary" @click="watermarkDialog = true">调整</el-button>
        </div>
      </template>
      <div class="watermark">
        <div>
          <div class="label">当前水位</div>
          <div class="value small">{{ incremental.watermark ? formatTime(incremental.watermark) : '还没有' }}</div>
          <div class="sub">{{ incremental.watermark ? '已同步到这个时间（不含）' : '下次运行从最早的数据开始' }}</div>
        </div>
        <div>
          <div class="label">如果现在运行，将同步</div>
          <div class="value small">[{{ incremental.nextStart ? formatTime(incremental.nextStart) : '最早' }}, {{ formatTime(incremental.nextEnd) }})</div>
          <div class="sub">失败不推进水位，下次运行会把这段一起同步</div>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="block trend-card">
      <template #header>最近 {{ overview.trend.length }} 次运行<span class="head-meta">柱子颜色表示结果，点击柱子查看那次运行</span></template>
      <EChart v-if="overview.trend.length" :option="trendOption" height="240px" @click="(i: number) => openTrendRun(i)" />
      <div v-else class="muted empty">还没有结束的运行</div>
    </el-card>

    <el-card v-if="overview.metricsInstanceId && overview.metricsInstanceId !== activeRun?.id" shadow="never" class="block">
      <template #header>
        <div class="block-head">
          <span>最近一次运行的引擎指标</span>
          <router-link :to="`/instances/${overview.metricsInstanceId}`" class="link">查看那次运行 <el-icon><ArrowRight /></el-icon></router-link>
        </div>
      </template>
      <RunMetricsPanel :metrics="overview.metrics" :show-chart="false" />
    </el-card>

    <el-card shadow="never" class="block">
      <template #header>
        <div class="block-head">
          <span>最近结束的运行</span>
          <el-button link type="primary" @click="emit('showRuns')">查看全部运行记录</el-button>
        </div>
      </template>
      <el-table :data="[...overview.trend].reverse().slice(0, 10)" class="clickable" empty-text="还没有结束的运行" @row-click="(row: { id: string }) => router.push(`/instances/${row.id}`)">
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag[row.status as RunStatus]">{{ statusText[row.status as RunStatus] }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发" width="80">
          <template #default="{ row }">{{ triggerText[row.triggerType] }}</template>
        </el-table-column>
        <el-table-column label="计划时间" width="170">
          <template #default="{ row }">{{ formatTime(row.scheduledTime) }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ formatDuration(row.durationMillis) }}</template>
        </el-table-column>
        <el-table-column label="读 / 写行数" min-width="140">
          <template #default="{ row }">{{ formatNumber(row.readRows) }} / {{ formatNumber(row.writeRows) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
  <div v-else v-loading="true" class="loading" />
  <WatermarkDialog v-model="watermarkDialog" :job-id="job.id" :incremental="incremental" @saved="(v) => (incremental = v)" />
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 1200px) {
  .cards {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .cards {
    grid-template-columns: 1fr;
  }
}

.card {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: var(--el-bg-color);
  min-width: 0;
}

.label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.value {
  margin-top: 8px;
  font-size: 24px;
  font-weight: 500;
  line-height: 32px;
  font-variant-numeric: tabular-nums;
}

.value.small {
  font-size: 16px;
}

.muted-value {
  font-size: 16px;
  color: var(--el-text-color-secondary);
}

.sub {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.link {
  color: var(--el-color-primary);
  text-decoration: none;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 13px;
}

.sub.link {
  font-size: 12px;
}

.block {
  margin-top: 16px;
}

.watermark {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 700px) {
  .watermark {
    grid-template-columns: 1fr;
  }
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.head-meta {
  margin-left: 8px;
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}













.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.empty {
  padding: 48px 0;
  text-align: center;
}

.bad {
  color: var(--el-color-danger);
}

.hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.switch {
  margin-left: 8px;
}

.loading {
  height: 240px;
}
</style>

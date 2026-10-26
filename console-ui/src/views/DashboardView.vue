<script setup lang="ts">
/**
 * The home page after login (docs/design.md §10.5): how the jobs run today and lately, what runs
 * now, what failed, what comes next. Refreshes itself; can go full screen for a wall display.
 */
import { FullScreen, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { dashboardApi, type Dashboard, type DashboardJobStat, type InstanceView, type RunStatus } from '@/api/console'
import EChart from '@/components/EChart.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'
import { formatCompact, formatDuration, formatTime, statusTag, statusText, triggerText } from '@/utils/format'
import { ago } from '@/utils/stream'

const REFRESH_MS = 30_000

const router = useRouter()
const data = ref<Dashboard | null>(null)
const loadedAt = ref<Date | null>(null)
const loading = ref(false)
const root = ref<HTMLElement | null>(null)
const fullscreen = ref(false)
const { state: query, apply } = useQueryState({ mine: false }, () => load())
let timer: number | undefined
let disposed = false

async function load() {
  window.clearTimeout(timer)
  loading.value = data.value === null
  try {
    const loaded = await dashboardApi.get(query.mine)
    if (disposed) return
    data.value = loaded
    loadedAt.value = new Date()
  } catch {
    // reported by the HTTP layer; the next refresh tries again
  } finally {
    loading.value = false
    if (!disposed) timer = window.setTimeout(load, REFRESH_MS)
  }
}

const successRate = computed(() =>
  data.value?.successRate7d === null || data.value?.successRate7d === undefined ? null : Math.round(data.value.successRate7d * 100),
)
const todayRuns = computed(() => {
  const t = data.value?.today
  return t ? t.success + t.failed + t.canceled + t.running : 0
})

const trendOption = computed(() => {
  const trend = data.value?.trend ?? []
  const axis = { color: '#8c8c8c' }
  return {
    color: ['#52c41a', '#ff4d4f', '#faad14', '#13c2c2'],
    grid: { left: 48, right: 64, top: 48, bottom: 28 },
    legend: { top: 0, left: 'center', itemWidth: 12, itemHeight: 8, textStyle: axis },
    tooltip: { trigger: 'axis' },
    xAxis: {
      type: 'category',
      data: trend.map((d) => d.date.slice(5)),
      axisLine: { lineStyle: { color: '#d9d9d9' } },
      axisLabel: axis,
    },
    yAxis: [
      { type: 'value', name: '运行次数', minInterval: 1, nameTextStyle: axis, axisLabel: axis, splitLine: { lineStyle: { color: '#f0f0f0' } } },
      {
        type: 'value',
        name: '写入行数',
        nameTextStyle: axis,
        axisLabel: { ...axis, formatter: (v: number) => formatCompact(v) },
        splitLine: { show: false },
      },
    ],
    series: [
      { name: '成功', type: 'bar', stack: 'runs', barMaxWidth: 22, data: trend.map((d) => d.success) },
      { name: '失败', type: 'bar', stack: 'runs', barMaxWidth: 22, data: trend.map((d) => d.failed) },
      { name: '已取消', type: 'bar', stack: 'runs', barMaxWidth: 22, data: trend.map((d) => d.canceled) },
      { name: '写入行数', type: 'line', yAxisIndex: 1, smooth: true, data: trend.map((d) => d.writeRows) },
    ],
  }
})

/** Bar width in a ranking, relative to the first (largest) entry. */
function share(list: DashboardJobStat[], value: (s: DashboardJobStat) => number, item: DashboardJobStat) {
  const top = list.length ? value(list[0]!) : 0
  return top > 0 ? `${Math.max(4, Math.round((value(item) * 100) / top))}%` : '0%'
}

const clusterText = computed(() => {
  const c = data.value?.cluster
  if (!c || Object.keys(c).length === 0) return null
  return { workers: c.workers ?? '-', running: c.runningJobs ?? '-', pending: c.pendingJobs ?? '-', version: c.projectVersion ?? '' }
})

function openRun(row: InstanceView) {
  router.push(`/instances/${row.id}`)
}

async function toggleFullscreen() {
  try {
    if (document.fullscreenElement) {
      await document.exitFullscreen()
    } else {
      await root.value?.requestFullscreen()
    }
  } catch {
    // e.g. refused by the browser or an embedding page
    ElMessage.warning('浏览器没有允许全屏，可以按 F11 让整个窗口全屏')
  }
}

function onFullscreenChange() {
  fullscreen.value = document.fullscreenElement === root.value
}

onMounted(() => {
  load()
  document.addEventListener('fullscreenchange', onFullscreenChange)
})
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(timer)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
})
</script>

<template>
  <div ref="root" class="dashboard" :class="{ fullscreen }">
    <el-card shadow="never" class="head-card">
      <PageHeader title="概览" :description="`任务的运行情况，每 30 秒自动刷新${loadedAt ? '，更新于 ' + loadedAt.toLocaleTimeString('zh-CN') : ''}`">
        <el-radio-group v-model="query.mine" class="scope" @change="apply()">
          <el-radio-button :value="false">全部</el-radio-button>
          <el-radio-button :value="true">我的</el-radio-button>
        </el-radio-group>
        <el-button :icon="Refresh" @click="load()">刷新</el-button>
        <el-button :icon="FullScreen" @click="toggleFullscreen">{{ fullscreen ? '退出全屏' : '全屏' }}</el-button>
      </PageHeader>
    </el-card>

    <template v-if="data">
      <div class="kpis">
        <div class="kpi clickable" @click="router.push({ path: '/jobs', query: query.mine ? {} : { mine: 'false' } })">
          <div class="label">离线任务</div>
          <div class="value">{{ data.jobs }}</div>
          <div class="sub">已启用调度 {{ data.scheduledJobs }}</div>
        </div>
        <div class="kpi clickable" @click="router.push({ path: '/instances', query: { status: 'RUNNING', ...(query.mine ? {} : { mine: 'false' }) } })">
          <div class="label">正在运行</div>
          <div class="value" :class="{ running: data.runningRuns > 0 }">{{ data.runningRuns }}</div>
          <div class="sub">排队等待 {{ data.pendingRuns }}</div>
        </div>
        <div class="kpi">
          <div class="label">今日运行</div>
          <div class="value">{{ todayRuns }}</div>
          <div class="sub">
            <span class="ok">成功 {{ data.today.success }}</span>
            <span :class="{ bad: data.today.failed > 0 }"> · 失败 {{ data.today.failed }}</span>
            <span v-if="data.today.canceled"> · 取消 {{ data.today.canceled }}</span>
          </div>
        </div>
        <div class="kpi">
          <div class="label">近 7 天成功率</div>
          <div class="value" :class="{ bad: successRate !== null && successRate < 90 }">{{ successRate === null ? '-' : `${successRate}%` }}</div>
          <div class="sub">成功 ÷（成功 + 失败），不含已取消</div>
        </div>
        <div class="kpi">
          <div class="label">今日写入行数</div>
          <div class="value">{{ formatCompact(data.today.writeRows) }}</div>
          <div class="sub">近 7 天 {{ formatCompact(data.writeRows7d) }}</div>
        </div>
        <div class="kpi clickable" @click="router.push({ path: '/realtime', query: query.mine ? {} : { mine: 'false' } })">
          <div class="label">实时同步</div>
          <div class="value" :class="{ running: data.realtime.running > 0 }">{{ data.realtime.running }}<span class="unit">个运行中</span></div>
          <div class="sub">
            <span :class="{ bad: data.realtime.abnormal > 0 }">异常 {{ data.realtime.abnormal }}</span> · 共 {{ data.realtime.jobs }} 个
            <template v-if="data.realtime.quietestReadAt"><br />最久没读到数据：{{ ago(data.realtime.quietestReadAt) }}</template>
          </div>
        </div>
        <div class="kpi clickable" @click="router.push('/cluster')">
          <div class="label">SeaTunnel 集群</div>
          <template v-if="clusterText">
            <div class="value">{{ clusterText.workers }}<span class="unit">个节点</span></div>
            <div class="sub">运行中作业 {{ clusterText.running }} · 排队 {{ clusterText.pending }}</div>
          </template>
          <template v-else>
            <div class="value bad small">无法连接</div>
            <div class="sub">点击查看集群健康检查</div>
          </template>
        </div>
      </div>

      <div class="row wide">
        <el-card shadow="never">
          <template #header>近 14 天运行<span class="head-meta">按运行创建的日期统计</span></template>
          <EChart :option="trendOption" height="280px" />
        </el-card>
        <el-card shadow="never" class="list-card">
          <template #header>
            <div class="block-head">
              <span>正在运行</span>
              <router-link :to="{ path: '/instances', query: { status: 'RUNNING', ...(query.mine ? {} : { mine: 'false' }) } }" class="link">全部</router-link>
            </div>
          </template>
          <div v-if="data.active.length === 0" class="empty">当前没有运行中的任务</div>
          <ul v-else class="items">
            <li v-for="r in data.active" :key="r.id" class="item" @click="openRun(r)">
              <el-tag :type="statusTag[r.status as RunStatus]">{{ statusText[r.status as RunStatus] }}</el-tag>
              <span class="item-name">{{ r.jobName }}</span>
              <span class="item-meta">{{ triggerText[r.triggerType] }} · {{ r.startedAt ? `已运行 ${formatDuration(r.durationMillis)}` : '等待开始' }}</span>
            </li>
          </ul>
        </el-card>
      </div>

      <div class="row half">
        <el-card shadow="never">
          <template #header>
            <div class="block-head">
              <span>最近失败</span>
              <router-link :to="{ path: '/instances', query: { status: 'FAILED', ...(query.mine ? {} : { mine: 'false' }) } }" class="link">全部</router-link>
            </div>
          </template>
          <el-table :data="data.recentFailures" class="clickable" empty-text="没有失败的运行" @row-click="openRun">
            <el-table-column prop="jobName" label="任务" min-width="150" show-overflow-tooltip />
            <el-table-column label="结束时间" width="150">
              <template #default="{ row }">{{ formatTime(row.finishedAt).slice(5) }}</template>
            </el-table-column>
            <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
          </el-table>
        </el-card>
        <el-card shadow="never">
          <template #header>未来 24 小时的调度</template>
          <el-table :data="data.upcoming" empty-text="未来 24 小时没有定时运行" class="clickable" @row-click="(row: { jobId: string }) => router.push(`/jobs/${row.jobId}`)">
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ formatTime(row.time).slice(5) }}</template>
            </el-table-column>
            <el-table-column prop="jobName" label="任务" min-width="150" show-overflow-tooltip />
            <el-table-column label="Cron" width="150">
              <template #default="{ row }"><code>{{ row.cron }}</code></template>
            </el-table-column>
          </el-table>
        </el-card>
      </div>

      <div class="row half">
        <el-card shadow="never">
          <template #header>近 7 天写入最多的任务</template>
          <div v-if="data.topByRows.length === 0" class="empty">近 7 天没有结束的运行</div>
          <ol v-else class="ranking">
            <li v-for="s in data.topByRows" :key="s.jobId" @click="router.push(`/jobs/${s.jobId}`)">
              <div class="rank-line">
                <span class="rank-name">{{ s.jobName }}</span>
                <span class="rank-value">{{ formatCompact(s.writeRows) }} 行 · {{ s.runs }} 次</span>
              </div>
              <div class="bar"><span :style="{ width: share(data.topByRows, (x) => x.writeRows, s) }" /></div>
            </li>
          </ol>
        </el-card>
        <el-card shadow="never">
          <template #header>近 7 天平均耗时最长的任务<span class="head-meta">只算成功的运行</span></template>
          <div v-if="data.slowest.length === 0" class="empty">近 7 天没有成功的运行</div>
          <ol v-else class="ranking">
            <li v-for="s in data.slowest" :key="s.jobId" @click="router.push(`/jobs/${s.jobId}`)">
              <div class="rank-line">
                <span class="rank-name">{{ s.jobName }}</span>
                <span class="rank-value">平均 {{ formatDuration(s.avgDurationMillis) }} · {{ s.runs }} 次</span>
              </div>
              <div class="bar slow"><span :style="{ width: share(data.slowest, (x) => x.avgDurationMillis ?? 0, s) }" /></div>
            </li>
          </ol>
        </el-card>
      </div>
    </template>
    <div v-else v-loading="loading" class="placeholder" />
  </div>
</template>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* full screen for a wall display: the page's own background and some room around it */
.dashboard.fullscreen {
  padding: 24px;
  overflow: auto;
  background: var(--el-bg-color-page);
}

.head-card :deep(.page-header) {
  margin-bottom: 0;
}

.scope {
  flex: none;
}

.kpis {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 1300px) {
  .kpis {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .kpis {
    grid-template-columns: 1fr;
  }
}

.kpi {
  padding: 20px;
  background: var(--el-bg-color);
  border-radius: 8px;
  min-width: 0;
}

.kpi.clickable {
  cursor: pointer;
}

.kpi.clickable:hover {
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.value {
  margin-top: 8px;
  font-size: 28px;
  font-weight: 500;
  line-height: 36px;
  font-variant-numeric: tabular-nums;
}

.value.small {
  font-size: 18px;
}

.value.running {
  color: var(--el-color-primary);
}

.unit {
  margin-left: 4px;
  font-size: 13px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.sub {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ok {
  color: var(--el-color-success);
}

.bad {
  color: var(--el-color-danger);
}

.row {
  display: grid;
  gap: 16px;
}

.row.wide {
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
}

.row.half {
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}

@media (max-width: 1100px) {
  .row.wide,
  .row.half {
    grid-template-columns: 1fr;
  }
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.head-meta {
  margin-left: 8px;
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.link {
  font-size: 13px;
  color: var(--el-color-primary);
  text-decoration: none;
}

.items {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 280px;
  overflow: auto;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
  cursor: pointer;
  font-size: 13px;
}

.item:last-child {
  border-bottom: none;
}

.item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-meta {
  flex: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.ranking {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ranking li {
  padding: 8px 0;
  cursor: pointer;
}

.rank-line {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
}

.rank-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-value {
  flex: none;
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}

.bar {
  margin-top: 6px;
  height: 6px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  overflow: hidden;
}

.bar span {
  display: block;
  height: 100%;
  border-radius: 3px;
  background: #1677ff;
}

.bar.slow span {
  background: #faad14;
}

.empty {
  padding: 40px 0;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.placeholder {
  height: 320px;
}
</style>

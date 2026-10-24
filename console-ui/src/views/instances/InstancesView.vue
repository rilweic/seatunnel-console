<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { instanceApi, projectApi, type InstanceListFilters, type InstanceView, type ProjectView, type RunStatus } from '@/api/console'
import { formatDuration, formatNumber, formatTime, isActive, statusTag, statusText, triggerText } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'
import ValidationTag from '@/components/ValidationTag.vue'

type Period = '' | 'LAST_30_MINUTES' | 'LAST_1_HOUR' | 'LAST_6_HOURS' | 'LAST_12_HOURS' | 'TODAY' | 'YESTERDAY' | 'WEEK' | 'LAST_7_DAYS' | 'CUSTOM'

const periodOptions: { value: Period; label: string }[] = [
  { value: 'LAST_30_MINUTES', label: '过去 30 分钟' },
  { value: 'LAST_1_HOUR', label: '过去 1 小时' },
  { value: 'LAST_6_HOURS', label: '过去 6 小时' },
  { value: 'LAST_12_HOURS', label: '过去 12 小时' },
  { value: 'TODAY', label: '今天' },
  { value: 'YESTERDAY', label: '昨天' },
  { value: 'WEEK', label: '本周' },
  { value: 'LAST_7_DAYS', label: '近 7 天' },
  { value: 'CUSTOM', label: '自定义' },
]

const recentPeriodMinutes: Partial<Record<Period, number>> = {
  LAST_30_MINUTES: 30,
  LAST_1_HOUR: 60,
  LAST_6_HOURS: 360,
  LAST_12_HOURS: 720,
}

const router = useRouter()
const rows = ref<InstanceView[]>([])
const projects = ref<ProjectView[]>([])
const total = ref<number | null>(null)
const totalLoading = ref(false)
const hasMore = ref(false)
const { state: query, apply } = useQueryState(
  { projectId: '', jobName: '', period: '' as Period, startedFrom: '', startedTo: '', status: '', mine: true, size: 20 },
  () => load(),
)
const loading = ref(false)
const loadingMore = ref(false)
let cursorTime: string | undefined
let cursorId: string | undefined
let listSeq = 0
let totalSeq = 0
let timer: number | undefined
let disposed = false

const customRange = computed<[string, string] | null>({
  get: () => (query.startedFrom && query.startedTo ? ([query.startedFrom, query.startedTo] as [string, string]) : null),
  set: (value) => {
    query.startedFrom = value?.[0] ?? ''
    query.startedTo = value?.[1] ?? ''
  },
})

function startOfDay(offset = 0) {
  const date = new Date()
  date.setHours(0, 0, 0, 0)
  date.setDate(date.getDate() + offset)
  return date
}

function localDateTime(date: Date) {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`
}

function periodRange(period: Period) {
  if (!period || period === 'CUSTOM') return {}
  const minutes = recentPeriodMinutes[period]
  if (minutes) {
    const to = new Date()
    return {
      startedFrom: localDateTime(new Date(to.getTime() - minutes * 60_000)),
      startedTo: localDateTime(to),
    }
  }
  const today = startOfDay()
  let from = today
  if (period === 'YESTERDAY') from = startOfDay(-1)
  if (period === 'WEEK') from = startOfDay(-((today.getDay() + 6) % 7))
  if (period === 'LAST_7_DAYS') from = startOfDay(-6)
  return {
    startedFrom: localDateTime(from),
    startedTo: localDateTime(period === 'YESTERDAY' ? today : startOfDay(1)),
  }
}

function filters(): InstanceListFilters {
  const { period, size, startedFrom, startedTo, ...rest } = query
  if (period === 'CUSTOM') {
    return { ...rest, startedFrom: startedFrom || undefined, startedTo: startedTo || undefined }
  }
  return { ...rest, ...periodRange(period) }
}

async function loadTotal(seq: number, params: InstanceListFilters) {
  totalLoading.value = true
  try {
    const count = await instanceApi.count(params)
    if (seq === totalSeq) total.value = count
  } catch {
    if (seq === totalSeq) total.value = null
  } finally {
    if (seq === totalSeq) totalLoading.value = false
  }
}

function scheduleRefresh() {
  window.clearTimeout(timer)
  if (!disposed && rows.value.some((r) => isActive(r.status))) {
    timer = window.setTimeout(() => load(true), 5000)
  }
}

async function load(silent = false) {
  const seq = ++listSeq
  loadingMore.value = false
  const params = filters()
  if (!silent) {
    cursorTime = undefined
    cursorId = undefined
    hasMore.value = false
    total.value = null
    loading.value = true
    loadTotal(++totalSeq, params)
  }
  try {
    const result = await instanceApi.feed({ ...params, size: query.size })
    if (seq !== listSeq) return
    if (silent) {
      const firstPage = new Set(result.items.map((row) => row.id))
      const loadedMore = rows.value.length > query.size
      rows.value = [...result.items, ...rows.value.filter((row) => !firstPage.has(row.id))]
      if (!loadedMore) {
        hasMore.value = result.hasMore
        cursorTime = result.nextCursorTime ?? undefined
        cursorId = result.nextCursorId ?? undefined
      }
    } else {
      rows.value = result.items
      hasMore.value = result.hasMore
      cursorTime = result.nextCursorTime ?? undefined
      cursorId = result.nextCursorId ?? undefined
    }
  } catch {
    if (!silent && seq === listSeq) rows.value = []
  } finally {
    if (seq === listSeq) loading.value = false
    scheduleRefresh()
  }
}

async function loadMore() {
  if (loadingMore.value || !hasMore.value) return
  const seq = listSeq
  loadingMore.value = true
  try {
    const result = await instanceApi.feed({ ...filters(), size: query.size, cursorTime, cursorId })
    if (seq !== listSeq) return
    const loaded = new Set(rows.value.map((row) => row.id))
    rows.value.push(...result.items.filter((row) => !loaded.has(row.id)))
    hasMore.value = result.hasMore
    cursorTime = result.nextCursorTime ?? undefined
    cursorId = result.nextCursorId ?? undefined
  } finally {
    if (seq === listSeq) loadingMore.value = false
  }
}

function applyCustomRange() {
  if (query.startedFrom && query.startedTo && query.startedFrom >= query.startedTo) {
    ElMessage.warning('开始时间必须早于结束时间')
    return
  }
  apply()
}

async function loadProjects() {
  projects.value = (await projectApi.list({ size: 200 })).items
}

onMounted(() => {
  load()
  loadProjects()
})
onBeforeUnmount(() => {
  disposed = true
  window.clearTimeout(timer)
})
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="运行实例" description="所有任务的运行记录，包括手动、定时、重跑和补数。点击一行查看事件时间线和 SeaTunnel 作业日志。">
      <el-button :icon="Refresh" @click="load()">刷新</el-button>
    </PageHeader>
    <div class="toolbar">
      <el-radio-group v-model="query.mine" class="scope" @change="apply()">
        <el-radio-button :value="true">我的</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>
      <el-select v-model="query.status" placeholder="全部状态" clearable class="status" @change="apply()">
        <el-option v-for="(label, value) in statusText" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="query.period" placeholder="全部时间" clearable class="period" @change="apply()">
        <el-option v-for="option in periodOptions" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
      <el-date-picker
        v-if="query.period === 'CUSTOM'"
        v-model="customRange"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        format="YYYY-MM-DD HH:mm:ss"
        start-placeholder="开始日期时间"
        end-placeholder="结束日期时间"
        range-separator="至"
        class="custom-range"
        @change="applyCustomRange"
      />
      <el-select v-model="query.projectId" placeholder="全部项目" clearable filterable class="project" @change="apply()">
        <el-option v-for="project in projects" :key="project.id" :label="project.name" :value="project.id" />
      </el-select>
      <el-input
        v-model="query.jobName"
        placeholder="搜索任务名称"
        clearable
        class="job-name"
        @keyup.enter="apply()"
        @clear="apply()"
        @change="apply()"
      />
    </div>
    <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: InstanceView) => router.push(`/instances/${row.id}`)">
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status as RunStatus]">{{ statusText[row.status as RunStatus] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip>
        <template #default="{ row }">{{ row.projectName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="任务" min-width="180">
        <template #default="{ row }">{{ row.jobName }} <span class="muted">v{{ row.versionNo }}</span></template>
      </el-table-column>
      <el-table-column label="触发" width="80">
        <template #default="{ row }">{{ triggerText[row.triggerType] }}</template>
      </el-table-column>
      <el-table-column label="开始时间" width="170">
        <template #default="{ row }">{{ formatTime(row.startedAt ?? row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="耗时" width="100">
        <template #default="{ row }">{{ formatDuration(row.durationMillis) }}</template>
      </el-table-column>
      <el-table-column label="读 / 写行数" width="140">
        <template #default="{ row }">{{ formatNumber(row.readRows) }} / {{ formatNumber(row.writeRows) }}</template>
      </el-table-column>
      <el-table-column label="校验" width="130">
        <template #default="{ row }"><ValidationTag :instance="row" /></template>
      </el-table-column>
      <el-table-column label="触发人" width="110">
        <template #default="{ row }">{{ row.createdByName ?? (row.triggerType === 'MANUAL' || row.triggerType === 'RERUN' ? '-' : '调度') }}</template>
      </el-table-column>
      <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
    </el-table>
    <div class="feed-footer">
      <el-select v-model="query.size" class="load-size" aria-label="每次加载条数" @change="apply()">
        <el-option v-for="size in [10, 20, 50, 100]" :key="size" :label="`${size} 条/次`" :value="size" />
      </el-select>
      <div class="more">
        <el-button v-if="hasMore" :loading="loadingMore" @click="loadMore">查看更多</el-button>
        <span v-else-if="rows.length" class="muted">没有更多了</span>
      </div>
      <span class="total">
        <template v-if="totalLoading">总数统计中…</template>
        <template v-else-if="total !== null">共 {{ formatNumber(total) }} 条</template>
        <template v-else>总数不可用</template>
      </span>
    </div>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
}

.status,
.period {
  width: 140px;
}

.project {
  width: 180px;
}

.job-name {
  width: 220px;
}

.custom-range {
  width: 420px;
}

.feed-footer {
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  margin-top: 16px;
}

.load-size {
  width: 110px;
  justify-self: start;
}

.more {
  justify-self: center;
}

.total {
  justify-self: end;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.scope {
  flex: none;
}

@media (max-width: 640px) {
  .custom-range {
    width: 100%;
  }

  .feed-footer {
    grid-template-columns: auto 1fr;
    gap: 12px;
  }

  .more {
    justify-self: end;
  }

  .total {
    grid-column: 1 / -1;
  }
}
</style>

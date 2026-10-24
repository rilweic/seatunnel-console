<script setup lang="ts">
/** Backfill batches of a job with their runs; refreshes itself while one is going. */
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { backfillApi, instanceApi, type BackfillView, type InstanceView, type RunStatus } from '@/api/console'
import ValidationTag from '@/components/ValidationTag.vue'
import { formatDuration, formatNumber, formatTime, statusTag, statusText } from '@/utils/format'

const props = defineProps<{ jobId: string; readonly: boolean }>()

const router = useRouter()
const backfills = ref<BackfillView[]>([])
/** Runs of each expanded batch, a page at a time (a batch can have up to a few thousand). */
const backfillRuns = ref<Record<string, { items: InstanceView[]; total: number; page: number }>>({})
const RUNS_PAGE = 20
const loading = ref(false)
let timer: number | undefined

const backfillStatusText = { RUNNING: '进行中', PAUSED: '已暂停', FINISHED: '已完成', CANCELED: '已取消' } as const
const backfillStatusTag = { RUNNING: 'primary', PAUSED: 'warning', FINISHED: 'success', CANCELED: 'info' } as const

/** Scheduled times already run to the end; a run XXL-JOB never took is retried and not counted. */
function done(b: BackfillView): number {
  return b.triggered - (b.statusCounts.RUNNING ?? 0) - (b.statusCounts.PENDING ?? 0)
}

async function load() {
  window.clearTimeout(timer)
  loading.value = backfills.value.length === 0
  try {
    backfills.value = await backfillApi.list(props.jobId)
  } finally {
    loading.value = false
  }
  for (const id of Object.keys(backfillRuns.value)) {
    loadRuns(id, runsPage[id] ?? 1)
  }
  if (backfills.value.some((b) => b.status === 'RUNNING')) {
    timer = window.setTimeout(load, 5000)
  }
}

/** Latest request per batch: a slower answer for an earlier page (or refresh) must not replace it. */
const runsSeq: Record<string, number> = {}
/** The page last asked for, which the periodic refresh reloads (not the one last shown). */
const runsPage: Record<string, number> = {}

async function loadRuns(batchId: string, page = 1) {
  runsPage[batchId] = page
  const seq = (runsSeq[batchId] = (runsSeq[batchId] ?? 0) + 1)
  const result = await instanceApi.list({ backfillId: batchId, page, size: RUNS_PAGE })
  if (seq === runsSeq[batchId]) {
    backfillRuns.value[batchId] = { items: result.items, total: result.total, page }
  }
}

async function cancel(b: BackfillView) {
  await backfillApi.cancel(b.id)
  ElMessage.success('补数已取消')
  load()
}

async function resume(b: BackfillView) {
  await backfillApi.resume(b.id)
  ElMessage.success('补数已继续')
  load()
}

onMounted(load)
onBeforeUnmount(() => window.clearTimeout(timer))
defineExpose({ load })
</script>

<template>
  <el-table v-loading="loading" :data="backfills" row-key="id" empty-text="还没有补数。点右上角「补数」按日期范围补跑历史数据" @expand-change="(row: BackfillView) => loadRuns(row.id)">
    <el-table-column type="expand">
      <template #default="{ row }">
        <el-table :data="backfillRuns[row.id]?.items ?? []" class="inner clickable" @row-click="(r: InstanceView) => router.push(`/instances/${r.id}`)">
          <el-table-column label="计划时间" width="170">
            <template #default="{ row: r }">{{ formatTime(r.scheduledTime) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row: r }">
              <el-tag :type="statusTag[r.status as RunStatus]">{{ statusText[r.status as RunStatus] }}</el-tag>
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
          <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
        </el-table>
        <el-pagination
          v-if="(backfillRuns[row.id]?.total ?? 0) > RUNS_PAGE"
          :current-page="backfillRuns[row.id]!.page"
          :page-size="RUNS_PAGE"
          :total="backfillRuns[row.id]!.total"
          layout="total, prev, pager, next"
          small
          class="inner-pager"
          @current-change="(p: number) => loadRuns(row.id, p)"
        />
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
        <el-progress :percentage="Math.round((done(row) * 100) / row.totalRuns)" :format="() => `${done(row)}/${row.totalRuns}`" />
      </template>
    </el-table-column>
    <el-table-column label="结果" width="170">
      <template #default="{ row }">
        <span class="ok">成功 {{ row.statusCounts.SUCCESS ?? 0 }}</span>
        <span v-if="row.statusCounts.FAILED" class="bad"> · 失败 {{ row.statusCounts.FAILED }}</span>
        <span v-if="(row.statusCounts.RUNNING ?? 0) + (row.statusCounts.PENDING ?? 0)" class="muted"> · 运行中 1</span>
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
        <el-button v-if="row.status === 'PAUSED' && !readonly" link type="primary" @click.stop="resume(row)">继续</el-button>
        <el-popconfirm v-if="(row.status === 'RUNNING' || row.status === 'PAUSED') && !readonly" title="取消补数？正在运行的那次也会被停止。" @confirm="cancel(row)">
          <template #reference>
            <el-button link type="danger" @click.stop>取消</el-button>
          </template>
        </el-popconfirm>
        <span v-if="readonly || row.status === 'FINISHED' || row.status === 'CANCELED'" class="muted">-</span>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.inner {
  margin: 0 16px 0 48px;
  width: auto;
}

.inner-pager {
  margin: 8px 16px 8px 48px;
}

.ok {
  color: var(--el-color-success);
}

.bad {
  color: var(--el-color-danger);
}

.muted {
  color: var(--el-text-color-secondary);
}
</style>

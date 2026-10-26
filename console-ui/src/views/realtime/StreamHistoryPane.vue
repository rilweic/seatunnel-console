<script setup lang="ts">
/** Every start of a real-time job until it stopped or failed (design.md §12 P2); a row opens its timeline and logs. */
import { Refresh } from '@element-plus/icons-vue'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { instanceApi, type InstanceView, type RunStatus } from '@/api/console'
import { formatDuration, formatNumber, formatTime } from '@/utils/format'

const props = defineProps<{ jobId: string }>()
const router = useRouter()
const rows = ref<InstanceView[]>([])
const total = ref(0)
const page = ref(1)
const loading = ref(false)

/** A stop is the normal end of a real-time run, so it reads as stopped rather than canceled. */
const resultText: Record<RunStatus, string> = { PENDING: '启动中', RUNNING: '运行中', SUCCESS: '已结束', FAILED: '失败', CANCELED: '已停止' }
const resultTag: Record<RunStatus, 'info' | 'primary' | 'success' | 'danger' | 'warning'> = {
  PENDING: 'info',
  RUNNING: 'success',
  SUCCESS: 'info',
  FAILED: 'danger',
  CANCELED: 'info',
}

async function load() {
  loading.value = true
  try {
    const result = await instanceApi.list({ jobId: props.jobId, page: page.value, size: 20 })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ load })
</script>

<template>
  <div class="toolbar">
    <el-button :icon="Refresh" @click="load">刷新</el-button>
  </div>
  <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: InstanceView) => router.push(`/instances/${row.id}`)">
    <el-table-column label="启动时间" width="170">
      <template #default="{ row }">{{ formatTime(row.startedAt ?? row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="结果" width="100">
      <template #default="{ row }"><el-tag :type="resultTag[row.status as RunStatus]">{{ resultText[row.status as RunStatus] }}</el-tag></template>
    </el-table-column>
    <el-table-column label="运行时长" width="120">
      <template #default="{ row }">{{ row.durationMillis != null ? formatDuration(row.durationMillis) : '-' }}</template>
    </el-table-column>
    <el-table-column label="版本" width="70">
      <template #default="{ row }">{{ row.versionNo ? `v${row.versionNo}` : '-' }}</template>
    </el-table-column>
    <el-table-column label="自动重启" width="90">
      <template #default="{ row }">{{ row.attemptCount > 1 ? `${row.attemptCount - 1} 次` : '-' }}</template>
    </el-table-column>
    <el-table-column label="读 / 写行数" width="160">
      <template #default="{ row }">{{ formatNumber(row.readRows) }} / {{ formatNumber(row.writeRows) }}</template>
    </el-table-column>
    <el-table-column label="说明" min-width="260" show-overflow-tooltip>
      <template #default="{ row }">{{ row.errorMessage ?? '' }}</template>
    </el-table-column>
    <el-table-column label="操作人" width="110">
      <template #default="{ row }">{{ row.createdByName ?? '-' }}</template>
    </el-table-column>
  </el-table>
  <el-pagination v-model:current-page="page" :total="total" :page-size="20" layout="total, prev, pager, next" @current-change="load" />
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
</style>

<script setup lang="ts">
/** All runs of one job, filterable by status; refreshes itself while any run on the page is active. */
import { Refresh } from '@element-plus/icons-vue'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { instanceApi, type InstanceView, type RunStatus } from '@/api/console'
import ValidationTag from '@/components/ValidationTag.vue'
import { useQueryState } from '@/composables/useQueryState'
import { formatDuration, formatNumber, formatTime, isActive, statusTag, statusText, triggerText } from '@/utils/format'

const props = defineProps<{ jobId: string }>()

const router = useRouter()
const rows = ref<InstanceView[]>([])
const total = ref(0)
const loading = ref(false)
const { state: query, apply } = useQueryState({ status: '', page: 1, size: 20 }, () => load())
let timer: number | undefined

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const result = await instanceApi.list({ jobId: props.jobId, status: query.status || undefined, page: query.page, size: query.size })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
  window.clearTimeout(timer)
  if (rows.value.some((r) => isActive(r.status))) {
    timer = window.setTimeout(() => load(true), 5000)
  }
}

onMounted(() => load())
onBeforeUnmount(() => window.clearTimeout(timer))
defineExpose({ load })
</script>

<template>
  <div class="toolbar">
    <el-select v-model="query.status" placeholder="全部状态" clearable class="status" @change="apply()">
      <el-option v-for="(label, value) in statusText" :key="value" :label="label" :value="value" />
    </el-select>
    <el-button :icon="Refresh" @click="load()">刷新</el-button>
  </div>
  <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: InstanceView) => router.push(`/instances/${row.id}`)">
    <el-table-column label="状态" width="100">
      <template #default="{ row }">
        <el-tag :type="statusTag[row.status as RunStatus]">{{ statusText[row.status as RunStatus] }}</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="触发" width="80">
      <template #default="{ row }">{{ triggerText[row.triggerType] }}</template>
    </el-table-column>
    <el-table-column label="版本" width="70">
      <template #default="{ row }">v{{ row.versionNo }}</template>
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
    <el-table-column label="读 / 写行数" width="140">
      <template #default="{ row }">{{ formatNumber(row.readRows) }} / {{ formatNumber(row.writeRows) }}</template>
    </el-table-column>
    <el-table-column label="校验" width="130">
      <template #default="{ row }"><ValidationTag :instance="row" /></template>
    </el-table-column>
    <el-table-column label="触发人" width="110">
      <template #default="{ row }">{{ row.createdByName ?? (row.triggerType === 'SCHEDULE' || row.triggerType === 'BACKFILL' ? '调度' : '-') }}</template>
    </el-table-column>
    <el-table-column prop="errorMessage" label="错误" min-width="200" show-overflow-tooltip />
  </el-table>
  <el-pagination
    v-model:current-page="query.page"
    :page-size="query.size"
    :total="total"
    layout="total, prev, pager, next"
    @current-change="apply(false)"
  />
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.status {
  width: 160px;
}
</style>

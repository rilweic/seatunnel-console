<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { instanceApi, type InstanceView, type RunStatus } from '@/api/console'
import { formatDuration, formatNumber, formatTime, isActive, statusTag, statusText, triggerText } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import ValidationTag from '@/components/ValidationTag.vue'

const router = useRouter()

const rows = ref<InstanceView[]>([])
const total = ref(0)
const query = reactive({ status: '', page: 1, size: 20 })
const loading = ref(false)
let timer: number | undefined

async function load(silent = false) {
  if (!silent) loading.value = true
  try {
    const result = await instanceApi.list(query)
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
  // Keep the list fresh while anything on this page is still running.
  window.clearTimeout(timer)
  if (rows.value.some((r) => isActive(r.status))) {
    timer = window.setTimeout(() => load(true), 5000)
  }
}

onMounted(() => load())
onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="运行实例" description="所有任务的运行记录，包括手动、定时、重跑和补数。点击一行查看事件时间线和 SeaTunnel 作业日志。">
      <el-button :icon="Refresh" @click="load()">刷新</el-button>
    </PageHeader>
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable class="status" @change="load()">
        <el-option v-for="(label, value) in statusText" :key="value" :label="label" :value="value" />
      </el-select>
    </div>
    <el-table v-loading="loading" :data="rows" @row-click="(row: InstanceView) => router.push(`/instances/${row.id}`)">
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="statusTag[row.status as RunStatus]">{{ statusText[row.status as RunStatus] }}</el-tag>
        </template>
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
      <el-table-column prop="errorMessage" label="错误" min-width="220" show-overflow-tooltip />
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="load()"
    />
  </el-card>
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

.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

:deep(.el-table__row) {
  cursor: pointer;
}
</style>

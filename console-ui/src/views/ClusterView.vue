<script setup lang="ts">
import { CircleCheckFilled, Refresh, VideoPlay, WarningFilled, CircleCloseFilled } from '@element-plus/icons-vue'
import { computed, onMounted, ref } from 'vue'

import { clusterApi, type ClusterHealth, type HealthCheck } from '@/api/console'
import PageHeader from '@/components/PageHeader.vue'

const health = ref<ClusterHealth | null>(null)
const loading = ref(false)
const failed = ref('')
const diagnosis = ref<HealthCheck[] | null>(null)
const diagnosing = ref(false)

async function load() {
  loading.value = true
  failed.value = ''
  try {
    health.value = await clusterApi.health()
  } catch (e) {
    failed.value = (e as { response?: { data?: { message?: string } } }).response?.data?.message ?? '健康检查失败'
  } finally {
    loading.value = false
  }
}

async function diagnose() {
  diagnosing.value = true
  diagnosis.value = null
  try {
    diagnosis.value = await clusterApi.diagnose()
  } finally {
    diagnosing.value = false
  }
}

const summary = computed(() => {
  const checks = health.value?.checks ?? []
  return {
    error: checks.filter((c) => c.status === 'ERROR').length,
    warn: checks.filter((c) => c.status === 'WARN').length,
  }
})

/** Checks grouped in the order they came, so each group reads as one section. */
const groups = computed(() => {
  const map = new Map<string, HealthCheck[]>()
  for (const c of health.value?.checks ?? []) {
    if (!map.has(c.group)) map.set(c.group, [])
    map.get(c.group)!.push(c)
  }
  return [...map.entries()]
})

const OVERVIEW_LABELS: Record<string, string> = {
  projectVersion: '版本',
  gitCommitAbbrev: '提交',
  workers: 'Worker 数',
  runningJobs: '运行中作业',
  pendingJobs: '排队作业',
  finishedJobs: '已完成作业',
  failedJobs: '失败作业',
  cancelledJobs: '已取消作业',
  totalSlot: '总 slot',
  unassignedSlot: '空闲 slot',
}

const gb = (bytes: number) => `${(bytes / 1024 ** 3).toFixed(1)} GB`
const statusIcon = { OK: CircleCheckFilled, WARN: WarningFilled, ERROR: CircleCloseFilled }

onMounted(load)
</script>

<template>
  <div class="page">
    <el-card v-loading="loading" shadow="never">
      <PageHeader title="集群健康检查" description="检查 SeaTunnel 集群和 console 的运行前提（设计文档 §4.3）。打开页面时自动执行，不会提交任何作业。">
        <el-button :icon="Refresh" @click="load">重新检查</el-button>
      </PageHeader>

      <el-result v-if="failed" icon="error" title="健康检查失败" :sub-title="failed" />
      <template v-else-if="health">
        <el-alert
          :type="summary.error ? 'error' : summary.warn ? 'warning' : 'success'"
          :title="summary.error ? `${summary.error} 项错误，${summary.warn} 项警告` : summary.warn ? `${summary.warn} 项警告，可以运行` : '全部正常'"
          :closable="false"
          show-icon
          class="summary"
        />
        <div v-for="[group, checks] in groups" :key="group" class="group">
          <div class="group-title">{{ group }}</div>
          <div v-for="(c, i) in checks" :key="i" class="check" :class="c.status.toLowerCase()">
            <el-icon class="icon"><component :is="statusIcon[c.status]" /></el-icon>
            <span class="name">{{ c.name }}</span>
            <span class="message">{{ c.message }}</span>
          </div>
        </div>
      </template>
    </el-card>

    <el-card shadow="never">
      <PageHeader title="运行诊断" description="在集群上提交几个很小的作业，确认快速检查看不到的两件事：每个节点是否按作业输出日志（routingAppender），以及每个 MySQL / PostgreSQL 数据源的 JDBC 驱动是否就位、worker 能否连上数据库。一般十几秒完成。">
        <el-button type="primary" :icon="VideoPlay" :loading="diagnosing" @click="diagnose">运行诊断</el-button>
      </PageHeader>
      <div v-if="diagnosing" class="muted">诊断作业运行中…</div>
      <div v-else-if="diagnosis" class="group">
        <div v-for="(c, i) in diagnosis" :key="i" class="check" :class="c.status.toLowerCase()">
          <el-icon class="icon"><component :is="statusIcon[c.status]" /></el-icon>
          <span class="name">{{ c.group }} · {{ c.name }}</span>
          <span class="message">{{ c.message }}</span>
        </div>
        <div v-if="diagnosis.length === 0" class="muted">没有可诊断的项</div>
      </div>
      <div v-else class="muted">还没有运行过</div>
    </el-card>

    <el-card v-if="health && health.workers.length" shadow="never">
      <PageHeader title="节点" />
      <el-table :data="health.workers">
        <el-table-column prop="address" label="地址" min-width="200" />
        <el-table-column label="堆内存（可用 / 总量）" min-width="180">
          <template #default="{ row }">{{ gb(row.availableHeapBytes) }} / {{ gb(row.totalHeapBytes) }}</template>
        </el-table-column>
        <el-table-column label="Slot" min-width="140">
          <template #default="{ row }">{{ row.dynamicSlot ? '动态分配' : `${row.freeSlots} / ${row.totalSlots} 空闲` }}</template>
        </el-table-column>
        <el-table-column prop="runningJobs" label="运行中作业" width="120" />
      </el-table>
    </el-card>

    <el-card v-if="health && Object.keys(health.overview).length" shadow="never">
      <PageHeader title="集群概览" />
      <el-descriptions :column="4">
        <el-descriptions-item v-for="(value, key) in health.overview" :key="key" :label="OVERVIEW_LABELS[key] ?? String(key)">
          {{ value }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.summary {
  margin-bottom: 8px;
}

.group {
  margin-top: 12px;
}

.group-title {
  margin-bottom: 4px;
  font-weight: 600;
}

.check {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 4px;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.icon {
  flex: none;
  position: relative;
  top: 2px;
}

.ok .icon {
  color: var(--el-color-success);
}

.warn .icon {
  color: var(--el-color-warning);
}

.error .icon {
  color: var(--el-color-danger);
}

.name {
  flex: none;
  min-width: 220px;
  color: var(--el-text-color-primary);
}

.message {
  color: var(--el-text-color-regular);
}

.error .message {
  color: var(--el-color-danger-dark-2);
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>

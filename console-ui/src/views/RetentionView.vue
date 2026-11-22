<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import {
  retentionApi,
  type CleanupLogView,
  type StorageOverview,
} from '@/api/retention'
import { formatBytes, formatDuration, formatNumber, formatTime } from '@/utils/format'

const loading = ref(false)
const saving = ref(false)
const runningCleanup = ref(false)
const updatedAt = ref<string | null>(null)

const form = reactive({
  enabled: true,
  runHour: 3,
  metricRetentionDays: 14,
  instanceRetentionDays: 90,
  logRetentionDays: 30,
  messageRetentionDays: 90,
})

const stats = ref<StorageOverview | null>(null)
const history = ref<CleanupLogView[]>([])

const hourOptions = Array.from({ length: 24 }, (_, h) => ({
  value: h,
  label: `${String(h).padStart(2, '0')}:05`,
}))

const hasExpiredData = computed(() => {
  const s = stats.value
  if (!s) return false
  return (
    s.metricSamplesExpired > 0 ||
    s.instancesExpired > 0 ||
    s.backfillsExpired > 0 ||
    s.messagesExpired > 0
  )
})

async function loadAll() {
  loading.value = true
  try {
    const [policy, overview, logs] = await Promise.all([
      retentionApi.getPolicy(),
      retentionApi.getStats(),
      retentionApi.listHistory(20),
    ])
    form.enabled = policy.enabled
    form.runHour = policy.runHour
    form.metricRetentionDays = policy.metricRetentionDays
    form.instanceRetentionDays = policy.instanceRetentionDays
    form.logRetentionDays = policy.logRetentionDays
    form.messageRetentionDays = policy.messageRetentionDays
    updatedAt.value = policy.updatedAt
    stats.value = overview
    history.value = logs
  } finally {
    loading.value = false
  }
}

async function savePolicy() {
  saving.value = true
  try {
    const saved = await retentionApi.updatePolicy({ ...form })
    updatedAt.value = saved.updatedAt
    ElMessage.success('保留策略已保存')
    stats.value = await retentionApi.getStats()
  } finally {
    saving.value = false
  }
}

async function triggerCleanup() {
  try {
    await ElMessageBox.confirm(
      '将按当前已保存的保留策略立即清理过期历史数据（运行中的任务和未解决告警不受影响）。确认执行吗？',
      '立即执行清理',
      {
        type: 'warning',
        confirmButtonText: '确认清理',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  runningCleanup.value = true
  try {
    const log = await retentionApi.runNow()
    if (log.status === 'SUCCESS') {
      ElMessage.success('清理完成')
    } else {
      ElMessage.error(`清理失败：${log.errorMessage || '未知错误'}`)
    }
    const [overview, logs] = await Promise.all([
      retentionApi.getStats(),
      retentionApi.listHistory(20),
    ])
    stats.value = overview
    history.value = logs
  } finally {
    runningCleanup.value = false
  }
}

onMounted(loadAll)
</script>

<template>
  <div v-loading="loading" class="page">
    <section class="panel">
      <div class="head">
        <div>
          <h2>数据量与存储概览</h2>
          <p class="sub">
            统计当前历史表与本地归档日志的占用，以及按当前策略预计可清理的过期数据量。
          </p>
        </div>
        <div class="actions">
          <el-button @click="loadAll">刷新</el-button>
          <el-button
            type="primary"
            :loading="runningCleanup || stats?.running"
            @click="triggerCleanup"
          >
            立即清理
          </el-button>
        </div>
      </div>

      <div v-if="stats" class="cards">
        <div class="card">
          <div class="card-label">指标采样（job_metric_sample）</div>
          <div class="card-value">{{ formatNumber(stats.metricSamplesTotal) }}</div>
          <div class="card-meta">
            <span>待清理：</span>
            <strong :class="{ warn: stats.metricSamplesExpired > 0 }">
              {{ formatNumber(stats.metricSamplesExpired) }}
            </strong>
          </div>
        </div>

        <div class="card">
          <div class="card-label">运行记录（job_instance）</div>
          <div class="card-value">{{ formatNumber(stats.instancesTotal) }}</div>
          <div class="card-meta">
            <span>待清理：</span>
            <strong :class="{ warn: stats.instancesExpired > 0 }">
              {{ formatNumber(stats.instancesExpired) }}
            </strong>
            <span class="sep">·</span>
            <span>尝试 {{ formatNumber(stats.attemptsTotal) }} / 事件 {{ formatNumber(stats.eventsTotal) }}</span>
          </div>
        </div>

        <div class="card">
          <div class="card-label">归档日志（本地磁盘）</div>
          <div class="card-value">{{ formatBytes(stats.logBytesTotal) }}</div>
          <div class="card-meta">
            <span>文件数：{{ formatNumber(stats.logFilesTotal) }}</span>
            <span class="sep">·</span>
            <span>补数批次 {{ formatNumber(stats.backfillsTotal) }}（待清理 {{ formatNumber(stats.backfillsExpired) }}）</span>
          </div>
        </div>

        <div class="card">
          <div class="card-label">站内消息（message）</div>
          <div class="card-value">{{ formatNumber(stats.messagesTotal) }}</div>
          <div class="card-meta">
            <span>待清理：</span>
            <strong :class="{ warn: stats.messagesExpired > 0 }">
              {{ formatNumber(stats.messagesExpired) }}
            </strong>
            <span class="sep">·</span>
            <span>未解决告警不删</span>
          </div>
        </div>
      </div>
    </section>

    <section class="panel">
      <div class="head">
        <div>
          <h2>保留策略配置</h2>
          <p class="sub">
            天数填 <strong>0</strong> 表示永久保留、不自动清理。所有清理均分批执行，且不会删除运行中的任务实例或未解决的告警消息。
          </p>
        </div>
        <div v-if="updatedAt" class="updated">最近修改：{{ formatTime(updatedAt) }}</div>
      </div>

      <el-form label-width="150px" class="policy-form">
        <el-form-item label="定时自动清理">
          <el-switch v-model="form.enabled" />
          <span class="hint">开启后每天在指定时间自动执行一次</span>
        </el-form-item>

        <el-form-item label="每日执行时间">
          <el-select v-model="form.runHour" :disabled="!form.enabled" style="width: 140px">
            <el-option v-for="opt in hourOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
          <span class="hint">建议避开业务高峰（默认凌晨 03:05）</span>
        </el-form-item>

        <el-form-item label="指标采样保留">
          <el-input-number v-model="form.metricRetentionDays" :min="0" :max="3650" />
          <span class="unit">天</span>
          <span class="hint">清理吞吐量曲线的明细采样（含长跑实时任务的历史采样），默认 14 天</span>
        </el-form-item>

        <el-form-item label="运行记录保留">
          <el-input-number v-model="form.instanceRetentionDays" :min="0" :max="3650" />
          <span class="unit">天</span>
          <span class="hint">清理已结束的离线/实时运行实例、尝试记录、事件时间线与已完成补数批次，默认 90 天</span>
        </el-form-item>

        <el-form-item label="归档日志保留">
          <el-input-number v-model="form.logRetentionDays" :min="0" :max="3650" />
          <span class="unit">天</span>
          <span class="hint">按天清理本地磁盘归档的引擎作业日志目录，默认 30 天</span>
        </el-form-item>

        <el-form-item label="站内消息保留">
          <el-input-number v-model="form.messageRetentionDays" :min="0" :max="3650" />
          <span class="unit">天</span>
          <span class="hint">清理已超过保留期的历史消息（仍处于未解决状态的告警会保留至恢复），默认 90 天</span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="savePolicy">保存策略</el-button>
          <span v-if="hasExpiredData" class="hint" style="margin-left: 12px">
            当前存在待清理数据，保存后可点击右上角「立即清理」
          </span>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <div class="head">
        <div>
          <h2>最近清理记录</h2>
          <p class="sub">记录每次定时与手动清理的删除明细与耗时（最多保留最近 100 条）。</p>
        </div>
      </div>

      <el-table :data="history" empty-text="还没有清理记录">
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column label="触发方式" width="110">
          <template #default="{ row }">
            <el-tag :type="row.triggerType === 'MANUAL' ? 'primary' : 'info'" size="small" effect="plain">
              {{ row.triggerType === 'MANUAL' ? '手动' : '定时' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="执行人" width="110">
          <template #default="{ row }">{{ row.triggeredByName || '系统' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : 'danger'" size="small">
              {{ row.status === 'SUCCESS' ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标采样" width="95" align="right">
          <template #default="{ row }">{{ formatNumber(row.deletedMetricSamples) }}</template>
        </el-table-column>
        <el-table-column label="运行记录" width="95" align="right">
          <template #default="{ row }">{{ formatNumber(row.deletedInstances) }}</template>
        </el-table-column>
        <el-table-column label="事件/补数" width="110" align="right">
          <template #default="{ row }">
            {{ formatNumber(row.deletedEvents) }} / {{ formatNumber(row.deletedBackfills) }}
          </template>
        </el-table-column>
        <el-table-column label="归档日志" width="140" align="right">
          <template #default="{ row }">
            {{ formatNumber(row.deletedLogFiles) }} 个 ({{ formatBytes(row.deletedLogBytes) }})
          </template>
        </el-table-column>
        <el-table-column label="消息" width="80" align="right">
          <template #default="{ row }">{{ formatNumber(row.deletedMessages) }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="90" align="right">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="err">{{ row.errorMessage }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.panel {
  background: #fff;
  border-radius: 8px;
  padding: 20px 24px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.sub {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.actions {
  display: flex;
  gap: 8px;
  flex: none;
}

.updated {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.card {
  padding: 16px 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fafcff;
}

.card-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.card-value {
  font-size: 24px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.2;
}

.card-meta {
  margin-top: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.card-meta .warn {
  color: var(--el-color-warning);
}

.sep {
  margin: 0 6px;
  color: var(--el-border-color);
}

.policy-form {
  max-width: 820px;
}

.unit {
  margin-left: 8px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.hint {
  margin-left: 12px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.err {
  color: var(--el-color-danger);
}

.muted {
  color: var(--el-text-color-placeholder);
}

@media (max-width: 1100px) {
  .cards {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>

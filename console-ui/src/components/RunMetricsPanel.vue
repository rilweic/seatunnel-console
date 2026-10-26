<script setup lang="ts">
/**
 * Engine metrics of one run (docs/design.md §10.4): totals, throughput over time from the samples
 * taken while it ran, and per-table counts as SeaTunnel reports them.
 */
import { computed } from 'vue'

import type { EngineMetrics, MetricSample } from '@/api/console'
import EChart from '@/components/EChart.vue'
import { formatBytes, formatNumber, formatRate } from '@/utils/format'

const props = withDefaults(
  defineProps<{ metrics: EngineMetrics; samples?: MetricSample[]; showChart?: boolean; samplingHint?: string }>(),
  {
    samples: () => [],
    showChart: true,
    samplingHint: '运行超过两个轮询周期（默认 2 秒）后会显示速率曲线',
  },
)

/** Rows per second between consecutive samples; the first point starts from zero at submission. */
const rates = computed(() => {
  const points: { time: string; read: number; write: number }[] = []
  for (let i = 1; i < props.samples.length; i++) {
    const a = props.samples[i - 1]!
    const b = props.samples[i]!
    const seconds = (Date.parse(b.time) - Date.parse(a.time)) / 1000
    if (seconds <= 0) continue
    points.push({
      time: b.time.slice(11, 19),
      read: Math.max(0, ((b.readRows ?? 0) - (a.readRows ?? 0)) / seconds),
      write: Math.max(0, ((b.writeRows ?? 0) - (a.writeRows ?? 0)) / seconds),
    })
  }
  return points
})

const chartOption = computed(() => ({
  color: ['#1677ff', '#13c2c2'],
  grid: { left: 48, right: 16, top: 32, bottom: 28 },
  legend: { top: 0, right: 0, itemWidth: 12, itemHeight: 8, textStyle: { color: '#8c8c8c' } },
  tooltip: {
    trigger: 'axis',
    valueFormatter: (v: number) => `${formatRate(v)} 行/秒`,
  },
  xAxis: {
    type: 'category',
    data: rates.value.map((p) => p.time),
    axisLine: { lineStyle: { color: '#d9d9d9' } },
    axisLabel: { color: '#8c8c8c' },
  },
  yAxis: {
    type: 'value',
    name: '行/秒',
    nameTextStyle: { color: '#8c8c8c', align: 'right' },
    splitLine: { lineStyle: { color: '#f0f0f0' } },
    axisLabel: { color: '#8c8c8c', formatter: (v: number) => formatRate(v) },
  },
  series: [
    { name: '读取', type: 'line', smooth: true, showSymbol: rates.value.length < 30, data: rates.value.map((p) => p.read) },
    { name: '写入', type: 'line', smooth: true, showSymbol: rates.value.length < 30, data: rates.value.map((p) => p.write), areaStyle: { opacity: 0.08 } },
  ],
}))

const tables = computed(() =>
  [...props.metrics.tables].sort((a, b) => (a.side === b.side ? a.table.localeCompare(b.table) : a.side === 'SOURCE' ? -1 : 1)),
)
</script>

<template>
  <div class="metrics">
    <div class="totals">
      <div class="total">
        <div class="label">读取行数</div>
        <div class="value">{{ formatNumber(metrics.readRows) }}</div>
        <div class="sub">{{ formatBytes(metrics.readBytes) }}</div>
      </div>
      <div class="total">
        <div class="label">写入行数</div>
        <div class="value">{{ formatNumber(metrics.writeRows) }}</div>
        <div class="sub">{{ formatBytes(metrics.writeBytes) }}</div>
      </div>
      <div class="total">
        <div class="label">平均读取速率</div>
        <div class="value">{{ formatRate(metrics.readQps) }}<span class="unit">行/秒</span></div>
      </div>
      <div class="total">
        <div class="label">平均写入速率</div>
        <div class="value">{{ formatRate(metrics.writeQps) }}<span class="unit">行/秒</span></div>
      </div>
    </div>

    <template v-if="showChart">
      <div class="block-title">吞吐量</div>
      <EChart v-if="rates.length >= 2" :option="chartOption" height="220px" />
      <div v-else class="empty">运行时间太短，采样点不足；{{ samplingHint }}</div>
    </template>

    <div class="block-title">表级指标<span class="tip">SeaTunnel 按插件统计，表名为 库.表（PostgreSQL 为 库.schema.表）</span></div>
    <el-table :data="tables" empty-text="这次运行没有表级指标">
      <el-table-column label="方向" width="80">
        <template #default="{ row }">
          <el-tag :type="row.side === 'SOURCE' ? 'primary' : 'success'">{{ row.side === 'SOURCE' ? '读取' : '写入' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="table" label="表" min-width="220" show-overflow-tooltip />
      <el-table-column prop="vertex" label="插件" width="110" />
      <el-table-column label="行数" width="130" align="right">
        <template #default="{ row }">{{ formatNumber(row.rows) }}</template>
      </el-table-column>
      <el-table-column label="数据量" width="120" align="right">
        <template #default="{ row }">{{ formatBytes(row.bytes) }}</template>
      </el-table-column>
      <el-table-column label="平均速率（行/秒）" width="150" align="right">
        <template #default="{ row }">{{ formatRate(row.qps) }}</template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.totals {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 900px) {
  .totals {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

.total {
  padding: 12px 16px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.unit {
  margin-left: 4px;
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.block-title {
  margin: 20px 0 8px;
  font-weight: 500;
}

.tip {
  margin-left: 8px;
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.empty {
  padding: 24px 0;
  text-align: center;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>

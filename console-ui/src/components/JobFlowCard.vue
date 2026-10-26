<script setup lang="ts">
/**
 * What a job syncs, on the job overview (design.md §10.4): source → processing → target, how it
 * writes, and which table goes where with what is done to it. For a real-time job, the tables
 * whose structure changed since it started are listed first, each change with whether the target
 * followed (design.md §12 P2, 表结构变更的反馈).
 */
import { Box, Operation, Search } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'

import type { FlowEndpoint, FlowSettings, JobFlow, StreamSettings, TableSchemaChanges } from '@/api/console'
import { useDatasourceTypes } from '@/composables/useDatasourceTypes'
import { formatTime } from '@/utils/format'

const props = defineProps<{ flow: JobFlow; versionNo: number | null; tableChanges?: TableSchemaChanges[] }>()

const { label: typeLabel } = useDatasourceTypes()

const FOLDED = 5
const showAll = ref(false)
const tableFilter = ref('')

const empty = computed(() => props.flow.sources.length === 0 && props.flow.sinks.length === 0)
const changesOf = computed(() => new Map((props.tableChanges ?? []).map((c) => [c.table, c])))
const changedCount = computed(() => props.flow.tables.filter((t) => changesOf.value.has(t.source)).length)
const needsAction = (table: string) => !!changesOf.value.get(table)?.changes.some((c) => c.problem)
/** Changed tables first, those needing action before the rest. */
const rank = (table: string) => (needsAction(table) ? 0 : changesOf.value.has(table) ? 1 : 2)

const filteredTables = computed(() => {
  const q = tableFilter.value.trim().toLowerCase()
  const tables = q ? props.flow.tables.filter((t) => t.source.toLowerCase().includes(q) || t.target.toLowerCase().includes(q)) : props.flow.tables
  return changedCount.value ? [...tables].sort((a, b) => rank(a.source) - rank(b.source)) : tables
})
const shownTables = computed(() => (showAll.value || tableFilter.value.trim() ? filteredTables.value : filteredTables.value.slice(0, FOLDED)))

const WINDOW_TEXT: Record<string, string> = { CONTINUOUS: '连续增量', DAY: '按天增量', HOUR: '按小时增量', CUSTOM: '按自定义条件增量' }

const STARTUP_TEXT: Record<string, string> = { INITIAL: '实时（全量 + 增量）', LATEST: '实时（只同步之后的变更）', TIMESTAMP: '实时（从某个时间点开始）' }

/** A real-time job's settings (design.md §12 P2). */
function streamSettings(s: FlowSettings, stream: StreamSettings) {
  return [
    { label: '同步方式', value: STARTUP_TEXT[stream.startup] ?? '实时' },
    { label: '表结构变更', value: stream.schemaChanges ? (stream.dropColumns ? '同步（含删列）' : '同步（不删列）') : '不同步' },
    { label: '删除', value: stream.skipDeletes ? '不同步删除' : '同步删除', warn: stream.skipDeletes },
    { label: '写入方式', value: '按主键写入' },
    { label: '并行度', value: String(s.parallelism) },
    { label: 'checkpoint', value: `每 ${stream.checkpointSeconds ?? 10} 秒` },
  ]
}

const settings = computed(() => {
  const s = props.flow.settings
  if (!s) return []
  if (s.stream) return streamSettings(s, s.stream)
  const mode = s.mode === 'INCREMENTAL'
    ? [WINDOW_TEXT[s.window ?? ''] ?? '增量', s.incrementalField].filter(Boolean).join(' · ')
    : '全量'
  return [
    { label: '同步方式', value: mode },
    { label: '写入方式', value: s.writeMode === 'UPSERT' ? '按主键更新（upsert）' : '追加' },
    { label: '已有数据', value: s.dataMode === 'CLEAR' ? '每次写入前清空' : '保留', warn: s.dataMode === 'CLEAR' },
    { label: '并行度', value: String(s.parallelism) },
    { label: '行数校验', value: s.validation ? '开启' : '关闭' },
  ]
})

function endpointType(e: FlowEndpoint): string {
  return (e.datasourceType && typeLabel(e.datasourceType)) || e.connector || '-'
}

function endpointName(e: FlowEndpoint): string {
  return e.datasourceName ?? e.datasourceId ?? e.connector ?? '-'
}
</script>

<template>
  <el-card shadow="never" class="flow-card">
    <template #header>
      <div class="head">
        <span>同步链路</span>
        <span class="head-meta">当前版本 {{ versionNo ? `v${versionNo}` : '未保存' }}</span>
      </div>
    </template>

    <div v-if="empty" class="muted">{{ versionNo ? '无法从脚本中识别来源和目标' : '还没有保存配置' }}</div>
    <template v-else>
      <div class="pipeline">
        <div class="stage">
          <div class="stage-title">来源</div>
          <div v-for="(e, i) in flow.sources" :key="'s' + i" class="node source">
            <div class="node-top">
              <span class="node-icon"><el-icon><Box /></el-icon></span>
              <span class="type">{{ endpointType(e) }}</span>
            </div>
            <div class="node-name" :title="endpointName(e)">{{ endpointName(e) }}</div>
            <div v-if="e.location" class="node-line" :title="e.location">{{ e.location }}</div>
            <div v-if="e.note" class="node-note">{{ e.note }}</div>
          </div>
        </div>

        <div class="link" aria-hidden="true"><span class="line" /><span class="head" /></div>

        <div class="stage">
          <div class="stage-title">处理</div>
          <div class="node process">
            <div class="node-top">
              <span class="node-icon"><el-icon><Operation /></el-icon></span>
              <span class="type">{{ flow.settings ? '字段处理' : 'Transform' }}</span>
            </div>
            <div v-if="flow.processing.length || flow.transforms.length" class="steps">
              <span v-for="s in flow.processing" :key="s" class="step">{{ s }}</span>
              <span v-for="(t, i) in flow.transforms" :key="t + i" class="step code">{{ t }}</span>
            </div>
            <div v-else class="node-line">原样同步，不做字段处理</div>
          </div>
        </div>

        <div class="link" aria-hidden="true"><span class="line" /><span class="head" /></div>

        <div class="stage">
          <div class="stage-title">目标</div>
          <div v-for="(e, i) in flow.sinks" :key="'t' + i" class="node target">
            <div class="node-top">
              <span class="node-icon"><el-icon><Box /></el-icon></span>
              <span class="type">{{ endpointType(e) }}</span>
            </div>
            <div class="node-name" :title="endpointName(e)">{{ endpointName(e) }}</div>
            <div v-if="e.location" class="node-line" :title="e.location">{{ e.location }}</div>
            <div v-if="e.note" class="node-note">{{ e.note }}</div>
          </div>
        </div>
      </div>

      <dl v-if="settings.length" class="settings">
        <div v-for="s in settings" :key="s.label" class="setting">
          <dt>{{ s.label }}</dt>
          <dd :class="{ warn: s.warn }">{{ s.value }}</dd>
        </div>
      </dl>

      <div v-if="flow.tables.length" class="mapping">
        <div class="mapping-head">
          <span class="mapping-title">
            表映射<span class="count">{{ flow.tables.length }} 张</span>
            <span v-if="changedCount" class="count changed">{{ changedCount }} 张启动后结构有变化，排在前面；运行中每 5 分钟对比一次</span>
          </span>
          <el-input
            v-if="flow.tables.length > FOLDED"
            v-model="tableFilter"
            :prefix-icon="Search"
            placeholder="筛选源表或目标表"
            clearable
            class="filter"
          />
        </div>
        <el-table :data="shownTables" size="small" class="mapping-table" empty-text="没有匹配的表">
          <el-table-column label="源表" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.source }}</span>
              <el-tag v-if="changesOf.has(row.source)" size="small" :type="needsAction(row.source) ? 'warning' : 'info'" disable-transitions class="changed-tag">
                {{ needsAction(row.source) ? '结构有变化 · 需要处理' : '结构有变化' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column width="36" align="center">
            <template #default><span class="arrow">→</span></template>
          </el-table-column>
          <el-table-column label="目标表" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="mono">{{ row.target }}</span>
              <span v-if="row.target === row.source" class="same">同名</span>
            </template>
          </el-table-column>
          <el-table-column label="说明" min-width="280">
            <template #default="{ row }">
              <span v-if="!row.notes.length && !changesOf.has(row.source)" class="none" title="原样同步">—</span>
              <span v-for="n in row.notes" :key="n" class="note-tag" :title="n">{{ n }}</span>
              <ul v-if="changesOf.has(row.source)" class="col-changes">
                <li v-for="(c, i) in changesOf.get(row.source)!.changes" :key="i" :class="{ problem: c.problem }">
                  <span class="change">{{ c.change }}</span><template v-if="c.status"> → {{ c.status }}</template>
                </li>
                <li class="checked">{{ formatTime(changesOf.get(row.source)!.checkedAt) }} 对比</li>
              </ul>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!tableFilter.trim() && filteredTables.length > FOLDED" class="more">
          <el-button link type="primary" @click="showAll = !showAll">
            {{ showAll ? '收起' : `展开全部 ${filteredTables.length} 张表` }}
          </el-button>
        </div>
      </div>
    </template>
  </el-card>
</template>

<style scoped>
.flow-card :deep(.el-card__header) {
  padding: 14px 20px;
}

.head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-weight: 600;
}

.head-meta {
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

/* source → processing → target, joined by arrows */
.pipeline {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 56px minmax(0, 1fr) 56px minmax(0, 1fr);
  align-items: stretch;
}

.stage {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-width: 0;
}

.stage-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  letter-spacing: 0.04em;
}

.node {
  flex: 1;
  box-sizing: border-box;
  min-height: 112px;
  padding: 14px 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: #fff;
  border-top-width: 3px;
}

.node.source {
  border-top-color: var(--el-color-primary);
}

.node.process {
  border-top-color: var(--el-color-warning);
  background: #fffdf7;
}

.node.target {
  border-top-color: var(--el-color-success);
}

.node-top {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.node-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 6px;
  font-size: 14px;
}

.source .node-icon {
  color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}

.process .node-icon {
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
}

.target .node-icon {
  color: var(--el-color-success);
  background: var(--el-color-success-light-9);
}

.type {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.node-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-line {
  margin-top: 4px;
  font-size: 13px;
  color: var(--el-text-color-regular);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-note {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.step {
  padding: 2px 8px;
  font-size: 12px;
  line-height: 20px;
  color: var(--el-color-warning-dark-2);
  background: var(--el-color-warning-light-9);
  border-radius: 4px;
}

.step.code {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
}

.link {
  position: relative;
  align-self: center;
  margin-top: 20px;
  height: 12px;
}

.link .line {
  position: absolute;
  top: 5px;
  left: 8px;
  right: 10px;
  height: 2px;
  background: var(--el-border-color);
}

.link .head {
  position: absolute;
  top: 1px;
  right: 6px;
  width: 8px;
  height: 8px;
  border-top: 2px solid var(--el-border-color);
  border-right: 2px solid var(--el-border-color);
  transform: rotate(45deg);
}

.settings {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 12px;
  margin: 18px 0 0;
  padding: 12px 16px;
  background: #f8fafc;
  border-radius: 8px;
}

.setting dt {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.setting dd {
  margin: 4px 0 0;
  font-size: 14px;
  color: var(--el-text-color-primary);
}

.setting dd.warn {
  color: var(--el-color-warning-dark-2);
}

.mapping {
  margin-top: 18px;
}

.mapping-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.mapping-title {
  font-weight: 600;
  font-size: 14px;
}

.count {
  margin-left: 6px;
  font-weight: 400;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.filter {
  width: 240px;
}

.mapping-table {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.mapping-table :deep(th.el-table__cell) {
  background: #f8fafc;
}

.mono {
  font-family: var(--el-font-family-mono, ui-monospace, monospace);
  font-size: 12.5px;
}

.arrow {
  color: var(--el-text-color-placeholder);
}

.none {
  color: var(--el-text-color-placeholder);
}

.same {
  margin-left: 6px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.note-tag {
  display: inline-block;
  max-width: 260px;
  margin: 2px 6px 2px 0;
  padding: 0 6px;
  font-size: 12px;
  line-height: 20px;
  color: var(--el-text-color-regular);
  background: var(--el-fill-color-light);
  border-radius: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}

.more {
  margin-top: 6px;
  text-align: center;
}

@media (max-width: 900px) {
  .pipeline {
    grid-template-columns: minmax(0, 1fr);
    row-gap: 4px;
  }

  .link {
    height: 20px;
    margin: 0;
    justify-self: center;
    width: 12px;
  }

  .link .line {
    top: 0;
    bottom: 6px;
    left: 5px;
    right: auto;
    width: 2px;
    height: auto;
  }

  .link .head {
    top: auto;
    bottom: 2px;
    right: 2px;
    transform: rotate(135deg);
  }

  .settings {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.count.changed {
  color: var(--el-color-warning-dark-2);
}

.changed-tag {
  margin-left: 6px;
}

.col-changes {
  margin: 4px 0 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
  line-height: 1.6;
  white-space: normal;
  color: var(--el-text-color-regular);
}

.col-changes .change {
  font-weight: 500;
}

.col-changes .problem {
  color: var(--el-color-warning-dark-2);
}

.col-changes .checked {
  color: var(--el-text-color-secondary);
}
</style>

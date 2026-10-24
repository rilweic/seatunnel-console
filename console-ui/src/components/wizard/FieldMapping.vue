<script setup lang="ts">
/**
 * Field mapping per table (docs/design.md §7.8): which source fields are synced, under which
 * target name, plus each table's fixed filter and incremental field. Tables on the left, the
 * chosen table's fields on the right.
 */
import { EditPen, Key, Right, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import type { ColumnMeta, NameRule, WizardForm, WizardTable } from '@/api/console'
import { clashes, isEmptyRule, nameProblem, ruleFieldName, syncedFields, targetFieldName, targetTableName } from '@/utils/naming'

import NameRuleDialog, { type RuleScope } from './NameRuleDialog.vue'

const props = defineProps<{
  form: WizardForm
  columns: Record<string, ColumnMeta[]>
  loading: boolean
  es: boolean
}>()

const tableSearch = ref('')
const fieldSearch = ref('')
const current = ref(props.form.source.tables[0]?.name ?? '')
const checked = ref<string[]>([])
const ruleOpen = ref(false)

watch(
  () => props.form.source.tables.map((t) => t.name),
  (names) => {
    if (!names.includes(current.value)) current.value = names[0] ?? ''
  },
)
watch(current, () => {
  checked.value = []
  fieldSearch.value = ''
})

const table = computed(() => props.form.source.tables.find((t) => t.name === current.value) ?? null)
const upsert = computed(() => props.form.target.writeMode === 'UPSERT')
const incremental = computed(() => props.form.sync.mode === 'INCREMENTAL' && props.form.sync.window !== 'CUSTOM')

const visibleTables = computed(() => {
  const q = tableSearch.value.trim().toLowerCase()
  return props.form.source.tables.filter(
    (t) => !q || t.name.toLowerCase().includes(q) || targetTableName(props.form, t, props.es).toLowerCase().includes(q),
  )
})

function allFields(t: WizardTable) {
  return (props.columns[t.name] ?? []).map((c) => c.name)
}

function synced(t: WizardTable) {
  return syncedFields(t, allFields(t))
}

/** Target names of the synced fields that clash or are invalid, by table. */
function problems(t: WizardTable): Map<string, string> {
  const fields = synced(t)
  const names = fields.map((c) => targetFieldName(props.form, t, c))
  const clashing = clashes(names)
  const out = new Map<string, string>()
  fields.forEach((c, i) => {
    const p = nameProblem(names[i], clashing)
    if (p) out.set(c, p)
  })
  return out
}

const currentProblems = computed(() => (table.value ? problems(table.value) : new Map<string, string>()))

const rows = computed(() => {
  const t = table.value
  if (!t) return []
  const q = fieldSearch.value.trim().toLowerCase()
  const kept = new Set(synced(t))
  return (props.columns[t.name] ?? [])
    .map((c, i) => ({ ...c, no: i + 1, masked: !kept.has(c.name) }))
    .filter((c) => !q || c.name.toLowerCase().includes(q) || targetFieldName(props.form, t, c.name).toLowerCase().includes(q))
})

// ---- masking: the table's column list; null means every column ----

function setColumns(t: WizardTable, names: string[]) {
  const all = allFields(t)
  t.columns = names.length === all.length ? null : all.filter((n) => names.includes(n))
}

function lockedKey(c: ColumnMeta) {
  return upsert.value && c.primaryKey
}

function mask(names: string[]) {
  const t = table.value!
  const meta = new Map((props.columns[t.name] ?? []).map((c) => [c.name, c]))
  const allowed = names.filter((n) => !lockedKey(meta.get(n)!))
  if (allowed.length < names.length) ElMessage.warning('按主键 upsert 需要主键字段，主键没有屏蔽')
  const kept = synced(t).filter((n) => !allowed.includes(n))
  if (kept.length === 0) {
    ElMessage.warning('至少要同步一个字段')
    return
  }
  setColumns(t, kept)
  checked.value = []
}

function restore(names: string[]) {
  const t = table.value!
  setColumns(t, [...synced(t), ...names])
  checked.value = []
}

// ---- target names ----

function shown(t: WizardTable, column: string) {
  return t.fieldNames?.[column] ?? targetFieldName(props.form, t, column)
}

function onInput(t: WizardTable, column: string, value: string) {
  t.fieldNames = { ...(t.fieldNames ?? {}), [column]: value }
}

/** A name equal to the rule's, or left empty, follows the rule again. */
function onBlur(t: WizardTable, column: string) {
  if (!t.fieldNames || !(column in t.fieldNames)) return
  const typed = t.fieldNames[column].trim()
  const next = { ...t.fieldNames }
  if (!typed || typed === ruleFieldName(props.form, t, column)) delete next[column]
  else next[column] = typed
  t.fieldNames = Object.keys(next).length ? next : null
}

/** Drops the name set by hand, so the field follows the rule again. */
function followRule(t: WizardTable, column: string) {
  const next = { ...(t.fieldNames ?? {}) }
  delete next[column]
  t.fieldNames = Object.keys(next).length ? next : null
}

function byHandCount(t: WizardTable) {
  return Object.keys(t.fieldNames ?? {}).length
}

function applyRule(rule: NameRule, clearByHand: boolean, scope: RuleScope) {
  const value = isEmptyRule(rule) ? null : rule
  if (scope === 'all') {
    props.form.fieldRule = value
    props.form.source.tables.forEach((t) => {
      t.fieldRule = null
      if (clearByHand) t.fieldNames = null
    })
  } else {
    const t = table.value!
    // an empty table rule still matters when it keeps the job's rule off this table
    t.fieldRule = value ?? (props.form.fieldRule ? rule : null)
    if (clearByHand) t.fieldNames = null
  }
}

function timeFields(t: WizardTable) {
  return (props.columns[t.name] ?? []).filter((c) => c.temporal)
}
</script>

<template>
  <div class="field-mapping" v-loading="loading">
    <aside class="tables">
      <el-input v-model="tableSearch" :prefix-icon="Search" placeholder="搜索表名" clearable />
      <ul>
        <li
          v-for="t in visibleTables"
          :key="t.name"
          :class="{ active: t.name === current }"
          @click="current = t.name"
        >
          <div class="t-target">
            <span class="t-name">{{ targetTableName(form, t, es) }}</span>
            <span v-if="problems(t).size" class="dot" title="有字段名冲突或不合法" />
          </div>
          <div class="t-meta">
            <span class="t-source">{{ t.name }}</span>
            <span>已选 {{ synced(t).length }} / {{ allFields(t).length || '?' }}</span>
            <span v-if="byHandCount(t)">· 改名 {{ byHandCount(t) }}</span>
          </div>
        </li>
      </ul>
    </aside>

    <section v-if="table" class="fields">
      <div class="fields-bar">
        <div class="table-title">
          {{ table.name }} <el-icon class="arrow"><Right /></el-icon> <b>{{ targetTableName(form, table, es) }}</b>
        </div>
        <div class="bar-actions">
          <el-button :icon="EditPen" @click="ruleOpen = true">字段名规则</el-button>
          <el-button :disabled="!checked.length" @click="mask(checked)">屏蔽</el-button>
          <el-button :disabled="!checked.length" @click="restore(checked)">恢复</el-button>
        </div>
      </div>

      <div class="table-settings">
        <label>过滤条件
          <el-input v-model="table.filter" placeholder="可选，不含 where，如 status = 1；可以使用变量" class="code" clearable />
        </label>
        <label v-if="incremental">增量字段
          <el-select
            v-model="table.incrementalField"
            :placeholder="form.sync.field ? `使用共用字段 ${form.sync.field}` : '选择时间字段'"
            clearable
          >
            <el-option v-for="c in timeFields(table)" :key="c.name" :label="`${c.name} (${c.type})`" :value="c.name" />
          </el-select>
        </label>
      </div>

      <div class="search-row">
        <el-input v-model="fieldSearch" :prefix-icon="Search" placeholder="搜索字段名" clearable class="field-search" />
        <span class="tip">
          屏蔽的字段不会同步；目标字段名默认按规则生成，可以直接改<template v-if="currentProblems.size">，<span class="error">{{ currentProblems.size }} 个字段名有问题</span></template>
        </span>
      </div>

      <el-table
        :data="rows"
        row-key="name"
        max-height="460"
        class="table-box"
        :row-class-name="({ row }: { row: { masked: boolean } }) => (row.masked ? 'masked' : '')"
        @selection-change="(sel: { name: string }[]) => (checked = sel.map((r) => r.name))"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column prop="no" label="序号" width="64" align="center" />
        <el-table-column label="源字段" min-width="220">
          <template #default="{ row }">
            <span class="f-name">{{ row.name }}</span>
            <el-tooltip v-if="row.primaryKey" content="主键" placement="top">
              <el-icon class="key"><Key /></el-icon>
            </el-tooltip>
            <div class="f-type">{{ row.type }}</div>
          </template>
        </el-table-column>
        <el-table-column label="目标字段名" min-width="240">
          <template #default="{ row }">
            <span v-if="row.masked" class="tip">不同步</span>
            <template v-else>
              <el-input
                :model-value="shown(table, row.name)"
                :class="{ invalid: currentProblems.has(row.name) }"
                @update:model-value="(v: string) => onInput(table!, row.name, v)"
                @blur="onBlur(table!, row.name)"
              />
              <div v-if="currentProblems.has(row.name)" class="error">{{ currentProblems.get(row.name) }}</div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button v-if="row.masked" link type="primary" @click="restore([row.name])">恢复</el-button>
            <el-tooltip v-else-if="lockedKey(row)" content="按主键 upsert 需要主键字段" placement="top">
              <span><el-button link type="primary" disabled>屏蔽</el-button></span>
            </el-tooltip>
            <el-button v-else link type="primary" @click="mask([row.name])">屏蔽</el-button>
            <el-button
              v-if="!row.masked && table.fieldNames?.[row.name] != null"
              link
              type="info"
              @click="followRule(table!, row.name)"
            >还原</el-button>
          </template>
        </el-table-column>
      </el-table>

      <NameRuleDialog
        v-model="ruleOpen"
        title="字段名规则"
        :rule="table.fieldRule ?? form.fieldRule"
        :items="synced(table).map((c) => ({ name: c, byHand: table!.fieldNames?.[c] }))"
        source-label="源字段名"
        target-label="目标字段名"
        scopes
        @apply="applyRule"
      />
    </section>
  </div>
</template>

<style scoped>
.field-mapping {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 420px;
  border: 1px solid #e8edf4;
  border-radius: 10px;
  overflow: hidden;
}

.tables {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 12px;
  background: #f8fafc;
  border-right: 1px solid #e8edf4;
}

.tables ul {
  flex: 1;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  max-height: 600px;
  list-style: none;
}

.tables li {
  padding: 10px 12px;
  border-left: 3px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.tables li:hover {
  background: #eef3fb;
}

.tables li.active {
  background: #e8f1ff;
  border-left-color: var(--el-color-primary);
}

.t-target {
  display: flex;
  align-items: center;
  gap: 6px;
}

.t-name {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tables li.active .t-name {
  color: var(--el-color-primary);
}

.dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
}

.t-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.t-source {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fields {
  min-width: 0;
  padding: 14px 16px 16px;
}

.fields-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.table-title b {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.arrow {
  color: var(--el-text-color-placeholder);
}

.bar-actions {
  display: flex;
  gap: 0;
}

.table-settings {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 12px;
  padding: 12px 14px;
  background: #f8fafc;
  border-radius: 8px;
}

.table-settings label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.search-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.field-search {
  width: 260px;
}

.table-box {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.table-box :deep(th.el-table__cell) {
  background: #f8fafc;
}

.table-box :deep(.el-table__body tr:last-child > td.el-table__cell) {
  border-bottom: none;
}

.table-box :deep(tr.masked) {
  color: var(--el-text-color-placeholder);
}

.table-box :deep(tr.masked .f-name) {
  text-decoration: line-through;
}

.f-name {
  font-weight: 500;
}

.key {
  margin-left: 4px;
  color: var(--el-color-warning);
  vertical-align: -2px;
}

.f-type {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.code :deep(input) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

@media (max-width: 900px) {
  .field-mapping {
    grid-template-columns: 1fr;
  }

  .tables {
    border-right: none;
    border-bottom: 1px solid #e8edf4;
  }

  .tables ul {
    max-height: 220px;
  }

  .table-settings {
    grid-template-columns: 1fr;
  }

  .field-search {
    width: 100%;
  }
}
</style>

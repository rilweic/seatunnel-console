<script setup lang="ts">
/**
 * Edits a list of name/value parameters (docs/design.md §8); turned into a map by the caller.
 * Values are picked by type instead of written as expressions: a fixed value, a date/time relative
 * to the scheduled time, a builtin variable, or a hand-written expression. Each row checks its name,
 * shows how to reference it and previews its value. What is stored is the plain value string.
 */
import { CopyDocument, Delete, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, reactive, watch } from 'vue'

import {
  BUILTINS,
  TIME_FORMATS,
  buildTime,
  formatBase,
  paramNameProblem,
  previewBuiltin,
  previewTime,
  readTime,
  type TimeSpec,
} from '@/utils/timeExpr'

export interface ParamRow {
  key: string
  value: string
}

type ValueType = 'fixed' | 'time' | 'builtin' | 'custom'

interface RowState {
  type: ValueType
  time: TimeSpec
  builtin: string
}

const rows = defineModel<ParamRow[]>({ required: true })
defineProps<{ placeholder?: string }>()

const defaultTime = (): TimeSpec => ({ kind: 'offset', amount: -1, unit: 'day', format: 'yyyyMMdd' })

/** The picker state of each row, kept beside the rows (which stay plain name/value pairs). */
const states = reactive(new WeakMap<ParamRow, RowState>())

function detect(value: string): RowState {
  const builtin = /^\$\{(system\.[\w.]+)}$/.exec(value.trim())
  if (builtin && BUILTINS.some((b) => b.name === builtin[1])) {
    return { type: 'builtin', time: defaultTime(), builtin: builtin[1] }
  }
  const time = readTime(value)
  if (time) return { type: 'time', time, builtin: BUILTINS[0].name }
  const type: ValueType = /\$[{[]/.test(value) ? 'custom' : 'fixed'
  return { type, time: defaultTime(), builtin: BUILTINS[0].name }
}

function state(row: ParamRow): RowState {
  let s = states.get(row)
  if (!s) {
    s = reactive(detect(row.value))
    states.set(row, s)
  }
  return s
}

// rows replaced from outside (loading) get their state worked out again
// (deep: 1 so added rows get theirs before they render)
watch(rows, (list) => list.forEach(state), { immediate: true, deep: 1 })

function setType(row: ParamRow, type: ValueType) {
  const s = state(row)
  s.type = type
  if (type === 'time') row.value = buildTime(s.time)
  else if (type === 'builtin') row.value = `\${${s.builtin}}`
  else if (type === 'fixed' && /\$[{[]/.test(row.value)) row.value = ''
}

function setTime(row: ParamRow, patch: Partial<TimeSpec>) {
  const s = state(row)
  Object.assign(s.time, patch)
  row.value = buildTime(s.time)
}

function setBuiltin(row: ParamRow, name: string) {
  state(row).builtin = name
  row.value = `\${${name}}`
}

/** The run's scheduled time the previews use: now, to the minute. */
const base = new Date()
base.setSeconds(0, 0)

function preview(row: ParamRow): string | null {
  const s = state(row)
  if (s.type === 'time') return previewTime(s.time, base)
  if (s.type === 'builtin') return previewBuiltin(s.builtin, base) ?? '运行时生成'
  return null
}

const names = computed(() => rows.value.map((r) => r.key))

function copyRef(key: string) {
  navigator.clipboard?.writeText(`\${${key.trim()}}`).then(
    () => ElMessage.success(`已复制 \${${key.trim()}}`),
    () => ElMessage.warning('复制失败，请手动选中复制'),
  )
}

function add() {
  rows.value.push({ key: '', value: '' })
}

const KINDS = [
  { value: 'offset', label: '计划时间' },
  { value: 'monthFirst', label: '所在月第一天' },
  { value: 'monthLast', label: '所在月最后一天' },
  { value: 'weekFirst', label: '所在周周一' },
  { value: 'weekLast', label: '所在周周日' },
]
const unitOf = (s: TimeSpec) => (s.kind === 'offset' ? s.unit : s.kind.startsWith('month') ? 'month' : 'week')
const UNIT_TEXT: Record<string, string> = { day: '天', hour: '小时', month: '个月', week: '周' }
</script>

<template>
  <div class="params">
    <div v-if="rows.length" class="head">
      <span class="c-name">参数名</span>
      <span class="c-type">值类型</span>
      <span class="c-value">值</span>
    </div>
    <div v-for="(row, i) in rows" :key="i" class="row">
      <div class="c-name">
        <el-input v-model="row.key" placeholder="如 dt" :class="{ invalid: paramNameProblem(row.key, names) }" />
        <div v-if="paramNameProblem(row.key, names)" class="error">{{ paramNameProblem(row.key, names) }}</div>
        <button v-else-if="row.key.trim()" type="button" class="ref" title="复制引用写法" @click="copyRef(row.key)">
          引用：<code>{{ '${' + row.key.trim() + '}' }}</code><el-icon><CopyDocument /></el-icon>
        </button>
      </div>

      <el-select :model-value="state(row).type" class="c-type" @update:model-value="(t: ValueType) => setType(row, t)">
        <el-option label="固定值" value="fixed" />
        <el-option label="日期时间" value="time" />
        <el-option label="内置变量" value="builtin" />
        <el-option label="自定义表达式" value="custom" />
      </el-select>

      <div class="c-value">
        <el-input v-if="state(row).type === 'fixed'" v-model="row.value" :placeholder="placeholder ?? '如 shanghai、100'" />

        <div v-else-if="state(row).type === 'time'" class="time">
          <el-select :model-value="state(row).time.kind" class="kind" @update:model-value="(k: TimeSpec['kind']) => setTime(row, { kind: k })">
            <el-option v-for="k in KINDS" :key="k.value" :label="k.label" :value="k.value" />
          </el-select>
          <el-select
            :model-value="Math.sign(state(row).time.amount) || 0"
            class="dir"
            @update:model-value="(d: number) => setTime(row, { amount: d === 0 ? 0 : d * (Math.abs(state(row).time.amount) || 1) })"
          >
            <el-option label="往前" :value="-1" />
            <el-option label="当前" :value="0" />
            <el-option label="往后" :value="1" />
          </el-select>
          <template v-if="state(row).time.amount !== 0">
            <el-input-number
              :model-value="Math.abs(state(row).time.amount)"
              :min="1"
              :max="999"
              controls-position="right"
              class="amount"
              @update:model-value="(n: number | undefined) => setTime(row, { amount: Math.sign(state(row).time.amount) * (n || 1) })"
            />
            <el-select
              v-if="state(row).time.kind === 'offset'"
              :model-value="state(row).time.unit"
              class="unit"
              @update:model-value="(u: TimeSpec['unit']) => setTime(row, { unit: u })"
            >
              <el-option label="天" value="day" />
              <el-option label="小时" value="hour" />
              <el-option label="个月" value="month" />
            </el-select>
            <span v-else class="unit-text">{{ UNIT_TEXT[unitOf(state(row).time)] }}</span>
          </template>
          <el-select :model-value="state(row).time.format" class="format" @update:model-value="(f: string) => setTime(row, { format: f })">
            <el-option v-for="f in TIME_FORMATS" :key="f.value" :label="f.label" :value="f.value" />
          </el-select>
        </div>

        <el-select v-else-if="state(row).type === 'builtin'" :model-value="state(row).builtin" class="builtin" @update:model-value="(n: string) => setBuiltin(row, n)">
          <el-option v-for="b in BUILTINS" :key="b.name" :label="b.label" :value="b.name">
            <span>{{ b.label }}</span><span class="opt-help">{{ (b.help ? b.help + ' · ' : '') + '${' + b.name + '}' }}</span>
          </el-option>
        </el-select>

        <el-input v-else v-model="row.value" class="code" placeholder="如 ${system.biz.date}、$[add_months(yyyyMMdd,-1)]" />

        <div v-if="preview(row) != null" class="preview">
          示例：<b>{{ preview(row) }}</b>（按计划时间 {{ formatBase(base) }} 计算）<span class="raw">{{ row.value }}</span>
        </div>
        <div v-else-if="state(row).type === 'custom'" class="preview">
          <code>${名称}</code> 引用参数或内置变量，<code>$[...]</code> 写时间表达式，如 <code>$[yyyyMMdd-1]</code>、<code>$[month_first_day(yyyy-MM-dd,-1)]</code>
        </div>
      </div>

      <el-button link type="danger" :icon="Delete" class="c-del" title="删除" @click="rows.splice(i, 1)" />
    </div>
    <el-button :icon="Plus" class="add" @click="add">添加参数</el-button>
  </div>
</template>

<style scoped>
.params {
  display: flex;
  flex-direction: column;
  width: 100%;
}

.head,
.row {
  display: grid;
  grid-template-columns: 200px 130px minmax(0, 1fr) 28px;
  gap: 12px;
  align-items: start;
}

.head {
  padding: 0 0 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.row {
  padding: 12px 0;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}

.c-type {
  width: 100%;
}

.c-del {
  margin-top: 8px;
}

.time {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.kind {
  width: 140px;
}

.dir {
  width: 80px;
}

.amount {
  width: 90px;
}

.unit {
  width: 84px;
}

.unit-text {
  align-self: center;
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.format {
  width: 230px;
}

.builtin {
  width: 100%;
  max-width: 360px;
}

.opt-help {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.preview {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.preview b {
  color: var(--el-color-primary);
  font-weight: 500;
}

.raw {
  margin-left: 8px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  color: var(--el-text-color-placeholder);
}

.ref {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  cursor: pointer;
}

.ref:hover {
  color: var(--el-color-primary);
}

.ref code {
  padding: 0;
  background: none;
}

.invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.error {
  margin-top: 4px;
  color: var(--el-color-danger);
  font-size: 12px;
  line-height: 1.5;
}

.code :deep(input) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.add {
  align-self: flex-start;
  margin-top: 12px;
}

@media (max-width: 720px) {
  .head {
    display: none;
  }

  .row {
    grid-template-columns: 1fr 28px;
  }

  .c-name,
  .c-type,
  .c-value {
    grid-column: 1;
  }

  .c-del {
    grid-column: 2;
    grid-row: 1;
  }
}
</style>

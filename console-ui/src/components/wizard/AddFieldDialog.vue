<script setup lang="ts">
/**
 * Adds or edits a new field of a table (docs/design.md §7.10), computed by picking what to do
 * rather than writing SQL. A constant's value is picked the way parameter values are.
 */
import { computed, reactive, ref, watch } from 'vue'

import type { AddedField } from '@/api/console'
import { ADD_KINDS, DATE_FORMATS, FIELD_TYPES } from '@/utils/fieldProcessing'
import { BUILTINS, TIME_FORMATS, buildTime, readTime, type TimeSpec } from '@/utils/timeExpr'

const props = defineProps<{
  field: AddedField | null
  /** fields the new one may read */
  fields: string[]
  /** names already used by the table's target fields, other than this one */
  taken: string[]
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ apply: [field: AddedField] }>()

type Source = 'fixed' | 'builtin' | 'time'

const f = reactive<AddedField>({ name: '', kind: 'CONSTANT' })
const source = ref<Source>('fixed')
const builtin = ref(BUILTINS[0].name)
const time = reactive<TimeSpec>({ kind: 'offset', amount: -1, unit: 'day', format: 'yyyyMMdd' })
const other = ref<'field' | 'number'>('number')

watch(visible, (open) => {
  if (!open) return
  const a = props.field
  Object.assign(f, {
    name: a?.name ?? '',
    kind: a?.kind ?? 'CONSTANT',
    fields: [...(a?.fields ?? [])],
    value: a?.value ?? '',
    op: a?.op ?? null,
    start: a?.start ?? 1,
    length: a?.length ?? 4,
    format: a?.format ?? null,
    type: a?.type ?? ((a?.kind ?? 'CONSTANT') === 'CONSTANT' ? 'STRING' : null),
    precision: a?.precision ?? 18,
    scale: a?.scale ?? 2,
  })
  const b = /^\$\{(system\.[\w.]+)}$/.exec(f.value ?? '')
  const t = readTime(f.value ?? '')
  source.value = b ? 'builtin' : t ? 'time' : 'fixed'
  if (b) builtin.value = b[1]
  if (t) Object.assign(time, t)
  other.value = (f.fields?.[1] ?? '') ? 'field' : 'number'
})

// defaults that make each kind usable right away
watch(() => f.kind, (kind) => {
  if (kind === 'SUBSTRING' && !f.op) f.op = 'LEFT'
  if (kind === 'CASE' && !['UPPER', 'LOWER', 'TRIM'].includes(f.op ?? '')) f.op = 'UPPER'
  if (kind === 'ARITHMETIC' && !['+', '-', '*', '/'].includes(f.op ?? '')) f.op = '+'
  if (kind === 'DATE_FORMAT' && !f.format) f.format = 'yyyy-MM-dd'
  if (kind === 'CONCAT' && f.value == null) f.value = ''
  if (kind === 'CONSTANT' && !f.type) f.type = 'STRING'
  // a computed field keeps its own type unless one is picked for it
  if (kind !== 'CONSTANT' && f.type === 'STRING') f.type = null
})

/** The constant's value and, for dates and times, the format it is written in. */
const constant = computed(() => {
  if (source.value === 'builtin') {
    const fmt = builtin.value === 'system.datetime' ? 'yyyyMMddHHmmss' : builtin.value.startsWith('system.biz.') ? 'yyyyMMdd' : null
    return { value: `\${${builtin.value}}`, format: fmt }
  }
  if (source.value === 'time') return { value: buildTime(time), format: time.format }
  return { value: f.value ?? '', format: f.format ?? null }
})

const DATE_PARSE = ['yyyy-MM-dd', 'yyyyMMdd', 'yyyy/MM/dd']
const TIMESTAMP_PARSE = ['yyyy-MM-dd HH:mm:ss', 'yyyyMMddHHmmss', 'yyyy/MM/dd HH:mm:ss']

const problem = computed(() => {
  const name = f.name.trim()
  if (!name) return '请填写字段名'
  if (/[\s.$`"'\\]/.test(name)) return '字段名不能包含空格、点、引号、$ 或反斜杠'
  if (props.taken.some((t) => t.toLowerCase() === name.toLowerCase())) return '字段名和已有字段重复'
  const fields = (f.fields ?? []).filter(Boolean)
  switch (f.kind) {
    case 'CONSTANT': {
      const fmt = constant.value.format ?? ''
      if (f.type === 'DATE' && !DATE_PARSE.includes(fmt)) return `日期类型的值要是 ${DATE_PARSE.join(' / ')} 格式`
      if (f.type === 'TIMESTAMP' && !TIMESTAMP_PARSE.includes(fmt)) return `日期时间类型的值要是 ${TIMESTAMP_PARSE.join(' / ')} 格式`
      return null
    }
    case 'CONCAT':
      return fields.length < 2 ? '至少选两个字段' : null
    case 'ARITHMETIC':
      if (!fields[0]) return '请选择字段'
      if (other.value === 'number' && !/^-?\d+(\.\d+)?$/.test((f.value ?? '').trim())) return '请填写一个数字'
      if (other.value === 'field' && !f.fields?.[1]) return '请选择另一个字段'
      return null
    case 'UUID':
    case 'NOW':
      return null
    case 'EXPRESSION':
      return f.value?.trim() ? null : '请填写表达式'
    default:
      return fields[0] ? null : '请选择字段'
  }
})

function apply() {
  const out: AddedField = {
    name: f.name.trim(),
    kind: f.kind,
    fields: ['CONCAT', 'SUBSTRING', 'CASE', 'DATE_FORMAT', 'ARITHMETIC', 'DEFAULT'].includes(f.kind)
      ? (f.kind === 'ARITHMETIC' && other.value === 'number' ? [f.fields?.[0] ?? ''] : (f.fields ?? []).filter(Boolean))
      : null,
    value: f.kind === 'CONSTANT' ? constant.value.value : ['CONCAT', 'DEFAULT', 'EXPRESSION'].includes(f.kind) || (f.kind === 'ARITHMETIC' && other.value === 'number') ? f.value ?? '' : null,
    op: ['SUBSTRING', 'CASE', 'ARITHMETIC'].includes(f.kind) ? f.op : null,
    start: f.kind === 'SUBSTRING' && f.op === 'MID' ? f.start : null,
    length: f.kind === 'SUBSTRING' ? f.length : null,
    format: f.kind === 'CONSTANT' ? constant.value.format : f.kind === 'DATE_FORMAT' ? f.format : null,
    type: f.type || null,
    precision: f.type === 'DECIMAL' ? f.precision : null,
    scale: f.type === 'DECIMAL' ? f.scale : null,
  }
  emit('apply', out)
  visible.value = false
}

const kindHelp = computed(() => ADD_KINDS.find((k) => k.value === f.kind)?.help)
</script>

<template>
  <el-dialog v-model="visible" :title="field ? '编辑新字段' : '新增字段'" width="640px" append-to-body>
    <el-form label-width="96px">
      <el-form-item label="字段名">
        <el-input v-model="f.name" placeholder="写到目标表的字段名，如 etl_time" />
      </el-form-item>
      <el-form-item label="怎么算">
        <el-select v-model="f.kind" class="w-full">
          <el-option v-for="k in ADD_KINDS" :key="k.value" :label="k.label" :value="k.value" />
        </el-select>
        <div class="tip">{{ kindHelp }}</div>
      </el-form-item>

      <!-- 常量或变量 -->
      <template v-if="f.kind === 'CONSTANT'">
        <el-form-item label="值">
          <el-radio-group v-model="source" size="small" class="mb">
            <el-radio-button value="fixed">固定值</el-radio-button>
            <el-radio-button value="builtin">内置变量</el-radio-button>
            <el-radio-button value="time">日期时间</el-radio-button>
          </el-radio-group>
          <el-input v-if="source === 'fixed'" v-model="f.value" placeholder="如 shanghai" />
          <el-select v-else-if="source === 'builtin'" v-model="builtin" class="w-full">
            <el-option v-for="b in BUILTINS" :key="b.name" :label="`${b.label}${b.help ? `（${b.help}）` : ''}`" :value="b.name" />
          </el-select>
          <div v-else class="row">
            计划时间
            <el-select :model-value="Math.sign(time.amount)" class="w80" @update:model-value="(d: number) => (time.amount = d * (Math.abs(time.amount) || 1))">
              <el-option label="往前" :value="-1" /><el-option label="当前" :value="0" /><el-option label="往后" :value="1" />
            </el-select>
            <el-input-number v-if="time.amount !== 0" :model-value="Math.abs(time.amount)" :min="1" :max="999" controls-position="right" class="w90"
              @update:model-value="(n: number | undefined) => (time.amount = Math.sign(time.amount) * (n || 1))" />
            <el-select v-if="time.amount !== 0" v-model="time.unit" class="w80">
              <el-option label="天" value="day" /><el-option label="小时" value="hour" /><el-option label="个月" value="month" />
            </el-select>
            <el-select v-model="time.format" class="w200">
              <el-option v-for="t in TIME_FORMATS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </div>
          <div class="tip">实际写入的值：<code>{{ constant.value || '（空）' }}</code>，运行时换成实际的值</div>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="f.type" class="w160">
            <el-option v-for="t in FIELD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
          <el-select v-if="source === 'fixed' && (f.type === 'DATE' || f.type === 'TIMESTAMP')" v-model="f.format" placeholder="值的格式" class="w200 ml">
            <el-option v-for="d in f.type === 'DATE' ? DATE_PARSE : TIMESTAMP_PARSE" :key="d" :label="d" :value="d" />
          </el-select>
        </el-form-item>
      </template>

      <!-- 用到其他字段的 -->
      <el-form-item v-if="f.kind === 'CONCAT'" label="拼接">
        <el-select v-model="f.fields" multiple filterable placeholder="按顺序选择字段" class="w-full">
          <el-option v-for="c in fields" :key="c" :label="c" :value="c" />
        </el-select>
        <el-input v-model="f.value" placeholder="分隔符，可留空，如 -" class="w200 mt" />
      </el-form-item>

      <el-form-item v-if="['SUBSTRING', 'CASE', 'DATE_FORMAT', 'ARITHMETIC', 'DEFAULT'].includes(f.kind)" label="字段">
        <el-select :model-value="f.fields?.[0]" filterable class="w200" @update:model-value="(v: string) => (f.fields = [v, ...(f.fields ?? []).slice(1)])">
          <el-option v-for="c in fields" :key="c" :label="c" :value="c" />
        </el-select>
        <template v-if="f.kind === 'SUBSTRING'">
          <el-select v-model="f.op" class="w120 ml">
            <el-option label="前几位" value="LEFT" /><el-option label="后几位" value="RIGHT" /><el-option label="中间一段" value="MID" />
          </el-select>
          <template v-if="f.op === 'MID'">从第 <el-input-number v-model="f.start" :min="1" controls-position="right" class="w90" /> 位起</template>
          取 <el-input-number v-model="f.length" :min="1" controls-position="right" class="w90" /> 位
        </template>
        <el-select v-if="f.kind === 'CASE'" v-model="f.op" class="w120 ml">
          <el-option label="转大写" value="UPPER" /><el-option label="转小写" value="LOWER" /><el-option label="去首尾空格" value="TRIM" />
        </el-select>
        <el-select v-if="f.kind === 'DATE_FORMAT'" v-model="f.format" filterable allow-create class="w200 ml" placeholder="格式">
          <el-option v-for="d in DATE_FORMATS" :key="d" :label="d" :value="d" />
        </el-select>
        <template v-if="f.kind === 'ARITHMETIC'">
          <el-select v-model="f.op" class="w80 ml">
            <el-option v-for="o in ['+', '-', '*', '/']" :key="o" :label="o" :value="o" />
          </el-select>
          <el-select v-model="other" class="w100">
            <el-option label="一个数" value="number" /><el-option label="另一个字段" value="field" />
          </el-select>
          <el-input v-if="other === 'number'" v-model="f.value" placeholder="如 100" class="w120" />
          <el-select v-else :model-value="f.fields?.[1]" filterable class="w160" @update:model-value="(v: string) => (f.fields = [f.fields?.[0] ?? '', v])">
            <el-option v-for="c in fields" :key="c" :label="c" :value="c" />
          </el-select>
        </template>
        <template v-if="f.kind === 'DEFAULT'">
          为空时取 <el-input v-model="f.value" placeholder="默认值" class="w160" />
        </template>
      </el-form-item>

      <el-form-item v-if="f.kind === 'EXPRESSION'" label="表达式">
        <el-input v-model="f.value" type="textarea" :rows="3" class="code" placeholder="如 CONCAT(UPPER(first_name), ' ', last_name)" />
        <el-alert type="warning" :closable="false" class="mt"
          title="使用 SeaTunnel SQL 的函数（不是数据库的），写法只有运行时才会检查；能用上面的方式做到的，优先用上面的方式" />
      </el-form-item>

      <el-form-item v-if="f.kind !== 'CONSTANT'" label="结果类型">
        <el-select v-model="f.type" clearable placeholder="不转换（用计算结果本身的类型）" class="w240">
          <el-option v-for="t in FIELD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
        </el-select>
      </el-form-item>
      <el-form-item v-if="f.type === 'DECIMAL'" label="小数">
        总位数 <el-input-number v-model="f.precision" :min="1" :max="38" controls-position="right" class="w90" />
        小数位 <el-input-number v-model="f.scale" :min="0" :max="f.precision ?? 38" controls-position="right" class="w90" />
      </el-form-item>
    </el-form>
    <template #footer>
      <span v-if="problem" class="problem">{{ problem }}</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!!problem" @click="apply">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.w-full {
  width: 100%;
}

.w80 {
  width: 80px;
}

.w90 {
  width: 90px;
}

.w100 {
  width: 100px;
}

.w120 {
  width: 120px;
}

.w160 {
  width: 160px;
}

.w200 {
  width: 200px;
}

.w240 {
  width: 260px;
}

.ml {
  margin-left: 8px;
}

.mt {
  margin-top: 8px;
}

.mb {
  margin-bottom: 8px;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

:deep(.el-form-item__content) {
  gap: 6px;
}

.tip {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.code :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.problem {
  margin-right: 12px;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>

<script setup lang="ts">
/**
 * One connector's advanced options in plain language (docs/design.md §7.1): grouped, with our own
 * names and help, a control that fits the type, and the original option name for reference.
 * Options we do not describe are listed under "其他参数" with SeaTunnel's own description.
 */
import { computed, ref } from 'vue'

import type { ConnectorOption } from '@/api/console'
import { controlOf, optionDict, type OptionControl, type OptionText } from '@/utils/connectorOptions'

const props = defineProps<{
  title: string
  defs: ConnectorOption[]
  type: 'source' | 'sink'
  plugin: string
  /** target database type, for options that apply to one database only */
  targetType?: string
}>()
const values = defineModel<Record<string, string>>({ required: true })

interface Row {
  def: ConnectorOption
  text: OptionText | null
  control: OptionControl
}

const dict = computed(() => optionDict(props.type, props.plugin))
const set = (key: string) => !!values.value[key]?.trim()

const rows = computed<Row[]>(() =>
  props.defs
    // hidden options stay visible while they hold a value, so nothing set earlier is out of sight
    .filter((d) => !dict.value.hidden.includes(d.key) || set(d.key))
    .filter((d) => {
      const only = dict.value.options[d.key]?.onlyFor
      return !only || only === props.targetType || set(d.key)
    })
    .map((d) => {
      const text = dict.value.options[d.key] ?? null
      return { def: d, text, control: text?.control ?? controlOf(d.type) }
    }),
)

// a rare option that holds a value is shown with the common ones
const common = (r: Row) => !!r.text && (!r.text.rare || set(r.def.key))
const groups = computed(() =>
  dict.value.groups
    .map((g) => ({ title: g, rows: rows.value.filter((r) => common(r) && r.text!.group === g) }))
    .filter((g) => g.rows.length),
)
const rare = computed(() => rows.value.filter((r) => r.text && !common(r)))
const others = computed(() => rows.value.filter((r) => !r.text))
const showOthers = ref(false)

function defaultText(row: Row) {
  const v = row.def.defaultValue
  if (v == null || v === '' || v === '{}') return '未设置'
  if (row.control === 'bool') return /^true$/i.test(v) ? '是' : '否'
  const choice = row.text?.choices?.find((c) => c.value.toLowerCase() === v.toLowerCase())
  return choice ? choice.label : v
}

function onNumber(key: string, value: string) {
  values.value[key] = value.replace(/[^\d.-]/g, '')
}
</script>

<template>
  <div class="adv">
    <div class="adv-title">{{ title }}</div>
    <div v-for="g in groups" :key="g.title" class="group">
      <div class="group-title">{{ g.title }}</div>
      <div v-for="r in g.rows" :key="r.def.key" class="opt" :class="{ changed: set(r.def.key) }">
        <div class="opt-name">
          <div class="label">{{ r.text!.label }}</div>
          <code class="key">{{ r.def.key }}</code>
        </div>
        <div class="opt-input">
          <el-select v-if="r.control === 'bool'" v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`">
            <el-option label="是" value="true" />
            <el-option label="否" value="false" />
          </el-select>
          <el-select v-else-if="r.control === 'select'" v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`">
            <el-option v-for="c in r.text!.choices" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
          <el-input
            v-else-if="r.control === 'number'"
            :model-value="values[r.def.key]"
            inputmode="decimal"
            clearable
            :placeholder="`默认：${defaultText(r)}`"
            @update:model-value="(v: string) => onNumber(r.def.key, v)"
          />
          <el-input v-else v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`" />
        </div>
        <div class="help">{{ r.text!.help }}</div>
      </div>
    </div>

    <template v-if="rare.length || others.length">
      <el-button link type="primary" class="others-toggle" @click="showOthers = !showOthers">
        {{ showOthers ? '收起' : '展开' }}更多参数（{{ rare.length + others.length }} 个，一般不需要改）
      </el-button>
      <div v-if="showOthers" class="group">
        <div v-for="r in [...rare, ...others]" :key="r.def.key" class="opt" :class="{ changed: set(r.def.key) }">
          <div class="opt-name">
            <template v-if="r.text">
              <div class="label">{{ r.text.label }}</div>
              <code class="key">{{ r.def.key }}</code>
            </template>
            <code v-else class="label mono">{{ r.def.key }}</code>
          </div>
          <div class="opt-input">
            <el-select v-if="r.control === 'bool'" v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`">
              <el-option label="是" value="true" />
              <el-option label="否" value="false" />
            </el-select>
            <el-select v-else-if="r.control === 'select'" v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`">
              <el-option v-for="c in r.text!.choices" :key="c.value" :label="c.label" :value="c.value" />
            </el-select>
            <el-input
              v-else-if="r.control === 'number'"
              :model-value="values[r.def.key]"
              inputmode="decimal"
              clearable
              :placeholder="`默认：${defaultText(r)}`"
              @update:model-value="(v: string) => onNumber(r.def.key, v)"
            />
            <el-input v-else v-model="values[r.def.key]" clearable :placeholder="`默认：${defaultText(r)}`" />
          </div>
          <div class="help">{{ r.text ? r.text.help : `SeaTunnel 说明：${r.def.description ?? '无'}` }}</div>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.adv + .adv {
  margin-top: 20px;
}

.adv-title {
  margin-bottom: 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.group {
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}

.group-title {
  padding: 8px 14px;
  background: #f8fafc;
  border-bottom: 1px solid var(--el-border-color-lighter);
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-weight: 500;
}

.opt {
  display: grid;
  grid-template-columns: 220px 220px minmax(0, 1fr);
  gap: 16px;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid var(--el-border-color-extra-light);
}

.opt:last-child {
  border-bottom: none;
}

.opt.changed {
  background: #f5f9ff;
}

.label {
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 500;
}

.key,
.mono {
  padding: 0;
  background: none;
}

.key {
  display: block;
  margin-top: 2px;
  overflow: hidden;
  color: var(--el-text-color-placeholder);
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mono {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-weight: 400;
  word-break: break-all;
}

.opt-input :deep(.el-select),
.opt-input :deep(.el-input) {
  width: 100%;
}

.help {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.others-toggle {
  margin: 4px 0 10px;
}

@media (max-width: 900px) {
  .opt {
    grid-template-columns: 1fr;
    gap: 6px;
  }
}
</style>

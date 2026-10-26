<script setup lang="ts">
/**
 * A where condition that may use variables (docs/design.md §7.5, §8): variables are inserted from a
 * menu instead of typed from memory, and the condition is rendered by the server as the job's next
 * run would see it, so a typo or an undefined parameter shows up before saving.
 */
import { ArrowDown } from '@element-plus/icons-vue'
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

import { jobApi } from '@/api/console'
import { BUILTINS } from '@/utils/timeExpr'

const props = defineProps<{ jobId: string; params: () => Record<string, string>; readonly?: boolean }>()
const condition = defineModel<string | null | undefined>()

const PLACEHOLDER = "updated_at >= '${system.window.start}' and updated_at < '${system.window.end}' and status = 1"

interface Item {
  text: string
  label: string
  help?: string
}

const WINDOW: Item[] = BUILTINS.filter((b) => b.name.startsWith('system.window.')).map((b) => ({ text: `\${${b.name}}`, label: b.label, help: b.help }))
const TIMES: Item[] = [
  { text: '$[yyyy-MM-dd HH:mm:ss]', label: '计划时间' },
  { text: '$[yyyy-MM-dd HH:mm:ss-5/24/60]', label: '计划时间前 5 分钟', help: '1/24/60 是 1 分钟，1/24 是 1 小时' },
  { text: '$[yyyy-MM-dd HH:00:00-1/24]', label: '上一个整点' },
  { text: '$[yyyy-MM-dd]', label: '计划日期' },
  { text: '$[yyyy-MM-dd-1]', label: '前一天' },
]
const OTHERS: Item[] = BUILTINS.filter((b) => !b.name.startsWith('system.window.')).map((b) => ({ text: `\${${b.name}}`, label: b.label, help: b.help }))

const input = ref<{ textarea?: HTMLTextAreaElement } | null>(null)

/** Puts the text at the cursor (or replaces the selection), then puts the cursor after it. */
async function insert(text: string) {
  const area = input.value?.textarea
  const value = condition.value ?? ''
  const from = area?.selectionStart ?? value.length
  const to = area?.selectionEnd ?? value.length
  condition.value = value.slice(0, from) + text + value.slice(to)
  await nextTick()
  area?.focus()
  area?.setSelectionRange(from + text.length, from + text.length)
}

const paramItems = () => Object.keys(props.params()).map((k) => ({ text: `\${${k}}`, label: k }))

const references = (names: string[]) => names.map((n) => `\${${n}}`).join('、')

const rendered = ref<{ text: string | null; unresolved: string[]; error: string | null } | null>(null)
let timer: number | undefined
let seq = 0

async function render() {
  const text = condition.value?.trim()
  if (!text) {
    rendered.value = null
    return
  }
  const mine = ++seq
  try {
    const result = await jobApi.renderText(props.jobId, { text, params: props.params() })
    if (mine === seq) rendered.value = result
  } catch {
    if (mine === seq) rendered.value = null
  }
}

watch(
  [condition, () => JSON.stringify(props.params())],
  () => {
    window.clearTimeout(timer)
    timer = window.setTimeout(render, 500)
  },
  { immediate: true },
)
onBeforeUnmount(() => window.clearTimeout(timer))
</script>

<template>
  <div class="condition">
    <el-input ref="input" v-model="condition" type="textarea" :rows="3" class="code" :placeholder="PLACEHOLDER" :disabled="readonly" />
    <div class="bar">
      <el-dropdown trigger="click" :disabled="readonly" @command="insert">
        <el-button size="small">插入变量<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
        <template #dropdown>
          <el-dropdown-menu class="variable-menu">
            <el-dropdown-item disabled class="group">同步窗口（连续增量）</el-dropdown-item>
            <el-dropdown-item v-for="i in WINDOW" :key="i.text" :command="i.text">
              <span class="label">{{ i.label }}</span><code>{{ i.text }}</code>
            </el-dropdown-item>
            <el-dropdown-item disabled divided class="group">时间表达式（以计划时间为基准）</el-dropdown-item>
            <el-dropdown-item v-for="i in TIMES" :key="i.text" :command="i.text">
              <span class="label">{{ i.label }}</span><code>{{ i.text }}</code>
            </el-dropdown-item>
            <el-dropdown-item disabled divided class="group">内置变量</el-dropdown-item>
            <el-dropdown-item v-for="i in OTHERS" :key="i.text" :command="i.text">
              <span class="label">{{ i.label }}</span><code>{{ i.text }}</code>
            </el-dropdown-item>
            <template v-if="paramItems().length">
              <el-dropdown-item disabled divided class="group">任务参数</el-dropdown-item>
              <el-dropdown-item v-for="i in paramItems()" :key="i.text" :command="i.text">
                <span class="label">{{ i.label }}</span><code>{{ i.text }}</code>
              </el-dropdown-item>
            </template>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
      <span class="tip">不含 where，对所有表生效；时间值两边要加单引号</span>
    </div>
    <div v-if="rendered" class="preview">
      <div v-if="rendered.error" class="bad">无法渲染：{{ rendered.error }}</div>
      <template v-else>
        <div class="tip">下次运行时的条件（项目参数、任务参数都已代入）：</div>
        <code class="rendered">{{ rendered.text }}</code>
        <div v-if="rendered.unresolved.length" class="bad">
          这些变量没有定义，保存时会被拦下：{{ references(rendered.unresolved) }}。
          先在下面的「任务参数」或项目参数里定义，或者从「插入变量」里选。
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.condition {
  width: 100%;
}

.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
}

.preview {
  margin-top: 8px;
  line-height: 1.6;
}

.rendered {
  display: block;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-light);
  white-space: pre-wrap;
  word-break: break-all;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.bad {
  color: var(--el-color-danger);
  font-size: 13px;
}

.variable-menu .group {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  cursor: default;
}

.variable-menu .label {
  display: inline-block;
  min-width: 120px;
}

.variable-menu code {
  color: var(--el-text-color-secondary);
}
</style>

<script setup lang="ts">
/**
 * Picks a schedule by frequency instead of writing cron: every N minutes, hourly, daily, weekly or
 * monthly, each at a chosen time. It reads and writes XXL-JOB's Quartz cron (秒 分 时 日 月 周); a
 * cron it cannot read back opens in the custom mode, written by hand.
 */
import { computed, ref, watch } from 'vue'

type Mode = 'minutes' | 'hourly' | 'daily' | 'weekly' | 'monthly' | 'custom'

const cron = defineModel<string>({ required: true })

const MINUTE_STEPS = [5, 10, 15, 20, 30]
const HOUR_STEPS = [1, 2, 3, 4, 6, 8, 12]
const WEEKDAYS = [
  { value: 'MON', label: '周一' },
  { value: 'TUE', label: '周二' },
  { value: 'WED', label: '周三' },
  { value: 'THU', label: '周四' },
  { value: 'FRI', label: '周五' },
  { value: 'SAT', label: '周六' },
  { value: 'SUN', label: '周日' },
]
const MONTH_DAYS = [...Array.from({ length: 31 }, (_, i) => ({ value: String(i + 1), label: `${i + 1} 日` })), { value: 'L', label: '最后一天' }]

const mode = ref<Mode>('daily')
const minuteStep = ref(30)
const hourStep = ref(1)
const minute = ref(0)
const time = ref('02:00')
const weekdays = ref<string[]>(['MON'])
const monthDays = ref<string[]>(['1'])
const custom = ref('')

const num = (s: string) => (/^\d+$/.test(s) ? Number(s) : NaN)
const pad = (n: number) => String(n).padStart(2, '0')

/** Reads a cron into the pickers; false when it is not one the pickers can show. */
function read(value: string): boolean {
  const p = value.trim().split(/\s+/)
  if (p.length !== 6 || p[0] !== '0') return false
  const [, m, h, dom, mon, dow] = p
  if (mon !== '*') return false
  const everyMinutes = /^0\/(\d+)$/.exec(m)
  if (everyMinutes && h === '*' && dom === '*' && dow === '?' && MINUTE_STEPS.includes(Number(everyMinutes[1]))) {
    mode.value = 'minutes'
    minuteStep.value = Number(everyMinutes[1])
    return true
  }
  if (Number.isNaN(num(m)) || num(m) > 59) return false
  const everyHours = h === '*' ? 1 : /^0\/(\d+)$/.exec(h) ? Number(/^0\/(\d+)$/.exec(h)![1]) : NaN
  if (!Number.isNaN(everyHours) && dom === '*' && dow === '?' && HOUR_STEPS.includes(everyHours)) {
    mode.value = 'hourly'
    hourStep.value = everyHours
    minute.value = num(m)
    return true
  }
  if (Number.isNaN(num(h)) || num(h) > 23) return false
  time.value = `${pad(num(h))}:${pad(num(m))}`
  if (dom === '*' && dow === '?') {
    mode.value = 'daily'
    return true
  }
  const days = dow.split(',')
  if (dom === '?' && days.every((d) => WEEKDAYS.some((w) => w.value === d))) {
    mode.value = 'weekly'
    weekdays.value = days
    return true
  }
  const dates = dom.split(',')
  if (dow === '?' && dates.every((d) => MONTH_DAYS.some((x) => x.value === d))) {
    mode.value = 'monthly'
    monthDays.value = dates
    return true
  }
  return false
}

function write(): string {
  const [h, m] = time.value.split(':').map(Number)
  const byOrder = <T extends { value: string }>(all: T[], picked: string[]) =>
    all.filter((x) => picked.includes(x.value)).map((x) => x.value).join(',')
  switch (mode.value) {
    case 'minutes':
      return `0 0/${minuteStep.value} * * * ?`
    case 'hourly':
      return `0 ${minute.value} ${hourStep.value === 1 ? '*' : `0/${hourStep.value}`} * * ?`
    case 'daily':
      return `0 ${m} ${h} * * ?`
    case 'weekly':
      return `0 ${m} ${h} ? * ${byOrder(WEEKDAYS, weekdays.value) || 'MON'}`
    case 'monthly':
      return `0 ${m} ${h} ${byOrder(MONTH_DAYS, monthDays.value) || '1'} * ?`
    default:
      return custom.value.trim()
  }
}

/** Plain words for the chosen schedule. */
const summary = computed(() => {
  const label = (all: { value: string; label: string }[], picked: string[]) =>
    all.filter((x) => picked.includes(x.value)).map((x) => x.label).join('、')
  switch (mode.value) {
    case 'minutes':
      return `每 ${minuteStep.value} 分钟运行一次`
    case 'hourly':
      return `${hourStep.value === 1 ? '每小时' : `每 ${hourStep.value} 小时`}的第 ${minute.value} 分运行（${hourStep.value === 1 ? '' : '从 0 点起，'}共 ${24 / hourStep.value} 次/天）`
    case 'daily':
      return `每天 ${time.value} 运行`
    case 'weekly':
      return `每${label(WEEKDAYS, weekdays.value) || '周一'} ${time.value} 运行`
    case 'monthly':
      return `每月 ${label(MONTH_DAYS, monthDays.value) || '1 日'} ${time.value} 运行（没有这一天的月份跳过）`
    default:
      return ''
  }
})

// The pickers follow the model when it changes from outside (loading a saved schedule).
watch(
  cron,
  (value) => {
    if (value === write()) return
    // only a cron the pickers write back unchanged is shown in them, so loading never alters it
    if (!read(value) || write() !== value) {
      mode.value = 'custom'
      custom.value = value
    }
  },
  { immediate: true },
)

watch([mode, minuteStep, hourStep, minute, time, weekdays, monthDays, custom], () => {
  if (mode.value === 'custom' && !custom.value) custom.value = cron.value
  const next = write()
  if (next !== cron.value) cron.value = next
})
</script>

<template>
  <div class="cron-editor">
    <el-radio-group v-model="mode" class="modes">
      <el-radio-button value="minutes">每隔几分钟</el-radio-button>
      <el-radio-button value="hourly">每小时</el-radio-button>
      <el-radio-button value="daily">每天</el-radio-button>
      <el-radio-button value="weekly">每周</el-radio-button>
      <el-radio-button value="monthly">每月</el-radio-button>
      <el-radio-button value="custom">自定义</el-radio-button>
    </el-radio-group>

    <div class="pickers">
      <template v-if="mode === 'minutes'">
        <span>每</span>
        <el-select v-model="minuteStep" class="short">
          <el-option v-for="n in MINUTE_STEPS" :key="n" :label="n" :value="n" />
        </el-select>
        <span>分钟运行一次</span>
      </template>

      <template v-else-if="mode === 'hourly'">
        <span>每</span>
        <el-select v-model="hourStep" class="short">
          <el-option v-for="n in HOUR_STEPS" :key="n" :label="n" :value="n" />
        </el-select>
        <span>小时，在第</span>
        <el-input-number v-model="minute" :min="0" :max="59" controls-position="right" class="short" />
        <span>分钟运行</span>
      </template>

      <template v-else-if="mode === 'custom'">
        <el-input v-model="custom" class="custom" placeholder="秒 分 时 日 月 周，如 0 0 2 * * ?" />
      </template>

      <template v-else>
        <template v-if="mode === 'weekly'">
          <el-checkbox-group v-model="weekdays" class="days">
            <el-checkbox-button v-for="w in WEEKDAYS" :key="w.value" :value="w.value">{{ w.label }}</el-checkbox-button>
          </el-checkbox-group>
        </template>
        <template v-if="mode === 'monthly'">
          <el-select v-model="monthDays" multiple collapse-tags collapse-tags-tooltip placeholder="选择日期" class="month-days">
            <el-option v-for="d in MONTH_DAYS" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </template>
        <el-time-picker v-model="time" format="HH:mm" value-format="HH:mm" :clearable="false" placeholder="时间" class="time" />
      </template>
    </div>

    <div v-if="summary" class="summary">{{ summary }}</div>
    <div v-if="mode === 'custom'" class="tip">
      格式：秒 分 时 日 月 周，日和周中必须有一个是 <code>?</code>；两次运行至少间隔 2 分钟。例如每天 2 点 <code>0 0 2 * * ?</code>，每周一 1:30
      <code>0 30 1 ? * MON</code>，工作日 9 点 <code>0 0 9 ? * MON-FRI</code>
    </div>
  </div>
</template>

<style scoped>
.cron-editor {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
}

.pickers {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.short {
  width: 90px;
}

.time {
  width: 120px;
}

.month-days {
  width: 260px;
}

.custom {
  width: 320px;
}

.summary {
  color: var(--el-text-color-primary);
  font-size: 13px;
  font-weight: 500;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}

@media (max-width: 720px) {
  .modes {
    flex-wrap: wrap;
  }

  .custom,
  .month-days {
    width: 100%;
  }
}
</style>

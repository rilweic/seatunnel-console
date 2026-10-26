<script setup lang="ts">
/** The job's schedule (docs/design.md §5.2): cron, retries, timeout and XXL-JOB strategies. */
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import { jobApi, scheduleApi, type IncrementalView, type JobView, type ScheduleForm, type ScheduleView } from '@/api/console'
import CronEditor from '@/components/CronEditor.vue'
import { formatTime } from '@/utils/format'
import { errorMessage } from '@/utils/params'

const props = defineProps<{ job: JobView }>()
const emit = defineEmits<{ dirty: [dirty: boolean] }>()

function defaults(): ScheduleForm {
  return {
    cron: '0 0 2 * * ?',
    enabled: false,
    failRetryCount: 0,
    retryIntervalSeconds: 60,
    timeoutSeconds: 0,
    blockStrategy: 'SERIAL_EXECUTION',
    misfireStrategy: 'DO_NOTHING',
  }
}

const schedule = ref<ScheduleView | null>(null)
const form = ref<ScheduleForm>(defaults())
const saved = ref(JSON.stringify(defaults()))
const loaded = ref(false)
const saving = ref(false)
const cronPreview = ref<string[]>([])
const cronError = ref('')
let previewTimer: number | undefined
const incremental = ref<IncrementalView | null>(null)

/**
 * A day or hour window read by a schedule that fires more often syncs the same range again and
 * again (design.md §7.5); the continuous window is what such a schedule needs.
 */
const windowMismatch = computed(() => {
  const window = incremental.value?.window
  const span = window === 'DAY' ? 86_400_000 : window === 'HOUR' ? 3_600_000 : 0
  if (!span || cronPreview.value.length < 2) return ''
  const times = cronPreview.value.map((t) => new Date(t).getTime())
  const gap = Math.min(...times.slice(1).map((t, i) => t - times[i]!))
  if (gap >= span) return ''
  const unit = window === 'DAY' ? '一天' : '一个小时'
  return `同步配置的时间窗口是「按${window === 'DAY' ? '天' : '小时'}」，每次同步计划时间之前的完整${unit}；这个调度比${unit}一次更频繁，同${unit}的数据会被反复同步，而当前这${unit}的新数据要等下${unit}才同步。建议在「同步配置」里改用「连续增量」，或把调度改为${unit}一次。`
})

const dirty = computed(() => loaded.value && JSON.stringify(form.value) !== saved.value)
watch(dirty, (value) => emit('dirty', value))

function apply(view: ScheduleView | null) {
  schedule.value = view
  if (view) {
    const { cron, enabled, failRetryCount, retryIntervalSeconds, timeoutSeconds, blockStrategy, misfireStrategy } = view
    form.value = { cron, enabled, failRetryCount, retryIntervalSeconds, timeoutSeconds, blockStrategy, misfireStrategy }
  }
  saved.value = JSON.stringify(form.value)
}

async function previewCron() {
  try {
    cronPreview.value = await scheduleApi.preview(form.value.cron)
    cronError.value = ''
  } catch (e) {
    cronPreview.value = []
    cronError.value = errorMessage(e, 'cron 无效')
  }
}

watch(
  () => form.value.cron,
  () => {
    window.clearTimeout(previewTimer)
    previewTimer = window.setTimeout(previewCron, 400)
  },
)

async function save() {
  saving.value = true
  try {
    const view = await scheduleApi.save(props.job.id, form.value)
    apply(view)
    if (view.syncStatus === 'SYNCED') {
      ElMessage.success(view.enabled ? '调度已启用' : '调度已保存（未启用）')
    } else {
      ElMessage.warning('调度已保存，但同步到 XXL-JOB 失败，请稍后重新保存')
    }
  } finally {
    saving.value = false
  }
}

const syncText = { PENDING: '待同步', SYNCED: '已同步', FAILED: '同步失败' } as const
const syncTag = { PENDING: 'info', SYNCED: 'success', FAILED: 'danger' } as const

onMounted(async () => {
  jobApi.incremental(props.job.id).then((v) => (incremental.value = v), () => undefined)
  apply(await scheduleApi.get(props.job.id))
  loaded.value = true
  previewCron()
})
</script>

<template>
  <div class="schedule">
    <el-alert v-if="schedule?.syncStatus === 'FAILED'" type="error" :closable="false" show-icon class="sync-alert"
      :title="`同步到 XXL-JOB 失败：${schedule.syncError ?? ''}`" description="console 中的调度已保存，修正问题后重新保存即可再次同步。" />
    <el-form label-width="120px" class="schedule-form">
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" :disabled="!job.currentVersionNo" />
        <span v-if="!job.currentVersionNo" class="tip gap">先在「同步配置」保存一个版本才能启用</span>
        <span v-else class="tip gap">定时运行总是使用触发时的当前版本；任务详情页也可以直接启用或暂停</span>
      </el-form-item>
      <el-form-item label="运行时间">
        <CronEditor v-model="form.cron" />
        <div class="cron-help">
          <div v-if="cronError" class="cron-error">{{ cronError }}</div>
          <div v-else-if="cronPreview.length" class="next">
            <span class="tip">接下来 {{ cronPreview.length }} 次：</span>
            <el-tag v-for="t in cronPreview" :key="t" type="info" disable-transitions>{{ formatTime(t) }}</el-tag>
          </div>
          <div class="tip">对应 cron 表达式：<code>{{ form.cron }}</code></div>
          <el-alert v-if="windowMismatch" type="warning" :closable="false" show-icon :title="windowMismatch" />
        </div>
      </el-form-item>
      <el-form-item label="失败重试次数">
        <el-input-number v-model="form.failRetryCount" :min="0" :max="10" />
        <span class="tip gap">对定时运行和补数生效，重试记录在同一个运行实例里</span>
      </el-form-item>
      <el-form-item label="重试间隔（秒）">
        <el-input-number v-model="form.retryIntervalSeconds" :min="0" :max="86400" :step="30" />
      </el-form-item>
      <el-form-item label="超时（秒）">
        <el-input-number v-model="form.timeoutSeconds" :min="0" :step="60" />
        <span class="tip gap">0 表示不限；超时后停止 SeaTunnel 作业，运行记为已取消。对手动运行同样生效</span>
      </el-form-item>
      <el-form-item label="上次未结束时">
        <el-radio-group v-model="form.blockStrategy">
          <el-radio value="SERIAL_EXECUTION">排队等待</el-radio>
          <el-radio value="DISCARD_LATER">丢弃本次</el-radio>
          <el-radio value="COVER_EARLY">停止上次，执行本次</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="错过的定时运行">
        <el-radio-group v-model="form.misfireStrategy">
          <el-radio value="DO_NOTHING">忽略</el-radio>
          <el-radio value="FIRE_ONCE_NOW">立即补一次</el-radio>
        </el-radio-group>
      </el-form-item>
    </el-form>
    <div class="save-bar">
      <template v-if="schedule">
        <el-tag :type="syncTag[schedule.syncStatus]">{{ syncText[schedule.syncStatus] }}</el-tag>
        <span class="tip">XXL-JOB 任务 ID：{{ schedule.xxlJobId ?? '-' }}<template v-if="schedule.syncedAt"> · 同步于 {{ formatTime(schedule.syncedAt) }}</template></span>
      </template>
      <span class="spacer" />
      <span v-if="dirty" class="unsaved">有未保存的修改</span>
      <el-button type="primary" :loading="saving" :disabled="!!cronError || (!dirty && !!schedule)" @click="save">保存调度</el-button>
    </div>
  </div>
</template>

<style scoped>
.schedule-form {
  max-width: 900px;
}

.sync-alert {
  margin-bottom: 16px;
}

.cron-help {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  margin-top: 10px;
  line-height: 1.6;
}

.next {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.cron-error {
  color: var(--el-color-danger);
  font-size: 13px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.gap {
  margin-left: 12px;
}

.save-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}

.spacer {
  flex: 1;
}

.unsaved {
  color: var(--el-color-warning);
  font-size: 13px;
}
</style>

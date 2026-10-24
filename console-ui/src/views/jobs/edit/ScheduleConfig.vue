<script setup lang="ts">
/** The job's schedule (docs/design.md §5.2): cron, retries, timeout and XXL-JOB strategies. */
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import { scheduleApi, type JobView, type ScheduleForm, type ScheduleView } from '@/api/console'
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
      <el-form-item label="Cron 表达式">
        <el-input v-model="form.cron" class="cron" placeholder="秒 分 时 日 月 周，如 0 0 2 * * ?" />
        <div class="cron-help">
          <div v-if="cronError" class="cron-error">{{ cronError }}</div>
          <div v-else-if="cronPreview.length" class="tip">接下来 {{ cronPreview.length }} 次：{{ cronPreview.map(formatTime).join('，') }}</div>
          <div class="tip">格式：秒 分 时 日 月 周，日和周中必须有一个是 <code>?</code>。示例：每天 2 点 <code>0 0 2 * * ?</code>，每小时 <code>0 0 * * * ?</code>，每周一 1:30 <code>0 30 1 ? * MON</code></div>
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
      <el-form-item label="错过的触发">
        <el-radio-group v-model="form.misfireStrategy">
          <el-radio value="DO_NOTHING">忽略</el-radio>
          <el-radio value="FIRE_ONCE_NOW">立即补一次</el-radio>
        </el-radio-group>
        <span class="tip gap">XXL-JOB 停机期间错过的触发</span>
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

.cron {
  width: 260px;
}

.cron-help {
  width: 100%;
  line-height: 1.6;
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

<script setup lang="ts">
/** Starts a backfill over a date range with the job's current version (docs/design.md §9). */
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'

import { backfillApi, type BackfillPlan } from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import { formatTime } from '@/utils/format'
import { errorMessage, paramsProblem, toMap } from '@/utils/params'

const props = defineProps<{ jobId: string; versionNo: number | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ created: [] }>()

const range = ref<[string, string] | null>(null)
const params = ref<ParamRow[]>([])
const plan = ref<BackfillPlan | null>(null)
const planError = ref('')
const creating = ref(false)

watch(visible, (open) => {
  if (open) {
    range.value = null
    params.value = []
  }
})

watch(range, async (value) => {
  plan.value = null
  planError.value = ''
  if (!value) return
  try {
    plan.value = await backfillApi.plan(props.jobId, value[0], value[1])
  } catch (e) {
    planError.value = errorMessage(e, '无法计算补数计划')
  }
})

async function create() {
  const paramError = paramsProblem(params.value)
  if (paramError) {
    ElMessage.warning(paramError)
    return
  }
  if (!range.value) return
  creating.value = true
  try {
    await backfillApi.create(props.jobId, { startDate: range.value[0], endDate: range.value[1], params: toMap(params.value) })
    visible.value = false
    ElMessage.success('补数已开始，会按时间顺序逐个运行')
    emit('created')
  } finally {
    creating.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="补数" width="700px">
    <el-form label-width="90px">
      <el-form-item label="日期范围">
        <el-date-picker v-model="range" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" />
        <div class="tip">
          选的是<b>计划时间</b>的日期，<code>${system.biz.date}</code> 是计划时间的前一天。任务有调度时，按调度的 cron 生成范围内的每个触发时间；没有调度时每天 00:00 一次。
        </div>
      </el-form-item>
      <el-form-item v-if="plan || planError" label="计划">
        <span v-if="planError" class="bad">{{ planError }}</span>
        <span v-else-if="plan">
          共 {{ plan.totalRuns }} 次，{{ formatTime(plan.firstTime) }} 到 {{ formatTime(plan.lastTime) }}
          <span class="tip">（{{ plan.cron ? `按调度 ${plan.cron}` : '每天 00:00' }}，使用当前版本 v{{ versionNo }}）</span>
        </span>
      </el-form-item>
      <el-form-item label="启动参数">
        <ParamsEditor v-model="params" />
        <div class="tip">对每一次运行都生效</div>
      </el-form-item>
    </el-form>
    <p class="tip">
      按时间顺序<b>逐个</b>运行：上一次结束后才触发下一次（XXL-JOB 对同一个任务只能逐个执行）。某一次失败不会中断补数，结束后可以单独重跑；
      失败重试次数和超时沿用调度配置。补数进行中时，定时调度仍会照常触发。
    </p>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="creating" :disabled="!plan" @click="create">开始补数</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.bad {
  color: var(--el-color-danger);
}
</style>

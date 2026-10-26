<script setup lang="ts">
/** "立即运行": a manual run with a chosen scheduled time and one-off parameters; opens the run. */
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, type IncrementalView } from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import { formatTime } from '@/utils/format'
import { nowText, paramsProblem, toMap } from '@/utils/params'

const props = defineProps<{ jobId: string }>()
const visible = defineModel<boolean>({ required: true })

const router = useRouter()
const scheduledTime = ref('')
const params = ref<ParamRow[]>([])
const running = ref(false)
const incremental = ref<IncrementalView | null>(null)

watch(visible, (open) => {
  if (open) {
    scheduledTime.value = nowText()
    params.value = []
    jobApi.incremental(props.jobId).then((v) => (incremental.value = v), () => undefined)
  }
})

async function run() {
  const paramError = paramsProblem(params.value)
  if (paramError) {
    ElMessage.warning(paramError)
    return
  }
  running.value = true
  try {
    const { instanceId } = await jobApi.run(props.jobId, { scheduledTime: scheduledTime.value || undefined, params: toMap(params.value) })
    visible.value = false
    ElMessage.success('已提交运行')
    router.push(`/instances/${instanceId}`)
  } finally {
    running.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="立即运行" width="680px">
    <el-form label-width="90px">
      <el-form-item label="计划时间">
        <el-date-picker v-model="scheduledTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" :clearable="false" />
        <div class="tip">变量和时间表达式以它为基准，默认是现在。比如要跑昨天的数据，可以改成今天 00:00 之后的某个时间点。</div>
        <div v-if="incremental?.usesWindow" class="tip">
          这个任务按连续增量同步：从当前水位（{{ incremental.watermark ? formatTime(incremental.watermark) : '还没有，从最早的数据开始' }}）同步到计划时间往前推延迟，成功后推进水位。
        </div>
      </el-form-item>
      <el-form-item label="启动参数">
        <ParamsEditor v-model="params" />
        <div class="tip">只对这次运行生效，优先级最高</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="running" @click="run">运行</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
</style>

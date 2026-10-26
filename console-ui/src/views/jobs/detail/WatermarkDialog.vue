<script setup lang="ts">
/**
 * Sets where a continuous incremental job carries on from, and how far behind the scheduled time
 * its windows end (docs/design.md §7.5).
 */
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'

import { jobApi, type IncrementalView } from '@/api/console'

const props = defineProps<{ jobId: string; incremental: IncrementalView | null }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ saved: [view: IncrementalView] }>()

const fromBeginning = ref(false)
const watermark = ref<string | null>(null)
const lagMinutes = ref(1)
const saving = ref(false)

watch(visible, (open) => {
  if (open && props.incremental) {
    fromBeginning.value = props.incremental.watermark === null
    watermark.value = props.incremental.watermark?.slice(0, 19) ?? null
    lagMinutes.value = props.incremental.lagSeconds / 60
  }
})

async function save() {
  if (!fromBeginning.value && !watermark.value) {
    ElMessage.warning('请选择从哪个时间开始')
    return
  }
  saving.value = true
  try {
    const view = await jobApi.updateIncremental(props.jobId, {
      watermark: fromBeginning.value ? null : watermark.value,
      lagSeconds: Math.round(lagMinutes.value * 60),
    })
    ElMessage.success('已保存，下一次运行生效')
    visible.value = false
    emit('saved', view)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="调整增量水位" width="620px">
    <el-form label-width="110px">
      <el-form-item label="下次从">
        <el-radio-group v-model="fromBeginning">
          <el-radio :value="false">指定时间</el-radio>
          <el-radio :value="true">最早的数据</el-radio>
        </el-radio-group>
        <el-date-picker
          v-if="!fromBeginning"
          v-model="watermark"
          type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss"
          placeholder="选择时间"
          class="picker"
        />
        <div class="tip">
          下一次运行同步 [这个时间, 计划时间 − 延迟)。往前调可以重新同步一段历史数据；往后调会跳过中间的数据。
          正在运行的不受影响，它结束后也不会覆盖这里设置的水位。
        </div>
      </el-form-item>
      <el-form-item label="延迟（分钟）">
        <el-input-number v-model="lagMinutes" :min="0" :max="1440" :step="1" :precision="1" />
        <div class="tip">窗口终点比计划时间早这么久，给还没提交的事务留出时间。业务数据写入有延迟时调大。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.picker {
  margin-left: 12px;
}

.tip {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}
</style>

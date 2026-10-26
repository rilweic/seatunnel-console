<script setup lang="ts">
/**
 * Starting a real-time job (design.md §12 P2, Q10 and Q27): from where the last run left off (the
 * savepoint of a stop, or the latest checkpoint after a failure or a forced stop), or fresh. The
 * default is what the server would do: a restore point unless the source changed or restoring
 * would fail again.
 */
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { streamApi, type StreamRestore, type StreamView } from '@/api/console'
import ParamsEditor, { type ParamRow } from '@/components/ParamsEditor.vue'
import { paramsProblem, toMap } from '@/utils/params'

const props = defineProps<{ view: StreamView }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ started: [view: StreamView] }>()

const restore = ref<StreamRestore>('AUTO')
const params = ref<ParamRow[]>([])
const starting = ref(false)

/** Whether a start restores when nothing is chosen. */
const restorable = computed(() => !!props.view.restoreKind && !props.view.restoreBlocked)
const defaultRestores = computed(() => restorable.value && !props.view.sourceChanged)

watch(visible, (open) => {
  if (open) {
    restore.value = defaultRestores.value ? (props.view.restoreKind as StreamRestore) : 'FRESH'
    params.value = []
  }
})

async function start() {
  const problem = paramsProblem(params.value)
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  starting.value = true
  try {
    const view = await streamApi.start(props.view.jobId, { restore: restore.value, startupParams: toMap(params.value) })
    visible.value = false
    ElMessage.success('已启动')
    emit('started', view)
  } finally {
    starting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="启动实时任务" width="640px">
    <el-form label-width="90px">
      <el-form-item label="从哪里开始">
        <el-radio-group v-model="restore" class="choices">
          <el-radio value="SAVEPOINT" :disabled="!restorable || view.restoreKind !== 'SAVEPOINT'">
            从 savepoint 恢复<span class="tip">：上次正常停止时保存的位置，接着同步</span>
          </el-radio>
          <el-radio value="CHECKPOINT" :disabled="!restorable || view.restoreKind !== 'CHECKPOINT'">
            从最近的 checkpoint 恢复<span class="tip">：上次失败或强制停止前最后保存的位置，之后的变更会再同步一遍</span>
          </el-radio>
          <el-radio value="FRESH">
            全新启动<span class="tip">：按任务设置重新开始（"全量 + 增量"会重新同步全部数据）</span>
          </el-radio>
        </el-radio-group>
        <el-alert v-if="view.restoreBlocked" type="warning" :closable="false" show-icon class="note" :title="`只能全新启动：${view.restoreBlocked}`" />
        <el-alert
          v-else-if="restorable && view.sourceChanged"
          type="warning"
          :closable="false"
          show-icon
          class="note"
          title="当前版本的来源或目标设置和上次运行的版本不同，从保存的位置恢复多半会失败，默认全新启动。全新启动会按设置重新同步（全量 + 增量时重新全量）"
        />
        <div v-else-if="!view.restoreKind" class="tip">还没有可以恢复的位置，第一次启动按任务设置全新开始。</div>
      </el-form-item>
      <el-form-item label="启动参数">
        <ParamsEditor v-model="params" />
        <div class="tip">只对这次启动生效，优先级最高</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="starting" @click="start">启动</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.choices {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

/* the explanations are long: let them wrap under the option */
.choices :deep(.el-radio) {
  height: auto;
  margin-right: 0;
  white-space: normal;
  line-height: 1.6;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.note {
  margin-top: 8px;
}
</style>

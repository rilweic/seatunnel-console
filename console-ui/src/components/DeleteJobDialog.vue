<script setup lang="ts">
import { ref, watch } from 'vue'

defineProps<{ jobName: string }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ confirm: [deleteRuns: boolean] }>()
const deleteRuns = ref(true)

watch(visible, (open) => {
  if (open) deleteRuns.value = true
})

function confirm() {
  emit('confirm', deleteRuns.value)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" title="删除任务" width="500px">
    <div class="message">删除任务「{{ jobName }}」？调度会一起删除。</div>
    <el-checkbox v-model="deleteRuns" class="delete-runs">同时删除历史运行记录</el-checkbox>
    <el-alert
      v-if="deleteRuns"
      title="运行实例、补数记录、指标和归档日志将一并永久删除，无法恢复。"
      type="error"
      :closable="false"
      show-icon
    />
    <el-alert
      v-else
      title="保留的运行记录会显示原任务名和“已删除”标记，但无法再打开原任务详情。"
      type="warning"
      :closable="false"
      show-icon
    />
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="danger" @click="confirm">删除</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.message {
  color: var(--el-text-color-primary);
  line-height: 1.7;
}

.delete-runs {
  display: flex;
  margin: 18px 0 14px;
}
</style>

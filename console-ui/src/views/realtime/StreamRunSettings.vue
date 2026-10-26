<script setup lang="ts">
/** "运行设置" of a real-time job (design.md §12 P2, Q20): automatic restart after a failure. */
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import { streamApi, type JobView, type StreamRestartSettings } from '@/api/console'
import HelpTip from '@/components/HelpTip.vue'

const props = defineProps<{ job: JobView }>()
const emit = defineEmits<{ dirty: [dirty: boolean] }>()

const form = ref<StreamRestartSettings>({ autoRestart: false, maxRestarts: 3, restartIntervalSeconds: 30 })
const saved = ref('')
const saving = ref(false)
const dirty = computed(() => saved.value !== '' && JSON.stringify(form.value) !== saved.value)
watch(dirty, (d) => emit('dirty', d))

onMounted(async () => {
  form.value = { ...(await streamApi.view(props.job.id)).settings }
  saved.value = JSON.stringify(form.value)
})

async function save() {
  saving.value = true
  try {
    form.value = { ...(await streamApi.settings(props.job.id, form.value)).settings }
    saved.value = JSON.stringify(form.value)
    ElMessage.success('已保存，立即生效')
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-form label-width="130px" class="form">
    <el-form-item label="失败后自动重启">
      <el-switch v-model="form.autoRestart" />
      <HelpTip text="作业失败后，等一段时间从最近一次 checkpoint 重新启动。因为源表结构变更导致的失败不会自动重启（重启只会再失败一次）。正常运行 10 分钟以上，连续重启的计数清零" />
    </el-form-item>
    <template v-if="form.autoRestart">
      <el-form-item label="最多连续重启">
        <el-input-number v-model="form.maxRestarts" :min="1" :max="20" /> <span class="unit">次</span>
      </el-form-item>
      <el-form-item label="重启间隔">
        <el-input-number v-model="form.restartIntervalSeconds" :min="10" :max="3600" :step="10" /> <span class="unit">秒</span>
      </el-form-item>
    </template>
    <el-form-item>
      <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</el-button>
      <span class="tip">运行设置不属于版本，保存后立即生效，不需要重启任务</span>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form {
  max-width: 640px;
  padding-top: 8px;
}

.unit {
  margin-left: 8px;
}

.tip {
  margin-left: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>

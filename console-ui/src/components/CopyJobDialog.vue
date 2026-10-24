<script setup lang="ts">
/** Copies a job (docs/design.md §7.9): its current version becomes v1 of the new job; the schedule comes along disabled. */
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, projectApi, type ProjectView } from '@/api/console'

const props = defineProps<{ job: { id: string; name: string; projectId: string } | null }>()
const visible = defineModel<boolean>({ required: true })

const router = useRouter()
const name = ref('')
const projectId = ref('')
const projects = ref<ProjectView[]>([])
const copying = ref(false)

watch(visible, async (open) => {
  if (!open || !props.job) return
  name.value = `${props.job.name}_copy`
  projectId.value = props.job.projectId
  projects.value = (await projectApi.list({ size: 200 })).items.filter((p) => p.editable)
  if (!projects.value.some((p) => p.id === projectId.value)) projectId.value = projects.value[0]?.id ?? ''
})

async function copy() {
  if (!props.job || !name.value.trim() || !projectId.value) return
  copying.value = true
  try {
    const { job, scheduleCopied } = await jobApi.copy(props.job.id, { name: name.value.trim(), projectId: projectId.value })
    ElMessage.success(scheduleCopied ? '已复制，调度设置也复制了过来（未启用）' : '已复制')
    visible.value = false
    router.push(`/jobs/${job.id}`)
  } finally {
    copying.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="复制任务" width="520px">
    <el-form label-width="80px" @submit.prevent="copy">
      <el-form-item label="名称" required>
        <el-input v-model="name" maxlength="128" />
      </el-form-item>
      <el-form-item label="项目" required>
        <el-select v-model="projectId" filterable placeholder="选择项目" class="full">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>
    </el-form>
    <ul class="notes">
      <li>复制当前版本的配置、任务参数和描述，作为新任务的 v1</li>
      <li>调度设置会一起复制，但默认不启用</li>
      <li>运行记录、补数和历史版本不复制；新任务的创建人是你，只能使用你自己的数据源</li>
    </ul>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="copying" :disabled="!name.trim() || !projectId" @click="copy">复制</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full {
  width: 100%;
}

.notes {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.8;
}
</style>

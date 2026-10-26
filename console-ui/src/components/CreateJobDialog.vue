<script setup lang="ts">
/** New offline or real-time job: project, name and how it is configured; opens its editor after. */
import { ElMessage } from 'element-plus'
import { reactive, watch } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, type JobView, type ProjectView } from '@/api/console'

const props = defineProps<{
  jobType: JobView['jobType']
  /** projects the user may change, the only ones a job can be created in */
  projects: ProjectView[]
  defaultProjectId?: string
}>()
const visible = defineModel<boolean>({ required: true })
const router = useRouter()

const form = reactive({ projectId: '', name: '', editMode: 'FORM' as 'FORM' | 'SCRIPT', description: '', saving: false })

watch(visible, (open) => {
  if (open) {
    Object.assign(form, {
      projectId: props.projects.find((p) => p.id === props.defaultProjectId)?.id ?? props.projects[0]?.id ?? '',
      name: '',
      editMode: 'FORM',
      description: '',
    })
  }
})

const streaming = () => props.jobType === 'STREAMING'

async function create() {
  if (!form.name.trim() || !form.projectId) {
    ElMessage.warning('请选择项目并填写任务名称')
    return
  }
  form.saving = true
  try {
    const job = await jobApi.create({
      projectId: form.projectId,
      name: form.name,
      jobType: props.jobType,
      editMode: form.editMode,
      description: form.description || undefined,
    })
    visible.value = false
    router.push(streaming() ? `/realtime/${job.id}/edit` : `/jobs/${job.id}/edit`)
  } finally {
    form.saving = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" :title="streaming() ? '新建实时任务' : '新建离线任务'" width="520px">
    <el-form label-width="80px">
      <el-form-item label="项目" required>
        <el-select v-model="form.projectId" class="full" placeholder="只能在自己的项目里建任务">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="form.name" maxlength="128" />
      </el-form-item>
      <el-form-item label="编辑方式">
        <el-radio-group v-model="form.editMode">
          <el-radio value="FORM">向导（推荐）</el-radio>
          <el-radio value="SCRIPT">脚本</el-radio>
        </el-radio-group>
        <div class="mode-tip">
          <template v-if="form.editMode === 'SCRIPT'">
            直接编写 SeaTunnel 的 HOCON 配置，适合向导不支持的连接器和写法。{{ streaming() ? 'env.job.mode 必须是 STREAMING。' : '' }}
          </template>
          <template v-else-if="streaming()">
            一步步选择数据源和表，自动生成配置：先同步现有数据，再持续同步变更。支持 MySQL / PostgreSQL 到 MySQL / PostgreSQL / ES。以后可以转为脚本。
          </template>
          <template v-else>
            一步步选择数据源、表和同步方式，自动生成配置。支持 MySQL / PostgreSQL 到 MySQL / PostgreSQL / ES。以后可以转为脚本。
          </template>
        </div>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="form.description" type="textarea" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="form.saving" @click="create">创建并编辑</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.full {
  width: 100%;
}

.mode-tip {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>

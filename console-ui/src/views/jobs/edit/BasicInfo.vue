<script setup lang="ts">
/** Name, description and project. Not part of a version: saving does not create one. */
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'

import { jobApi, projectApi, type JobView, type ProjectView } from '@/api/console'

const props = defineProps<{ job: JobView }>()
const emit = defineEmits<{ saved: [job: JobView]; dirty: [dirty: boolean] }>()

const form = ref({ name: props.job.name, description: props.job.description ?? '', projectId: props.job.projectId })
const saved = ref(JSON.stringify(form.value))
const saving = ref(false)
const projects = ref<ProjectView[]>([])

/** A job can move only into projects the user may change; its current project is always listed. */
const targets = computed(() => projects.value.filter((p) => p.editable || p.id === props.job.projectId))
const dirty = computed(() => JSON.stringify(form.value) !== saved.value)
watch(dirty, (value) => emit('dirty', value))

async function save() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请填写任务名称')
    return
  }
  saving.value = true
  try {
    const job = await jobApi.update(props.job.id, {
      name: form.value.name.trim(),
      description: form.value.description.trim() || null,
      projectId: form.value.projectId,
    })
    form.value.name = job.name
    saved.value = JSON.stringify(form.value)
    ElMessage.success('已保存')
    emit('saved', job)
  } finally {
    saving.value = false
  }
}

onMounted(async () => {
  projects.value = (await projectApi.list({ size: 200 })).items
})
</script>

<template>
  <el-form label-width="90px" class="basic">
    <el-form-item label="任务名称" required>
      <el-input v-model="form.name" maxlength="128" show-word-limit />
    </el-form-item>
    <el-form-item label="描述">
      <el-input v-model="form.description" type="textarea" :rows="3" maxlength="512" show-word-limit placeholder="这个任务同步什么、给谁用" />
    </el-form-item>
    <el-form-item label="所属项目">
      <el-select v-model="form.projectId" filterable class="project">
        <el-option v-for="p in targets" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <div class="tip">只能移到你有权限修改的项目。移动后，运行时引用的是新项目的项目参数；已有的运行记录不变。</div>
    </el-form-item>
  </el-form>
  <div class="save-bar">
    <span class="tip">名称、描述和项目不属于运行配置，保存后不会生成新版本。</span>
    <span class="spacer" />
    <span v-if="dirty" class="unsaved">有未保存的修改</span>
    <el-button type="primary" :loading="saving" :disabled="!dirty" @click="save">保存</el-button>
  </div>
</template>

<style scoped>
.basic {
  max-width: 720px;
}

.project {
  width: 320px;
}

.tip {
  width: 100%;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.save-bar .tip {
  width: auto;
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

<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, projectApi, type JobView, type ProjectView } from '@/api/console'
import { formatTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'

const router = useRouter()

const rows = ref<JobView[]>([])
const total = ref(0)
const projects = ref<ProjectView[]>([])
const { state: query, apply } = useQueryState({ projectId: '', keyword: '', mine: true, page: 1, size: 20 }, () => load())
/** New jobs can only go into projects the user may change. */
const ownProjects = computed(() => projects.value.filter((p) => p.editable))

const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await jobApi.list(query)
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  projects.value = (await projectApi.list({ size: 200 })).items
}

const dialog = reactive({
  visible: false,
  projectId: '',
  name: '',
  editMode: 'FORM' as 'FORM' | 'SCRIPT',
  description: '',
  saving: false,
})

function openCreate() {
  if (ownProjects.value.length === 0) {
    ElMessage.warning('你还没有自己的项目，请先在「项目」中新建一个')
    return
  }
  Object.assign(dialog, { visible: true, projectId: ownProjects.value.find((p) => p.id === query.projectId)?.id ?? ownProjects.value[0]!.id, name: '', editMode: 'FORM', description: '' })
}

async function create() {
  if (!dialog.name.trim() || !dialog.projectId) {
    ElMessage.warning('请选择项目并填写任务名称')
    return
  }
  dialog.saving = true
  try {
    const job = await jobApi.create({
      projectId: dialog.projectId,
      name: dialog.name,
      jobType: 'BATCH',
      editMode: dialog.editMode,
      description: dialog.description || undefined,
    })
    dialog.visible = false
    router.push(`/jobs/${job.id}`)
  } finally {
    dialog.saving = false
  }
}

async function remove(row: JobView) {
  await ElMessageBox.confirm(`删除任务「${row.name}」？历史运行记录会保留。`, '删除任务', { type: 'warning' })
  await jobApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

onMounted(() => {
  loadProjects()
  load()
})
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="任务" description="离线同步任务。点击任务进入配置、版本、调度、补数和运行记录。">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建任务</el-button>
    </PageHeader>
    <div class="toolbar">
      <div class="filters">
        <el-radio-group v-model="query.mine" class="scope" @change="apply()">
        <el-radio-button :value="true">我的</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>
        <el-select v-model="query.projectId" placeholder="全部项目" clearable class="project" @change="apply()">
          <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="搜索任务名称" clearable class="search" @change="apply()" />
      </div>
    </div>
    <el-table v-loading="loading" :data="rows" @row-click="(row: JobView) => router.push(`/jobs/${row.id}`)">
      <el-table-column prop="name" label="名称" min-width="180" />
      <el-table-column prop="projectName" label="项目" min-width="120" />
      <el-table-column label="类型" width="90">
        <template #default>离线</template>
      </el-table-column>
      <el-table-column label="当前版本" width="100">
        <template #default="{ row }">{{ row.currentVersionNo ? `v${row.currentVersionNo}` : '未保存' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
<el-table-column label="创建人" width="120">
        <template #default="{ row }">{{ row.ownerName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" :disabled="!row.editable" @click.stop="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="apply(false)"
    />
  </el-card>

  <el-dialog v-model="dialog.visible" title="新建离线任务" width="520px">
    <el-form label-width="80px">
      <el-form-item label="项目" required>
        <el-select v-model="dialog.projectId" class="full" placeholder="只能在自己的项目里建任务">
          <el-option v-for="p in ownProjects" :key="p.id" :label="p.name" :value="p.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="名称" required>
        <el-input v-model="dialog.name" maxlength="128" />
      </el-form-item>
      <el-form-item label="编辑方式">
        <el-radio-group v-model="dialog.editMode">
          <el-radio value="FORM">向导（推荐）</el-radio>
          <el-radio value="SCRIPT">脚本</el-radio>
        </el-radio-group>
        <div class="mode-tip">
          {{ dialog.editMode === 'FORM' ? '一步步选择数据源、表和同步方式，自动生成配置。支持 MySQL / PostgreSQL 到 MySQL / PostgreSQL / ES。以后可以转为脚本。' : '直接编写 SeaTunnel 的 HOCON 配置，适合向导不支持的连接器和写法。' }}
        </div>
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="dialog.description" type="textarea" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="dialog.saving" @click="create">创建并编辑</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.filters {
  display: flex;
  gap: 12px;
}

.project {
  width: 180px;
}

.search {
  width: 240px;
}

.full {
  width: 100%;
}

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}

:deep(.el-table__row) {
  cursor: pointer;
}
.mode-tip {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.scope {
  flex: none;
}
</style>

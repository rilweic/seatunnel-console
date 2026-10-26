<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, projectApi, type JobView, type ProjectView, type RunStatus } from '@/api/console'
import { formatTime, statusTag, statusText } from '@/utils/format'
import CopyJobDialog from '@/components/CopyJobDialog.vue'
import CreateJobDialog from '@/components/CreateJobDialog.vue'
import DeleteJobDialog from '@/components/DeleteJobDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'

const router = useRouter()
/** The job the copy dialog is open for. */
const copying = ref<JobView | null>(null)
const copyOpen = computed({ get: () => copying.value !== null, set: (open) => { if (!open) copying.value = null } })
const deleteDialog = reactive({ visible: false, job: null as JobView | null })

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
    const result = await jobApi.list({ ...query, jobType: 'BATCH' })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

async function loadProjects() {
  projects.value = (await projectApi.list({ size: 200 })).items
}

const createOpen = ref(false)

function openCreate() {
  if (ownProjects.value.length === 0) {
    ElMessage.warning('你还没有自己的项目，请先在「项目」中新建一个')
    return
  }
  createOpen.value = true
}

function remove(row: JobView) {
  deleteDialog.job = row
  deleteDialog.visible = true
}

async function confirmRemove(deleteRuns: boolean) {
  if (!deleteDialog.job) return
  await jobApi.remove(deleteDialog.job.id, deleteRuns)
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
    <PageHeader title="离线同步" description="按时运行的批量同步任务。点击任务进入配置、版本、调度、补数和运行记录。">
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
    <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: JobView) => router.push(`/jobs/${row.id}`)">
      <el-table-column label="名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="name">{{ row.name }}</span>
          <div v-if="row.description" class="sub">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="projectName" label="项目" min-width="120" show-overflow-tooltip />
      <el-table-column label="配置方式" width="90">
        <template #default="{ row }">{{ row.editMode === 'FORM' ? '向导' : '脚本' }}</template>
      </el-table-column>
      <el-table-column label="当前版本" width="90">
        <template #default="{ row }">{{ row.currentVersionNo ? `v${row.currentVersionNo}` : '未保存' }}</template>
      </el-table-column>
      <el-table-column label="最近运行" width="220">
        <template #default="{ row }">
          <template v-if="row.activity?.lastRunStatus">
            <el-tag :type="statusTag[row.activity.lastRunStatus as RunStatus]">{{ statusText[row.activity.lastRunStatus as RunStatus] }}</el-tag>
            <span class="sub inline">{{ formatTime(row.activity.lastRunAt).slice(0, 16) }}</span>
          </template>
          <span v-else class="sub">未运行</span>
        </template>
      </el-table-column>
      <el-table-column label="调度" width="190">
        <template #default="{ row }">
          <template v-if="row.activity?.cron">
            <el-tag :type="row.activity.scheduleEnabled ? 'success' : 'info'">{{ row.activity.scheduleEnabled ? '已启用' : '已暂停' }}</el-tag>
            <span class="sub inline mono">{{ row.activity.cron }}</span>
          </template>
          <span v-else class="sub">手动</span>
        </template>
      </el-table-column>
      <el-table-column label="创建人" width="110">
        <template #default="{ row }">{{ row.ownerName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="router.push(`/jobs/${row.id}`)">详情</el-button>
          <el-button link type="primary" :disabled="!row.editable" @click.stop="router.push(`/jobs/${row.id}/edit`)">编辑</el-button>
          <el-button link type="primary" @click.stop="copying = row">复制</el-button>
          <el-button link type="danger" :disabled="!row.editable" @click.stop="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @size-change="apply()"
      @current-change="apply(false)"
    />
  </el-card>

  <CreateJobDialog v-model="createOpen" job-type="BATCH" :projects="ownProjects" :default-project-id="query.projectId" />
  <CopyJobDialog v-model="copyOpen" :job="copying" />
  <DeleteJobDialog v-model="deleteDialog.visible" :job-name="deleteDialog.job?.name ?? ''" @confirm="confirmRemove" />
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


.name {
  font-weight: 500;
}

.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
}

.sub.inline {
  margin-left: 6px;
  white-space: nowrap;
}

.mono {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}


.scope {
  flex: none;
}
</style>

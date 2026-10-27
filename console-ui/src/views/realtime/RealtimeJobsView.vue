<script setup lang="ts">
/** Real-time (CDC) jobs (design.md §12 P2): their live state, refreshed while the page is open. */
import { CircleCheck, Plus } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { jobApi, projectApi, streamApi, type JobView, type ProjectView, type StreamSummary } from '@/api/console'
import CdcCheckDialog from '@/components/CdcCheckDialog.vue'
import CopyJobDialog from '@/components/CopyJobDialog.vue'
import CreateJobDialog from '@/components/CreateJobDialog.vue'
import PageHeader from '@/components/PageHeader.vue'
import StreamDeleteDialog from '@/components/StreamDeleteDialog.vue'
import { useQueryState } from '@/composables/useQueryState'
import { formatTime } from '@/utils/format'
import { ago, streamDisplay, streamStateTag, streamStateText } from '@/utils/stream'

const router = useRouter()
const rows = ref<JobView[]>([])
const total = ref(0)
const summaries = ref<Record<string, StreamSummary>>({})
const projects = ref<ProjectView[]>([])
const ownProjects = computed(() => projects.value.filter((p) => p.editable))
const { state: query, apply } = useQueryState({ projectId: '', keyword: '', mine: true, page: 1, size: 20 }, () => load())
const loading = ref(false)
const createOpen = ref(false)
const checkOpen = ref(false)
const copying = ref<JobView | null>(null)
const copyOpen = computed({ get: () => copying.value !== null, set: (open) => { if (!open) copying.value = null } })
const deleting = reactive({ visible: false, job: null as JobView | null })

async function load() {
  loading.value = true
  try {
    const result = await jobApi.list({ ...query, jobType: 'STREAMING' })
    rows.value = result.items
    total.value = result.total
    await loadSummaries()
  } finally {
    loading.value = false
  }
}

async function loadSummaries() {
  summaries.value = rows.value.length ? await streamApi.summaries(rows.value.map((r) => r.id)) : {}
}

function openCreate() {
  if (ownProjects.value.length === 0) {
    ElMessage.warning('你还没有自己的项目，请先在「项目」中新建一个')
    return
  }
  createOpen.value = true
}

function remove(row: JobView) {
  if (summaries.value[row.id] && summaries.value[row.id]!.state !== 'STOPPED') {
    ElMessage.warning('任务正在运行，先停止再删除')
    return
  }
  deleting.job = row
  deleting.visible = true
}

let timer: number | undefined
onMounted(() => {
  projectApi.list({ size: 200 }).then((r) => (projects.value = r.items))
  load()
  timer = window.setInterval(() => loadSummaries().catch(() => undefined), 10_000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="实时同步" description="持续读取 MySQL binlog / PostgreSQL WAL，把插入、更新、删除实时写到目标端。点击任务查看运行状态和启停历史。">
      <el-button :icon="CircleCheck" @click="checkOpen = true">检查同步条件</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">新建任务</el-button>
    </PageHeader>
    <div class="toolbar">
      <el-radio-group v-model="query.mine" @change="apply()">
        <el-radio-button :value="true">我的</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>
      <el-select v-model="query.projectId" placeholder="全部项目" clearable class="project" @change="apply()">
        <el-option v-for="p in projects" :key="p.id" :label="p.name" :value="p.id" />
      </el-select>
      <el-input v-model="query.keyword" placeholder="搜索任务名称" clearable class="search" @change="apply()" />
    </div>
    <el-table v-loading="loading" :data="rows" class="clickable" @row-click="(row: JobView) => router.push(`/realtime/${row.id}`)">
      <el-table-column label="名称" min-width="180" show-overflow-tooltip>
        <template #default="{ row }">
          <span class="name">{{ row.name }}</span>
          <div v-if="row.description" class="sub">{{ row.description }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="projectName" label="项目" min-width="120" show-overflow-tooltip />
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <template v-if="summaries[row.id]">
            <el-tooltip
              :disabled="streamDisplay(summaries[row.id]!) !== 'FAILED'"
              :content="summaries[row.id]!.lastRunMessage ?? ''"
              placement="top"
              popper-class="wide-tip"
            >
              <el-tag :type="streamStateTag[streamDisplay(summaries[row.id]!)]">{{ streamStateText[streamDisplay(summaries[row.id]!)] }}</el-tag>
            </el-tooltip>
            <el-tooltip v-if="summaries[row.id]!.checkpointProblem" :content="summaries[row.id]!.checkpointProblem!" placement="top">
              <el-tag type="warning" class="gap">checkpoint 异常</el-tag>
            </el-tooltip>
          </template>
        </template>
      </el-table-column>
      <el-table-column label="最近读到数据" width="130">
        <template #default="{ row }">{{ summaries[row.id]?.state === 'STOPPED' ? '-' : ago(summaries[row.id]?.lastReadAt) }}</template>
      </el-table-column>
      <el-table-column label="启动时间" width="170">
        <template #default="{ row }">{{ summaries[row.id]?.startedAt ? formatTime(summaries[row.id]!.startedAt) : '-' }}</template>
      </el-table-column>
      <el-table-column label="当前版本" width="90">
        <template #default="{ row }">{{ row.currentVersionNo ? `v${row.currentVersionNo}` : '未保存' }}</template>
      </el-table-column>
      <el-table-column label="创建人" width="110">
        <template #default="{ row }">{{ row.ownerName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="190" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click.stop="router.push(`/realtime/${row.id}`)">详情</el-button>
          <el-button link type="primary" :disabled="!row.editable" @click.stop="router.push(`/realtime/${row.id}/edit`)">编辑</el-button>
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

  <CreateJobDialog v-model="createOpen" job-type="STREAMING" :projects="ownProjects" :default-project-id="query.projectId" />
  <CopyJobDialog v-model="copyOpen" :job="copying" />
  <CdcCheckDialog v-model="checkOpen" />
  <StreamDeleteDialog v-model="deleting.visible" :job-id="deleting.job?.id ?? null" :job-name="deleting.job?.name ?? ''" @deleted="load" />
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
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

.gap {
  margin-left: 6px;
}
</style>

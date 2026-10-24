<script setup lang="ts">
/**
 * A job as it runs (docs/design.md §10.4): overview with monitoring, runs, backfills and versions.
 * Nothing is edited here; "编辑" opens JobEditView.
 */
import { ArrowDown, Edit } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { jobApi, type JobView } from '@/api/console'
import BackButton from '@/components/BackButton.vue'
import { listUrl, setBreadcrumb } from '@/composables/useBreadcrumb'
import { formatTime, statusTag, statusText } from '@/utils/format'
import { textTooltip } from '@/utils/tooltip'
import CopyJobDialog from '@/components/CopyJobDialog.vue'
import DeleteJobDialog from '@/components/DeleteJobDialog.vue'

import BackfillDialog from './detail/BackfillDialog.vue'
import JobBackfillsPane from './detail/JobBackfillsPane.vue'
import JobOverviewPane from './detail/JobOverviewPane.vue'
import JobRunsPane from './detail/JobRunsPane.vue'
import JobVersionsPane from './detail/JobVersionsPane.vue'
import RunDialog from './detail/RunDialog.vue'

const route = useRoute()
const router = useRouter()
const copyOpen = ref(false)
const deleteOpen = ref(false)
const jobId = route.params.id as string

const job = ref<JobView | null>(null)
const NOT_EDITABLE = '只有创建人或管理员可以修改和运行这个任务'

/** The tab is the last path segment (the overview has none), so refresh and links keep it. */
const tab = ref((route.params.tab as string) || 'overview')
watch(tab, (name) => {
  router.replace(name === 'overview' ? `/jobs/${jobId}` : `/jobs/${jobId}/${name}`)
})

const runDialog = ref(false)
const backfillDialog = ref(false)
const overviewPane = ref<InstanceType<typeof JobOverviewPane> | null>(null)
const backfillsPane = ref<InstanceType<typeof JobBackfillsPane> | null>(null)

const editTip = computed(() => (job.value?.editable ? '' : NOT_EDITABLE))
const runTip = computed(() => (!job.value?.editable ? NOT_EDITABLE : !job.value.currentVersionNo ? '先编辑并保存一个版本' : ''))

async function load() {
  job.value = (await jobApi.get(jobId)).job
  setBreadcrumb([{ title: '任务', to: listUrl('/jobs') }, { title: job.value.name }])
}

function onBackfillCreated() {
  tab.value = 'backfills'
  backfillsPane.value?.load()
}

async function onRestored() {
  await load()
  overviewPane.value?.load()
}

function remove() {
  deleteOpen.value = true
}

async function confirmRemove(deleteRuns: boolean) {
  await jobApi.remove(jobId, deleteRuns)
  ElMessage.success('已删除')
  router.push('/jobs')
}

onMounted(load)
</script>

<template>
  <el-card v-if="job" shadow="never">
    <div class="head">
      <div class="title">
        <div class="name">
          <BackButton :fallback="listUrl('/jobs')" />
          {{ job.name }}
          <el-tag v-if="job.activity?.lastRunStatus" :type="statusTag[job.activity.lastRunStatus]" class="status">
            最近运行：{{ statusText[job.activity.lastRunStatus] }}
          </el-tag>
        </div>
        <div class="meta">
          <router-link :to="{ path: '/jobs', query: { projectId: job.projectId, mine: 'false' } }">{{ job.projectName }}</router-link>
          · 离线任务 · {{ job.editMode === 'FORM' ? '向导配置' : '脚本配置' }}
          · 当前版本 {{ job.currentVersionNo ? `v${job.currentVersionNo}` : '未保存' }}
          · 创建人 {{ job.ownerName ?? '-' }} · 更新于 {{ formatTime(job.updatedAt) }}
        </div>
        <div v-if="job.description" class="description">{{ job.description }}</div>
      </div>
      <div class="actions">
        <el-tooltip :content="editTip" :disabled="!editTip" placement="bottom" v-bind="textTooltip">
          <span>
            <el-button :icon="Edit" :disabled="!job.editable" @click="router.push(`/jobs/${jobId}/edit`)">编辑</el-button>
          </span>
        </el-tooltip>
        <el-tooltip :content="runTip" :disabled="!runTip" placement="bottom" v-bind="textTooltip">
          <span class="buttons">
            <el-button :disabled="!!runTip" @click="backfillDialog = true">补数</el-button>
            <el-button type="primary" :disabled="!!runTip" @click="runDialog = true">立即运行</el-button>
          </span>
        </el-tooltip>
        <el-dropdown trigger="click">
          <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="copyOpen = true">复制任务</el-dropdown-item>
              <el-dropdown-item class="danger-item" :disabled="!job.editable" divided @click="remove">删除任务</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="概览" name="overview" lazy>
        <JobOverviewPane ref="overviewPane" :job="job" @show-runs="tab = 'runs'" />
      </el-tab-pane>
      <el-tab-pane label="运行记录" name="runs" lazy>
        <JobRunsPane :job-id="jobId" />
      </el-tab-pane>
      <el-tab-pane label="补数" name="backfills" lazy>
        <JobBackfillsPane ref="backfillsPane" :job-id="jobId" :readonly="!job.editable" />
      </el-tab-pane>
      <el-tab-pane label="版本" name="versions" lazy>
        <JobVersionsPane :job="job" @restored="onRestored" />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <RunDialog v-model="runDialog" :job-id="jobId" />
  <BackfillDialog v-model="backfillDialog" :job-id="jobId" :version-no="job?.currentVersionNo ?? null" @created="onBackfillCreated" />
  <CopyJobDialog v-model="copyOpen" :job="job" />
  <DeleteJobDialog v-model="deleteOpen" :job-name="job?.name ?? ''" @confirm="confirmRemove" />
</template>

<style scoped>
.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.title {
  min-width: 0;
}

.name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: -6px;
  font-size: 20px;
  font-weight: 600;
}

.status {
  font-weight: normal;
}

.meta,
.description {
  padding-left: 30px;
}

.meta {
  margin-top: 6px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.meta a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.description {
  margin-top: 6px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.buttons {
  display: inline-flex;
  gap: 12px;
}

.buttons .el-button + .el-button {
  margin-left: 0;
}

/* scoped, not :deep - the dropdown menu is teleported out of this component */
.danger-item {
  color: var(--el-color-danger);
}
</style>

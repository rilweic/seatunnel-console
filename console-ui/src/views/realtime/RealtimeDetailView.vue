<script setup lang="ts">
/**
 * A real-time job (design.md §12 P2, 详情页): start and stop it, see how it runs, its starts and
 * versions. Nothing is edited here; "编辑" opens the editor shared with offline jobs.
 */
import { ArrowDown, Edit, VideoPause, VideoPlay } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { jobApi, streamApi, type JobView, type StreamView } from '@/api/console'
import BackButton from '@/components/BackButton.vue'
import CopyJobDialog from '@/components/CopyJobDialog.vue'
import StreamDeleteDialog from '@/components/StreamDeleteDialog.vue'
import { listUrl, setBreadcrumb } from '@/composables/useBreadcrumb'
import { formatTime } from '@/utils/format'
import { streamDisplay, streamStateTag, streamStateText } from '@/utils/stream'
import { textTooltip } from '@/utils/tooltip'
import JobVersionsPane from '@/views/jobs/detail/JobVersionsPane.vue'

import StartStreamDialog from './StartStreamDialog.vue'
import StreamHistoryPane from './StreamHistoryPane.vue'
import StreamOverviewPane from './StreamOverviewPane.vue'

const route = useRoute()
const router = useRouter()
const jobId = route.params.id as string
const NOT_EDITABLE = '只有创建人或管理员可以修改和启停这个任务'

const job = ref<JobView | null>(null)
const view = ref<StreamView | null>(null)
const startOpen = ref(false)
const copyOpen = ref(false)
const deleteOpen = ref(false)
const stopping = ref(false)
const historyPane = ref<InstanceType<typeof StreamHistoryPane> | null>(null)

const tab = ref((route.params.tab as string) || 'overview')
watch(tab, (name) => router.replace(name === 'overview' ? `/realtime/${jobId}` : `/realtime/${jobId}/${name}`))

const editTip = computed(() => (job.value?.editable ? '' : NOT_EDITABLE))
const startTip = computed(() => (!job.value?.editable ? NOT_EDITABLE : !job.value.currentVersionNo ? '先编辑并保存一个版本' : ''))
const running = computed(() => !!view.value && view.value.state !== 'STOPPED')

async function load() {
  job.value = (await jobApi.get(jobId)).job
  setBreadcrumb([{ title: '实时同步', to: listUrl('/realtime') }, { title: job.value.name }])
  view.value = await streamApi.view(jobId)
}

async function refresh() {
  view.value = await streamApi.view(jobId)
}

async function stop(force: boolean) {
  if (force) {
    await ElMessageBox.confirm('强制停止不保存 savepoint，下次启动从最近一次 checkpoint 恢复，之后的变更会再同步一遍。确定强制停止？', '强制停止', {
      type: 'warning',
      confirmButtonText: '强制停止',
    })
  }
  stopping.value = true
  try {
    view.value = await streamApi.stop(jobId, force)
    ElMessage.success(force ? '已强制停止' : '正在停止，保存 savepoint 后结束')
  } finally {
    stopping.value = false
  }
}

function onStarted(started: StreamView) {
  view.value = started
  historyPane.value?.load()
}

let timer: number | undefined
onMounted(async () => {
  await load()
  timer = window.setInterval(() => refresh().catch(() => undefined), 10_000)
})
onBeforeUnmount(() => window.clearInterval(timer))
</script>

<template>
  <el-card v-if="job && view" shadow="never">
    <div class="detail-context"><strong>实时任务详情</strong></div>
    <div class="head">
      <div class="title">
        <div class="name">
          <BackButton :fallback="listUrl('/realtime')" />
          {{ job.name }}
          <el-tag :type="streamStateTag[streamDisplay(view)]" class="status">{{ streamStateText[streamDisplay(view)] }}</el-tag>
        </div>
        <div class="meta">
          <router-link :to="{ path: '/realtime', query: { projectId: job.projectId, mine: 'false' } }">{{ job.projectName }}</router-link>
          · 实时任务 · {{ job.editMode === 'FORM' ? '向导配置' : '脚本配置' }}
          · 当前版本 {{ job.currentVersionNo ? `v${job.currentVersionNo}` : '未保存' }}
          · 创建人 {{ job.ownerName ?? '-' }} · 更新于 {{ formatTime(job.updatedAt) }}
        </div>
        <div v-if="job.description" class="description">{{ job.description }}</div>
      </div>
      <div class="actions">
        <el-tooltip :content="editTip" :disabled="!editTip" placement="bottom" v-bind="textTooltip">
          <span><el-button :icon="Edit" :disabled="!job.editable" @click="router.push(`/realtime/${jobId}/edit`)">编辑</el-button></span>
        </el-tooltip>
        <el-tooltip v-if="!running" :content="startTip" :disabled="!startTip" placement="bottom" v-bind="textTooltip">
          <span><el-button type="primary" :icon="VideoPlay" :disabled="!!startTip" @click="startOpen = true">启动</el-button></span>
        </el-tooltip>
        <template v-else>
          <el-button v-if="view.state !== 'STOPPING'" :icon="VideoPause" :loading="stopping" :disabled="!job.editable" @click="stop(false)">停止</el-button>
          <el-button type="danger" plain :loading="stopping" :disabled="!job.editable" @click="stop(true)">强制停止</el-button>
        </template>
        <el-dropdown trigger="click">
          <el-button>更多<el-icon class="el-icon--right"><ArrowDown /></el-icon></el-button>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item @click="copyOpen = true">复制任务</el-dropdown-item>
              <el-dropdown-item class="danger-item" :disabled="!job.editable || running" divided @click="deleteOpen = true">删除任务</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>

    <el-tabs v-model="tab">
      <el-tab-pane label="概览" name="overview" lazy>
        <StreamOverviewPane :job="job" :view="view" />
      </el-tab-pane>
      <el-tab-pane label="启停历史" name="history" lazy>
        <StreamHistoryPane ref="historyPane" :job-id="jobId" />
      </el-tab-pane>
      <el-tab-pane label="版本" name="versions" lazy>
        <JobVersionsPane :job="job" @restored="load" />
      </el-tab-pane>
    </el-tabs>
  </el-card>

  <StartStreamDialog v-if="view" v-model="startOpen" :view="view" @started="onStarted" />
  <CopyJobDialog v-model="copyOpen" :job="job" />
  <StreamDeleteDialog v-model="deleteOpen" :job-id="jobId" :job-name="job?.name ?? ''" @deleted="router.push('/realtime')" />
</template>

<style scoped>
.detail-context {
  margin-bottom: 16px;
  font-size: 14px;
}

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
}

.actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: none;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

@media (max-width: 900px) {
  .head {
    flex-direction: column;
  }

  .actions {
    flex-wrap: wrap;
  }
}

.danger-item {
  color: var(--el-color-danger);
}
</style>

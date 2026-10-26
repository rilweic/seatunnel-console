<script setup lang="ts">
/**
 * Editing a job: sync config (wizard or script, saved as a new version), schedule and basic info.
 * Each section saves on its own; leaving with unsaved changes asks first.
 */
import { Document } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'

import { jobApi, type JobView, type VersionDetail } from '@/api/console'
import BackButton from '@/components/BackButton.vue'
import JobWizard from '@/components/JobWizard.vue'
import type { ParamRow } from '@/components/ParamsEditor.vue'
import { listUrl, setBreadcrumb } from '@/composables/useBreadcrumb'
import { toRows } from '@/utils/params'

import BasicInfo from './edit/BasicInfo.vue'
import ScheduleConfig from './edit/ScheduleConfig.vue'
import ScriptConfig from './edit/ScriptConfig.vue'
import StreamRunSettings from '../realtime/StreamRunSettings.vue'

const route = useRoute()
const router = useRouter()
const jobId = route.params.id as string

const job = ref<JobView | null>(null)
const version = ref<VersionDetail | null>(null)
const isWizard = computed(() => job.value?.editMode === 'FORM')
/** Real-time jobs live under /realtime and have run settings instead of a schedule (design.md §12 P2). */
const streaming = computed(() => route.path.startsWith('/realtime/'))
const base = computed(() => (streaming.value ? `/realtime/${jobId}` : `/jobs/${jobId}`))
const wizardParams = ref<ParamRow[]>([])
/** Remounts the config editor after a save or a mode switch, so it starts from what was saved. */
const configKey = ref(0)

/** The section is the last path segment (config has none); the wizard step is ?step=, 1-based. */
const section = ref((route.params.section as string) || 'config')
const wizardStep = ref(Math.max(0, Math.min(4, Number(route.query.step) - 1 || 0)))
watch([section, wizardStep], () => {
  const path = section.value === 'config' ? `${base.value}/edit` : `${base.value}/edit/${section.value}`
  const query = section.value === 'config' && isWizard.value && wizardStep.value > 0 ? { step: String(wizardStep.value + 1) } : {}
  router.replace({ path, query })
})

const dirty = reactive({ config: false, schedule: false, basic: false })
const anyDirty = computed(() => dirty.config || dirty.schedule || dirty.basic)
const dirtyNames = computed(() =>
  [dirty.config && '同步配置', dirty.schedule && (streaming.value ? '运行设置' : '调度'), dirty.basic && '基本信息'].filter(Boolean).join('、'),
)

async function load() {
  const detail = await jobApi.get(jobId)
  if (!detail.job.editable) {
    ElMessage.warning('只有创建人或管理员可以编辑这个任务')
    router.replace(base.value)
    return
  }
  if ((detail.job.jobType === 'STREAMING') !== streaming.value) {
    // opened under the other menu's path, e.g. an old link
    router.replace(`${detail.job.jobType === 'STREAMING' ? '/realtime' : '/jobs'}/${jobId}/edit`)
    return
  }
  job.value = detail.job
  version.value = detail.currentVersion
  crumbs()
  wizardParams.value = toRows(detail.currentVersion?.params)
}

async function onConfigSaved() {
  dirty.config = false
  await load()
  configKey.value++
}

function crumbs() {
  setBreadcrumb([
    streaming.value ? { title: '实时同步', to: listUrl('/realtime') } : { title: '离线同步', to: listUrl('/jobs') },
    { title: job.value!.name, to: base.value },
    { title: '编辑' },
  ])
}

function onBasicSaved(updated: JobView) {
  job.value = updated
  crumbs()
}

async function switchToScript() {
  try {
    await ElMessageBox.confirm(
      dirty.config
        ? '向导里还有未保存的修改，转换会丢弃它们，只带走当前已保存版本生成的配置。转换不可逆，不能再回到向导。'
        : '转换后用脚本编辑器直接修改 HOCON，当前版本生成的配置会原样带过去。转换不可逆，不能再回到向导。',
      '转为脚本模式',
      { type: 'warning', confirmButtonText: '转换', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  await jobApi.switchToScript(jobId)
  dirty.config = false
  await load()
  configKey.value++
  ElMessage.success('已转为脚本模式')
}

async function confirmLeave(): Promise<boolean> {
  if (!anyDirty.value) return true
  try {
    await ElMessageBox.confirm(`${dirtyNames.value}有未保存的修改，离开后会丢失。`, '离开编辑页', {
      type: 'warning',
      confirmButtonText: '离开',
      cancelButtonText: '继续编辑',
    })
    return true
  } catch {
    return false
  }
}

onBeforeRouteLeave(confirmLeave)

function beforeUnload(e: BeforeUnloadEvent) {
  if (anyDirty.value) e.preventDefault()
}
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))

onMounted(load)
</script>

<template>
  <el-card v-if="job" shadow="never" class="edit-card">
    <div class="head">
      <div>
        <div class="name"><BackButton :fallback="base" />编辑{{ streaming ? '实时' : '' }}任务：{{ job.name }}</div>
        <div class="meta">
          {{ job.projectName }} · {{ isWizard ? '向导配置' : '脚本配置' }} · 当前版本 {{ job.currentVersionNo ? `v${job.currentVersionNo}` : '未保存' }}
          · 同步配置每次保存生成一个新版本，{{ streaming ? '运行设置' : '调度' }}和基本信息单独保存
          <template v-if="streaming">；新版本在任务重启后生效</template>
        </div>
      </div>
      <div class="actions">
        <el-button v-if="isWizard" :icon="Document" @click="switchToScript">转为脚本模式</el-button>
      </div>
    </div>

    <el-tabs v-model="section">
      <el-tab-pane name="config">
        <template #label>同步配置<span v-if="dirty.config" class="dot" /></template>
        <JobWizard
          v-if="isWizard"
          :key="'w' + configKey"
          v-model:step="wizardStep"
          v-model:params="wizardParams"
          :job-id="jobId"
          :owner-id="job.ownerId"
          :readonly="false"
          :initial-form="version?.form ?? null"
          :streaming="streaming"
          @saved="onConfigSaved"
          @dirty="(d: boolean) => (dirty.config = d)"
        />
        <ScriptConfig
          v-else
          :key="'s' + configKey"
          :job="job"
          :version="version"
          @saved="onConfigSaved"
          @dirty="(d: boolean) => (dirty.config = d)"
        />
      </el-tab-pane>
      <el-tab-pane v-if="streaming" name="settings">
        <template #label>运行设置<span v-if="dirty.schedule" class="dot" /></template>
        <StreamRunSettings :job="job" @dirty="(d: boolean) => (dirty.schedule = d)" />
      </el-tab-pane>
      <el-tab-pane v-else name="schedule">
        <template #label>调度<span v-if="dirty.schedule" class="dot" /></template>
        <ScheduleConfig :job="job" @dirty="(d: boolean) => (dirty.schedule = d)" />
      </el-tab-pane>
      <el-tab-pane name="basic">
        <template #label>基本信息<span v-if="dirty.basic" class="dot" /></template>
        <BasicInfo :job="job" @saved="onBasicSaved" @dirty="(d: boolean) => (dirty.basic = d)" />
      </el-tab-pane>
    </el-tabs>
  </el-card>
</template>

<style scoped>
/* nothing between the page and the wizard clips, so its bottom bar can stick to the page bottom */
.edit-card,
.edit-card :deep(.el-card__body),
.edit-card :deep(.el-tabs__content) {
  overflow: visible;
}

.head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 8px;
}

.name {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-left: -6px;
  font-size: 20px;
  font-weight: 600;
}

.meta {
  margin-top: 6px;
  padding-left: 30px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.actions {
  flex: none;
}

/* marks a section with unsaved changes */
.dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: var(--el-color-warning);
  vertical-align: middle;
}
</style>

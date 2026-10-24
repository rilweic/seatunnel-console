<script setup lang="ts">
/** Saved versions: view, compare and roll back (a rollback saves the old version as a new one). */
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, ref } from 'vue'

import { datasourceApi, jobApi, type DatasourceView, type JobView, type VersionDetail, type VersionSummary } from '@/api/console'
import VersionDiff from '@/components/VersionDiff.vue'
import { formatTime } from '@/utils/format'

const props = defineProps<{ job: JobView }>()
const emit = defineEmits<{ restored: [] }>()

const versions = ref<VersionSummary[]>([])
const loading = ref(false)
const datasources = ref<DatasourceView[]>([])
const datasourceName = computed(() => new Map(datasources.value.map((d) => [d.id, `${d.name} (${d.type})`])))
const viewing = ref<{ visible: boolean; detail: VersionDetail | null }>({ visible: false, detail: null })
const diff = ref({ visible: false, leftId: '', rightId: '' })

async function load() {
  loading.value = versions.value.length === 0
  try {
    versions.value = await jobApi.versions(props.job.id)
  } finally {
    loading.value = false
  }
}

async function view(v: VersionSummary) {
  viewing.value = { visible: true, detail: await jobApi.version(props.job.id, v.id) }
  if (datasources.value.length === 0) {
    datasources.value = (await datasourceApi.list({ size: 200 })).items
  }
}

/** Compares a version with the current one (or the previous one, for the current version itself). */
function compare(v: VersionSummary) {
  const current = versions.value[0]!
  const other = v.id === current.id ? versions.value[1]! : current
  const [older, newer] = other.versionNo < v.versionNo ? [other, v] : [v, other]
  diff.value = { visible: true, leftId: older.id, rightId: newer.id }
}

async function rollback(v: VersionSummary) {
  try {
    await ElMessageBox.confirm(
      `用 v${v.versionNo} 的配置${props.job.editMode === 'FORM' ? '（向导表单）' : ''}和任务参数生成一个新版本，并设为当前版本。历史版本都会保留。`,
      `回滚到 v${v.versionNo}`,
      { type: 'warning', confirmButtonText: '回滚', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  const version = await jobApi.restoreVersion(props.job.id, v.id)
  ElMessage.success(`已回滚，生成 v${version.versionNo}`)
  await load()
  emit('restored')
}

const usedDatasources = computed(() =>
  (viewing.value.detail?.datasourceIds ?? []).map((id) => datasourceName.value.get(id) ?? id).join('，') || '无',
)

onMounted(load)
defineExpose({ load })
</script>

<template>
  <el-table v-loading="loading" :data="versions" empty-text="还没有保存过版本。点右上角「编辑」配置任务">
    <el-table-column label="版本" width="110">
      <template #default="{ row }">
        v{{ row.versionNo }}
        <el-tag v-if="row.versionNo === job.currentVersionNo" type="success" class="current">当前</el-tag>
      </template>
    </el-table-column>
    <el-table-column label="备注" min-width="200" show-overflow-tooltip>
      <template #default="{ row }">{{ row.remark || '-' }}</template>
    </el-table-column>
    <el-table-column label="创建人" width="120">
      <template #default="{ row }">{{ row.createdByName ?? '-' }}</template>
    </el-table-column>
    <el-table-column label="创建时间" width="170">
      <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="160" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" @click="view(row)">查看</el-button>
        <el-button link type="primary" :disabled="versions.length < 2" @click="compare(row)">对比</el-button>
        <el-button link type="primary" :disabled="row.versionNo === job.currentVersionNo || !job.editable" @click="rollback(row)">回滚</el-button>
      </template>
    </el-table-column>
  </el-table>

  <VersionDiff v-if="versions.length >= 2" v-model="diff.visible" :job-id="job.id" :versions="versions" :left-id="diff.leftId" :right-id="diff.rightId" />

  <el-dialog v-model="viewing.visible" :title="`版本 v${viewing.detail?.versionNo ?? ''}`" width="760px">
    <template v-if="viewing.detail">
      <p class="tip">
        {{ viewing.detail.form ? '向导生成的配置' : '脚本' }} · 数据源：{{ usedDatasources }}
        <template v-if="Object.keys(viewing.detail.params).length"> · 任务参数：{{ Object.entries(viewing.detail.params).map(([k, v]) => `${k}=${v}`).join('，') }}</template>
      </p>
      <pre class="code-view">{{ viewing.detail.hocon }}</pre>
    </template>
    <template #footer>
      <el-button @click="viewing.visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.current {
  margin-left: 6px;
}

.tip {
  margin: 0 0 8px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.code-view {
  margin: 0;
  padding: 12px;
  max-height: 480px;
  overflow: auto;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.5;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  white-space: pre-wrap;
}
</style>

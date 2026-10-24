<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { projectApi, type ParamItem, type ProjectView } from '@/api/console'
import { formatTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'

const router = useRouter()

function openJobs(row: ProjectView) {
  // Opened from a project (possibly someone else's), show all of its jobs, not only mine.
  router.push({ path: '/jobs', query: { projectId: row.id, mine: 'false' } })
}

const rows = ref<ProjectView[]>([])
const total = ref(0)
const { state: query, apply } = useQueryState({ keyword: '', mine: true, page: 1, size: 20 }, () => load())
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await projectApi.list(query)
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

const dialog = reactive({ visible: false, id: '', name: '', description: '', saving: false })

function openCreate() {
  Object.assign(dialog, { visible: true, id: '', name: '', description: '' })
}

function openEdit(row: ProjectView) {
  Object.assign(dialog, { visible: true, id: row.id, name: row.name, description: row.description ?? '' })
}

async function save() {
  if (!dialog.name.trim()) {
    ElMessage.warning('请填写项目名称')
    return
  }
  dialog.saving = true
  try {
    const body = { name: dialog.name, description: dialog.description || undefined }
    if (dialog.id) {
      await projectApi.update(dialog.id, body)
    } else {
      await projectApi.create(body)
    }
    dialog.visible = false
    ElMessage.success('已保存')
    load()
  } finally {
    dialog.saving = false
  }
}

async function remove(row: ProjectView) {
  try {
    await ElMessageBox.confirm(`删除项目「${row.name}」？项目下还有任务时无法删除。`, '删除项目', { type: 'warning' })
  } catch {
    return
  }
  await projectApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

const params = reactive({ visible: false, projectId: '', projectName: '', editable: false, items: [] as ParamItem[], saving: false })

async function openParams(row: ProjectView) {
  Object.assign(params, { visible: true, projectId: row.id, projectName: row.name, editable: row.editable, items: [] })
  params.items = await projectApi.params(row.id)
}

async function saveParams() {
  params.saving = true
  try {
    params.items = await projectApi.saveParams(params.projectId, params.items.filter((p) => p.key.trim()))
    params.visible = false
    ElMessage.success('参数已保存')
  } finally {
    params.saving = false
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="项目" description="按项目组织任务。项目参数可以在项目下的所有任务里用 ${名称} 引用；点项目名称查看它的任务。">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建项目</el-button>
    </PageHeader>
    <div class="toolbar">
      <el-radio-group v-model="query.mine" size="default" class="scope" @change="apply()">
        <el-radio-button :value="true">我的</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>
      <el-input v-model="query.keyword" placeholder="搜索项目名称" clearable class="search" @change="apply()" />
    </div>
    <el-table v-loading="loading" :data="rows">
      <el-table-column label="名称" min-width="160">
        <template #default="{ row }">
          <el-link type="primary" underline="never" @click="openJobs(row)">{{ row.name }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="描述" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">{{ row.description || '-' }}</template>
      </el-table-column>
      <el-table-column label="任务数" width="90">
        <template #default="{ row }">
          <el-link type="primary" underline="never" @click="openJobs(row)">{{ row.jobCount }}</el-link>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="170">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="创建人" width="120">
        <template #default="{ row }">{{ row.ownerName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openJobs(row)">任务</el-button>
          <el-button link type="primary" :disabled="!row.editable" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openParams(row)">项目参数</el-button>
          <el-button link type="danger" :disabled="!row.editable" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      @current-change="apply(false)"
    />
  </el-card>

  <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑项目' : '新建项目'" width="480px">
    <el-form label-width="80px">
      <el-form-item label="名称" required>
        <el-input v-model="dialog.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="描述">
        <el-input v-model="dialog.description" type="textarea" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="params.visible" :title="`项目参数 · ${params.projectName}`" width="640px">
    <p class="hint">项目下所有任务都能用 <code>${名称}</code> 引用这些参数，优先级低于任务参数和启动参数。值里可以使用内置变量和时间表达式，如 <code>${system.biz.date}</code>、<code>$[yyyyMMdd-1]</code>。名称只能包含字母、数字、_ 和 .，不能以 system. 开头。</p>
    <el-table :data="params.items">
      <el-table-column label="参数名" min-width="160">
        <template #default="{ row }"><el-input v-model="row.key" /></template>
      </el-table-column>
      <el-table-column label="值" min-width="200">
        <template #default="{ row }"><el-input v-model="row.value" /></template>
      </el-table-column>
      <el-table-column width="70">
        <template #default="{ $index }">
          <el-button link type="danger" @click="params.items.splice($index, 1)">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-button class="add-param" @click="params.items.push({ key: '', value: '' })">添加参数</el-button>
    <template #footer>
      <el-button @click="params.visible = false">取消</el-button>
      <el-button type="primary" :loading="params.saving" :disabled="!params.editable" @click="saveParams">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.search {
  width: 240px;
}


.hint {
  margin: 0 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.add-param {
  margin-top: 12px;
}
.scope {
  flex: none;
}
</style>

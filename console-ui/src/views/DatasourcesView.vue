<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { datasourceApi, type DatasourceView } from '@/api/console'
import DatasourceTypeIcon from '@/components/DatasourceTypeIcon.vue'
import { datasourceForms } from '@/components/datasource/forms'
import { useDatasourceTypes } from '@/composables/useDatasourceTypes'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'

const { types, label: typeLabel } = useDatasourceTypes()
/** The datasource whose real-time (CDC) conditions are being checked. */
/** Types the server supports and this page has a form for, in the server's order. */
const creatableTypes = computed(() => types.value.filter((t) => datasourceForms[t.type]))

const rows = ref<DatasourceView[]>([])
const total = ref(0)
const store = useUserStore()
const { state: query, apply } = useQueryState({ keyword: '', type: '', mine: true, page: 1, size: 20 }, () => load())
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const result = await datasourceApi.list({ ...query, ownerId: query.mine ? store.user?.id : undefined })
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
}

const dialog = reactive({
  visible: false,
  id: '',
  name: '',
  type: '',
  description: '',
  props: {} as Record<string, string>,
  secretsSet: [] as string[],
  saving: false,
  testing: false,
})

const form = computed(() => datasourceForms[dialog.type])

function initialProps(type: string) {
  return datasourceForms[type]?.initialProps?.() ?? {}
}

function openCreate() {
  const type = creatableTypes.value[0]?.type ?? ''
  Object.assign(dialog, { visible: true, id: '', name: '', type, description: '', props: initialProps(type), secretsSet: [] })
}

function openEdit(row: DatasourceView) {
  Object.assign(dialog, {
    visible: true,
    id: row.id,
    name: row.name,
    type: row.type,
    description: row.description ?? '',
    props: { ...initialProps(row.type), ...row.config },
    secretsSet: row.secretsSet,
  })
}

function secretPlaceholder(key: string) {
  return dialog.id && dialog.secretsSet.includes(key) ? '已保存，留空表示不修改' : ''
}

async function testConnection() {
  dialog.testing = true
  try {
    const result = await datasourceApi.test({
      datasourceId: dialog.id || undefined,
      type: dialog.type,
      props: dialog.props,
    })
    if (result.success) {
      ElMessage.success(`连接成功（${result.elapsedMillis} ms）`)
    } else {
      ElMessage.error({ message: `连接失败：${result.message}`, duration: 8000 })
    }
  } finally {
    dialog.testing = false
  }
}

async function save() {
  if (!dialog.name.trim()) {
    ElMessage.warning('请填写数据源名称')
    return
  }
  dialog.saving = true
  try {
    const body = { name: dialog.name, description: dialog.description || undefined, props: dialog.props }
    if (dialog.id) {
      await datasourceApi.update(dialog.id, body)
    } else {
      await datasourceApi.create({ ...body, type: dialog.type })
    }
    dialog.visible = false
    ElMessage.success('已保存')
    load()
  } finally {
    dialog.saving = false
  }
}

async function remove(row: DatasourceView) {
  try {
    await ElMessageBox.confirm(`删除数据源「${row.name}」？仍被任务使用时无法删除。`, '删除数据源', { type: 'warning' })
  } catch {
    return
  }
  await datasourceApi.remove(row.id)
  ElMessage.success('已删除')
  load()
}

async function copyId(row: DatasourceView) {
  try {
    await navigator.clipboard.writeText(row.id)
    ElMessage.success('已复制数据源 ID')
  } catch {
    ElMessage.info(row.id)
  }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="数据源" description="配置同步任务读取和写入数据时使用的连接信息。">
      <el-button type="primary" :icon="Plus" @click="openCreate">新建数据源</el-button>
    </PageHeader>
    <div class="toolbar">
      <div class="filters">
        <el-radio-group v-model="query.mine" class="scope" @change="apply()">
        <el-radio-button :value="true">我的</el-radio-button>
        <el-radio-button :value="false">全部</el-radio-button>
      </el-radio-group>
        <el-input v-model="query.keyword" placeholder="搜索名称" clearable class="search" @change="apply()" />
        <el-select v-model="query.type" placeholder="全部类型" clearable class="type" @change="apply()">
          <el-option v-for="t in types" :key="t.type" :label="t.capabilities.label" :value="t.type">
            <span class="type-option"><DatasourceTypeIcon :type="t.type" />{{ t.capabilities.label }}</span>
          </el-option>
        </el-select>
      </div>
    </div>
    <el-table v-loading="loading" :data="rows">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="160">
        <template #default="{ row }">
          <span class="type-option"><DatasourceTypeIcon :type="row.type" />{{ typeLabel(row.type) }}</span>
        </template>
      </el-table-column>
      <el-table-column label="地址" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ row.address }}</template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="创建人" width="120">
        <template #default="{ row }">{{ row.ownerName ?? '-' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!row.editable" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="copyId(row)">复制 ID</el-button>
          <el-button link type="danger" :disabled="!row.editable" @click="remove(row)">删除</el-button>
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

  <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑数据源' : '新建数据源'" width="560px">
    <el-form label-width="90px">
      <el-form-item label="名称" required>
        <el-input v-model="dialog.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-select
          v-model="dialog.type"
          class="type-select"
          filterable
          :disabled="!!dialog.id"
          @change="dialog.props = initialProps(dialog.type)"
        >
          <template #prefix><DatasourceTypeIcon v-if="dialog.type" :type="dialog.type" /></template>
          <el-option v-for="t in creatableTypes" :key="t.type" :label="t.capabilities.label" :value="t.type">
            <span class="type-option"><DatasourceTypeIcon :type="t.type" />{{ t.capabilities.label }}</span>
          </el-option>
        </el-select>
      </el-form-item>
      <component
        :is="form.component"
        v-if="form"
        v-bind="form.options"
        :values="dialog.props"
        :secret-placeholder="secretPlaceholder"
      />
      <el-form-item label="描述">
        <el-input v-model="dialog.description" type="textarea" maxlength="512" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :loading="dialog.testing" @click="testConnection">测试连接</el-button>
      <el-button @click="dialog.visible = false">取消</el-button>
      <el-button type="primary" :loading="dialog.saving" @click="save">保存</el-button>
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

.search {
  width: 240px;
}

.type {
  width: 160px;
}

.scope {
  flex: none;
}

.type-option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.type-select {
  width: 100%;
}
</style>

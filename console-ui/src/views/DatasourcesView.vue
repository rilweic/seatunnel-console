<script setup lang="ts">
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { datasourceApi, type DatasourceType, type DatasourceView } from '@/api/console'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/format'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'

interface FieldDef {
  key: string
  label: string
  required?: boolean
  secret?: boolean
  placeholder?: string
}

/** Must match the properties read by the plugins in console-datasource. */
const fieldsByType: Record<DatasourceType, FieldDef[]> = {
  MYSQL: [
    { key: 'host', label: '主机', required: true },
    { key: 'port', label: '端口', placeholder: '3306' },
    { key: 'database', label: '数据库', placeholder: '可选' },
    { key: 'username', label: '用户名', required: true },
    { key: 'password', label: '密码', secret: true },
    { key: 'params', label: '连接参数', placeholder: '如 useSSL=false&serverTimezone=Asia/Shanghai' },
  ],
  POSTGRESQL: [
    { key: 'host', label: '主机', required: true },
    { key: 'port', label: '端口', placeholder: '5432' },
    { key: 'database', label: '数据库', required: true },
    { key: 'username', label: '用户名', required: true },
    { key: 'password', label: '密码', secret: true },
    { key: 'params', label: '连接参数', placeholder: '如 sslmode=disable' },
  ],
  ELASTICSEARCH: [
    { key: 'hosts', label: '地址', required: true, placeholder: 'http://es1:9200,http://es2:9200' },
    { key: 'username', label: '用户名', placeholder: '可选' },
    { key: 'password', label: '密码', secret: true },
  ],
}

const typeLabels: Record<DatasourceType, string> = {
  MYSQL: 'MySQL',
  POSTGRESQL: 'PostgreSQL',
  ELASTICSEARCH: 'Elasticsearch',
}

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
  type: 'MYSQL' as DatasourceType,
  description: '',
  props: {} as Record<string, string>,
  secretsSet: [] as string[],
  saving: false,
  testing: false,
})

const fields = computed(() => fieldsByType[dialog.type])

function openCreate() {
  Object.assign(dialog, { visible: true, id: '', name: '', type: 'MYSQL', description: '', props: {}, secretsSet: [] })
}

function openEdit(row: DatasourceView) {
  Object.assign(dialog, {
    visible: true,
    id: row.id,
    name: row.name,
    type: row.type,
    description: row.description ?? '',
    props: { ...row.config },
    secretsSet: row.secretsSet,
  })
}

function secretPlaceholder(field: FieldDef) {
  return dialog.id && dialog.secretsSet.includes(field.key) ? '已保存，留空表示不修改' : ''
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
  await ElMessageBox.confirm(`删除数据源「${row.name}」？仍被任务使用时无法删除。`, '删除数据源', { type: 'warning' })
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

function summary(row: DatasourceView) {
  const c = row.config
  return row.type === 'ELASTICSEARCH' ? c.hosts : `${c.host}:${c.port || (row.type === 'MYSQL' ? 3306 : 5432)}${c.database ? '/' + c.database : ''}`
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader title="数据源" description="数据库和 Elasticsearch 的连接信息，密码加密保存。任务里用 datasource_id 引用，提交运行时才注入，配置里不会出现密码。">
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
          <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
        </el-select>
      </div>
    </div>
    <el-table v-loading="loading" :data="rows">
      <el-table-column prop="name" label="名称" min-width="160" />
      <el-table-column label="类型" width="130">
        <template #default="{ row }">{{ typeLabels[row.type as DatasourceType] }}</template>
      </el-table-column>
      <el-table-column label="地址" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">{{ summary(row) }}</template>
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
      :page-size="query.size"
      :total="total"
      layout="total, prev, pager, next"
      class="pager"
      @current-change="apply(false)"
    />
  </el-card>

  <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑数据源' : '新建数据源'" width="560px">
    <el-form label-width="90px">
      <el-form-item label="名称" required>
        <el-input v-model="dialog.name" maxlength="64" />
      </el-form-item>
      <el-form-item label="类型" required>
        <el-radio-group v-model="dialog.type" :disabled="!!dialog.id" @change="dialog.props = {}">
          <el-radio-button v-for="(label, value) in typeLabels" :key="value" :value="value">{{ label }}</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-for="field in fields" :key="field.key" :label="field.label" :required="field.required">
        <el-input
          v-model="dialog.props[field.key]"
          :type="field.secret ? 'password' : 'text'"
          :show-password="field.secret"
          :placeholder="field.secret ? secretPlaceholder(field) : field.placeholder"
          autocomplete="off"
        />
      </el-form-item>
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

.pager {
  margin-top: 16px;
  justify-content: flex-end;
}
.scope {
  flex: none;
}
</style>

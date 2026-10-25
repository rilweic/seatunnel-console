<script setup lang="ts">
/**
 * Step ① for a JDBC source: pick a database or schema, then tick tables (design.md §7.2). The
 * filter only narrows the list; the ticked tables are what the form keeps.
 */
import { Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, nextTick, onMounted, ref, watch } from 'vue'

import { datasourceApi, type TableMeta, type WizardForm } from '@/api/console'

import type { SourceContext } from './connectors'

const props = defineProps<{ form: WizardForm; context: SourceContext }>()
/** The selected tables without a primary key, whenever the selection or the table list changes. */
const emit = defineEmits<{ keyless: [names: string[]] }>()

const namespaces = ref<string[]>([])
const tables = ref<TableMeta[]>([])
const tableFilter = ref('')
const loadingTables = ref(false)
const tableRef = ref<{ toggleRowSelection: (row: TableMeta, selected: boolean) => void; clearSelection: () => void } | null>(null)
const selected = ref<string[]>(props.form.source.tables.map((t) => t.name))

const filteredTables = computed(() => {
  const q = tableFilter.value.trim()
  if (!q) return tables.value
  let re: RegExp | null = null
  try {
    re = new RegExp(q, 'i')
  } catch {
    re = null
  }
  return tables.value.filter((t) => (re ? re.test(t.name) : t.name.toLowerCase().includes(q.toLowerCase())))
})
const tableMeta = computed(() => new Map(tables.value.map((t) => [t.name, t])))
/** Tables not loaded yet count as keyed, so nothing is flagged before the list is known. */
const keyless = computed(() => selected.value.filter((n) => (tableMeta.value.get(n)?.primaryKeys.length ?? 1) === 0))
watch(keyless, (names) => emit('keyless', names), { immediate: true })

async function loadNamespaces() {
  namespaces.value = props.form.source.datasourceId ? await datasourceApi.namespaces(props.form.source.datasourceId) : []
}

async function loadTables() {
  tables.value = []
  if (!props.form.source.datasourceId || !props.form.source.namespace) return
  loadingTables.value = true
  try {
    tables.value = await datasourceApi.tables(props.form.source.datasourceId, props.form.source.namespace)
  } finally {
    loadingTables.value = false
  }
  await nextTick()
  restoreSelection()
}

/** Re-reads the table list, e.g. after tables were created; the selection is kept. */
async function refreshTables() {
  await loadTables()
  const names = new Set(tables.value.map((t) => t.name))
  const gone = selected.value.filter((n) => !names.has(n))
  if (gone.length) ElMessage.warning(`已选的表在源库里找不到了：${gone.join('，')}`)
  else ElMessage.success(`已刷新，共 ${tables.value.length} 张表`)
}

/** Shows `selected` in the table; it never changes `selected` itself. */
function restoreSelection() {
  tableRef.value?.clearSelection()
  for (const t of filteredTables.value) {
    if (selected.value.includes(t.name)) tableRef.value?.toggleRowSelection(t, true)
  }
}

/*
 * `selected` changes only on the user's own clicks (select / select-all). The table's
 * selection-change also fires when its rows change, e.g. while typing a filter, and would then
 * report the hidden tables as unchecked; listening to it lost the selection.
 */
// Order is kept (new tables go last), so checking and unchecking a table leaves the form as it was.
function onSelect(rows: TableMeta[], row: TableMeta) {
  const checked = rows.some((r) => r.name === row.name)
  selected.value = checked
    ? [...selected.value.filter((n) => n !== row.name), row.name]
    : selected.value.filter((n) => n !== row.name)
}

function onSelectAll(rows: TableMeta[]) {
  const visible = filteredTables.value.map((t) => t.name)
  selected.value = rows.length > 0
    ? [...selected.value, ...visible.filter((n) => !selected.value.includes(n))]
    : selected.value.filter((n) => !visible.includes(n))
}

watch(filteredTables, () => nextTick(restoreSelection))

async function onNamespace() {
  selected.value = []
  await loadTables()
}

/** Keeps per-table settings of tables that stay selected. */
watch(selected, (names) => {
  const old = new Map(props.form.source.tables.map((t) => [t.name, t]))
  props.form.source.tables = names.map((n) => old.get(n) ?? { name: n, columns: null, filter: '', incrementalField: '' })
})

onMounted(async () => {
  if (!props.form.source.datasourceId) return
  await loadNamespaces()
  await loadTables()
})
</script>

<template>
  <!-- beside the datasource select in the wizard's source form -->
  <Teleport defer to="#wizard-source-fields">
    <el-form label-position="top">
      <el-form-item label="库 / schema">
        <el-select v-model="form.source.namespace" placeholder="选择库或 schema" filterable @change="onNamespace" @visible-change="(open: boolean) => open && loadNamespaces()">
          <el-option v-for="n in namespaces" :key="n" :label="n" :value="n" />
        </el-select>
      </el-form-item>
    </el-form>
  </Teleport>
  <div v-if="form.source.namespace" class="tables">
    <div class="tables-heading">
      <div>
        <div class="block-title">选择需要同步的表</div>
        <div class="tip">勾选会保存到任务配置，筛选不会影响已选择的表。</div>
      </div>
      <div class="table-tags">
        <el-tag :type="selected.length ? 'primary' : 'info'" disable-transitions>已选 {{ selected.length }} / {{ tables.length }} 张表</el-tag>
        <el-tag v-if="keyless.length" type="warning" disable-transitions>{{ keyless.length }} 张没有主键</el-tag>
      </div>
    </div>
    <div class="tables-bar">
      <el-input v-model="tableFilter" placeholder="筛选表名，支持正则，如 ^order_" clearable class="table-filter" />
      <span class="tip bar-hint">输入关键字或正则表达式快速筛选</span>
      <el-button :icon="Refresh" :loading="loadingTables" class="refresh" @click="refreshTables">刷新</el-button>
    </div>
    <el-table ref="tableRef" v-loading="loadingTables" :data="filteredTables" row-key="name" max-height="440" class="table-box" @select="onSelect" @select-all="onSelectAll">
      <el-table-column type="selection" width="48" :reserve-selection="false" />
      <el-table-column prop="name" label="表名" min-width="280" />
      <el-table-column label="主键" min-width="180">
        <template #default="{ row }">
          <span v-if="row.primaryKeys.length">{{ row.primaryKeys.join(', ') }}</span>
          <el-tag v-else size="small" type="warning">无主键</el-tag>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.tables {
  margin-top: 20px;
  padding: 18px 20px 20px;
  border: 1px solid #e8edf4;
  border-radius: 10px;
}

.tables-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.block-title {
  margin-bottom: 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.table-tags {
  display: flex;
  flex: none;
  gap: 8px;
}

.tables-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  flex-wrap: wrap;
  background: #f8fafc;
  border-radius: 8px;
}

.table-filter {
  width: 360px;
  max-width: 100%;
}

.bar-hint {
  margin: 0;
}

.refresh {
  margin-left: auto;
}

.table-box {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  overflow: hidden;
}

.table-box :deep(th.el-table__cell) {
  background: #f8fafc;
}

/* the box's own border closes the table */
.table-box :deep(.el-table__body tr:last-child > td.el-table__cell) {
  border-bottom: none;
}
</style>

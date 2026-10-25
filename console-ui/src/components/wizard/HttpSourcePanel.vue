<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'

import type { HttpSourceField, HttpSourceSpec } from '@/api/console'
import JsonViewer from '@/components/json/JsonViewer.vue'

type JsonPathPart = string | number
type Paging = NonNullable<HttpSourceSpec['paging']>

interface Candidate {
  id: string
  key: JsonPathPart
  path: JsonPathPart[]
  values: unknown[]
  scalar: boolean
  children: Candidate[]
}

interface NestedTree {
  setCheckedKeys: (keys: string[]) => void
  getCheckedNodes: (leafOnly?: boolean, includeHalfChecked?: boolean) => Candidate[]
}

const props = withDefaults(
  defineProps<{
    modelValue: HttpSourceSpec
    loading?: boolean
    previewText?: string
    previewStatus?: number | null
    previewElapsed?: number | null
  }>(),
  { loading: false, previewText: '', previewStatus: null, previewElapsed: null },
)

const emit = defineEmits<{
  'update:modelValue': [value: HttpSourceSpec]
  preview: []
}>()

const fieldTypes = ['STRING', 'BIGINT', 'DOUBLE', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'DECIMAL']
const selectedPath = ref('')
const nestedTree = ref<NestedTree | null>(null)

const paramRows = computed(() => Object.entries(props.modelValue.params ?? {}))
const paging = computed<Paging>(() => props.modelValue.paging ?? { mode: 'NONE' })
const primaryKeys = computed(() => props.modelValue.primaryKeys ?? [])

const method = computed<HttpSourceSpec['method']>({
  get: () => props.modelValue.method,
  set: (method) => {
    const changes: Partial<HttpSourceSpec> = { method }
    if (method === 'GET' && paging.value.pageLocation === 'BODY') {
      changes.paging = { ...paging.value, pageLocation: 'PARAMS' }
    }
    update(changes)
  },
})
const pagingMode = computed<Paging['mode']>({
  get: () => paging.value.mode,
  set: (mode) => {
    if (mode === 'NONE') update({ paging: { mode } })
    else if (mode === 'CURSOR') {
      update({
        paging: {
          mode,
          pageLocation: 'PARAMS',
          cursorField: paging.value.cursorField || 'cursor',
          cursorResponseField: paging.value.cursorResponseField || 'cursor',
        },
      })
    } else {
      update({
        paging: {
          ...paging.value,
          mode,
          pageField: paging.value.pageField || 'page',
          pageLocation: paging.value.pageLocation || 'PARAMS',
          startPageNumber: paging.value.startPageNumber ?? 1,
          pageSizeField: paging.value.pageSizeField || 'pageSize',
          pageSize: paging.value.pageSize ?? 20,
        },
      })
    }
  },
})
const pageLocation = computed<'PARAMS' | 'BODY'>({
  get: () => paging.value.pageLocation ?? 'PARAMS',
  set: (pageLocation) => updatePaging({ pageLocation }),
})

const previewValue = computed<unknown>(() => {
  if (!props.previewText) return undefined
  try {
    return JSON.parse(props.previewText) as unknown
  } catch {
    return undefined
  }
})
const selectedRecords = computed<Record<string, unknown>[]>(() => {
  if (!selectedPath.value) return []
  try {
    const path = JSON.parse(selectedPath.value) as JsonPathPart[]
    const value = valueAtPath(previewValue.value, path)
    return Array.isArray(value) ? value.filter(isRecord).slice(0, 20) : []
  } catch {
    return []
  }
})
const candidates = computed(() => buildCandidates(selectedRecords.value, []))
const nestedCandidates = computed(() => candidates.value.filter((candidate) => candidate.children.length))

watch(
  () => [props.previewText, props.modelValue.contentField] as const,
  () => {
    selectedPath.value = props.modelValue.contentField
      ? findArrayPath(previewValue.value, props.modelValue.contentField)
      : ''
  },
  { immediate: true },
)

watch(
  [candidates, () => props.modelValue.fields.map((field) => field.jsonPath).join('\n')],
  async () => {
    await nextTick()
    const checked = props.modelValue.fields
      .map((field) => field.jsonPath)
      .filter((path): path is string => !!path)
      .map((jsonPath) => findCandidate(candidates.value, jsonPath)?.id)
      .filter((id): id is string => !!id)
    nestedTree.value?.setCheckedKeys(checked)
  },
  { immediate: true },
)

function update(changes: Partial<HttpSourceSpec>) {
  emit('update:modelValue', { ...props.modelValue, ...changes })
}

function updatePaging(changes: Partial<Paging>) {
  update({ paging: { ...paging.value, ...changes } })
}

function updateParamKey(index: number, key: string) {
  const entries = paramRows.value.map((entry) => [...entry] as [string, string])
  if (!entries[index] || entries[index][0] === key) return
  if (entries.some((entry, i) => i !== index && entry[0] === key)) return
  entries[index][0] = key
  update({ params: Object.fromEntries(entries) })
}

function updateParamValue(index: number, value: string) {
  const entries = paramRows.value.map((entry) => [...entry] as [string, string])
  if (!entries[index]) return
  entries[index][1] = value
  update({ params: Object.fromEntries(entries) })
}

function addParam() {
  const params = { ...(props.modelValue.params ?? {}) }
  let index = 1
  while (`param${index}` in params) index += 1
  params[`param${index}`] = ''
  update({ params })
}

function insertTimeWindow(mode: 'DAY' | 'HOUR') {
  const [start, end] = mode === 'DAY'
    ? ['$[yyyy-MM-dd-1]', '$[yyyy-MM-dd]']
    : ['$[yyyy-MM-dd HH-1/24]:00:00', '$[yyyy-MM-dd HH]:00:00']
  update({ params: { ...(props.modelValue.params ?? {}), start, end } })
}

function removeParam(index: number) {
  update({ params: Object.fromEntries(paramRows.value.filter((_, i) => i !== index)) })
}

function selectArray(path: JsonPathPart[]) {
  selectedPath.value = JSON.stringify(path)
  const records = recordsAtPath(path)
  const inferred = buildCandidates(records, [])
    .filter((candidate) => candidate.scalar)
    .map((candidate) => newField(candidate, null, new Set<string>()))
  const used = new Set<string>()
  inferred.forEach((field) => {
    field.name = uniqueName(field.name, used)
  })
  update({ contentField: toJsonPath(path), fields: inferred, primaryKeys: [] })
}

function syncNestedFields() {
  const checked = nestedTree.value?.getCheckedNodes(false, false) ?? []
  const checkedIds = new Set(
    checked.filter((candidate) => candidate.path.length > 1 && candidate.scalar).map((candidate) => candidate.id),
  )
  const oldDirect = props.modelValue.fields.filter((field) => !field.jsonPath)
  const oldByPath = new Map(props.modelValue.fields.filter((field) => field.jsonPath).map((field) => [field.jsonPath, field]))
  const used = new Set<string>()
  const direct = candidates.value
    .filter((candidate) => candidate.scalar)
    .map((candidate, index) => newField(candidate, oldByPath.get(toJsonPath(candidate.path)) ?? oldDirect[index] ?? null, used))
  const nested = flattenCandidates(candidates.value)
    .filter((candidate) => candidate.path.length > 1 && candidate.scalar && checkedIds.has(candidate.id))
    .map((candidate) => newField(candidate, oldByPath.get(toJsonPath(candidate.path)) ?? null, used))
  const fields = [...direct, ...nested]
  const names = new Set(fields.map((field) => field.name))
  update({ fields, primaryKeys: primaryKeys.value.filter((name) => names.has(name)) })
}

function updateField(index: number, changes: Partial<HttpSourceField>) {
  const fields = props.modelValue.fields.map((field) => ({ ...field }))
  if (!fields[index]) return
  fields[index] = { ...fields[index], ...changes }
  update({ fields })
}

function setPrimaryKey(name: string, checked: string | number | boolean) {
  const keys = primaryKeys.value.filter((key) => key !== name)
  if (checked) keys.push(name)
  update({ primaryKeys: keys })
}

function newField(candidate: Candidate, previous: HttpSourceField | null, used: Set<string>): HttpSourceField {
  const defaultName = [...candidate.path].reverse().find((part) => typeof part === 'string') ?? String(candidate.key)
  const type = previous?.type ?? inferType(candidate.values)
  return {
    name: uniqueName(previous?.name ?? defaultName, used),
    type,
    jsonPath: toJsonPath(candidate.path),
    precision: type === 'DECIMAL' ? previous?.precision ?? 18 : null,
    scale: type === 'DECIMAL' ? previous?.scale ?? 2 : null,
  }
}

function uniqueName(name: string, used: Set<string>) {
  let result = name
  let suffix = 2
  while (used.has(result)) result = `${name}_${suffix++}`
  used.add(result)
  return result
}

function recordsAtPath(path: JsonPathPart[]) {
  const value = valueAtPath(previewValue.value, path)
  return Array.isArray(value) ? value.filter(isRecord).slice(0, 20) : []
}

function valueAtPath(value: unknown, path: JsonPathPart[]): unknown {
  return path.reduce<unknown>((current, part) => {
    if (current === null || typeof current !== 'object') return undefined
    return (current as Record<JsonPathPart, unknown>)[part]
  }, value)
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function buildCandidates(containers: unknown[], basePath: JsonPathPart[]): Candidate[] {
  const groups = new Map<JsonPathPart, unknown[]>()
  containers.slice(0, 20).forEach((container) => {
    if (container === null || typeof container !== 'object') return
    const entries = Array.isArray(container)
      ? container.map((value, key) => [key, value] as const)
      : Object.entries(container)
    entries.forEach(([key, value]) => {
      const values = groups.get(key) ?? []
      values.push(value)
      groups.set(key, values)
    })
  })
  return [...groups.entries()].map(([key, values]) => {
    const path = [...basePath, key]
    const children = buildCandidates(values.filter((value) => value !== null && typeof value === 'object'), path)
    return {
      id: JSON.stringify(path),
      key,
      path,
      values,
      scalar: values.some((value) => value === null || typeof value !== 'object'),
      children,
    }
  })
}

function flattenCandidates(items: Candidate[]): Candidate[] {
  return items.flatMap((item) => [item, ...flattenCandidates(item.children)])
}

function findCandidate(items: Candidate[], jsonPath: string): Candidate | null {
  return flattenCandidates(items).find((candidate) => toJsonPath(candidate.path) === jsonPath) ?? null
}

function inferType(values: unknown[]): HttpSourceField['type'] {
  const present = values.filter((value) => value !== null && value !== undefined)
  if (!present.length) return 'STRING'
  if (present.every((value) => typeof value === 'boolean')) return 'BOOLEAN'
  if (present.every((value) => typeof value === 'number')) {
    return present.every((value) => Number.isInteger(value)) ? 'BIGINT' : 'DOUBLE'
  }
  if (present.every((value) => typeof value === 'string')) {
    if (present.every((value) => isDate(value as string))) return 'DATE'
    if (present.every((value) => isTimestamp(value as string))) return 'TIMESTAMP'
  }
  return 'STRING'
}

function isDate(value: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(Date.parse(value))
}

function isTimestamp(value: string) {
  return /^\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value)
    && !Number.isNaN(Date.parse(value))
}

function toJsonPath(path: JsonPathPart[]) {
  return path.reduce<string>((result, part) => {
    if (typeof part === 'number') return `${result}[${part}]`
    if (/^[A-Za-z_$][A-Za-z0-9_$]*$/.test(part)) return `${result}.${part}`
    return `${result}['${part.replace(/\\/g, '\\\\').replace(/'/g, "\\'")}']`
  }, '$')
}

function findArrayPath(value: unknown, jsonPath: string, path: JsonPathPart[] = []): string {
  if (Array.isArray(value) && toJsonPath(path) === jsonPath) return JSON.stringify(path)
  if (value === null || typeof value !== 'object') return ''
  const entries = Array.isArray(value) ? value.map((child, key) => [key, child] as const) : Object.entries(value)
  for (const [key, child] of entries) {
    const found = findArrayPath(child, jsonPath, [...path, key])
    if (found) return found
  }
  return ''
}
</script>

<template>
  <div class="http-source" v-loading="loading">
    <section class="section">
      <div class="section-title">请求配置</div>
      <div class="request-line">
        <el-radio-group v-model="method">
          <el-radio-button value="GET">GET</el-radio-button>
          <el-radio-button value="POST">POST</el-radio-button>
        </el-radio-group>
        <el-input
          :model-value="modelValue.path"
          placeholder="接口路径，如 /orders；基础地址在数据源里配置"
          clearable
          @update:model-value="update({ path: $event })"
        />
      </div>

      <div class="subsection-title">
        <span>请求参数</span>
        <div class="param-actions">
          <el-button size="small" @click="insertTimeWindow('DAY')">按天</el-button>
          <el-button size="small" @click="insertTimeWindow('HOUR')">按小时</el-button>
          <el-button size="small" @click="addParam">添加参数</el-button>
        </div>
      </div>
      <div v-if="paramRows.length" class="params">
        <div v-for="([key, value], index) in paramRows" :key="index" class="param-row">
          <el-input :model-value="key" placeholder="参数名" @update:model-value="updateParamKey(index, $event)" />
          <el-input :model-value="value" placeholder="参数值" clearable @update:model-value="updateParamValue(index, $event)" />
          <el-button link type="danger" @click="removeParam(index)">删除</el-button>
        </div>
      </div>
      <div v-else class="empty-inline">暂无请求参数</div>

      <div v-if="method === 'POST'" class="body-field">
        <div class="subsection-title">JSON Body</div>
        <el-input
          :model-value="modelValue.body ?? ''"
          type="textarea"
          :rows="5"
          placeholder='{"keyword":"example"}'
          class="code-input"
          @update:model-value="update({ body: $event })"
        />
      </div>
    </section>

    <section class="section">
      <div class="section-title">分页</div>
      <el-radio-group v-model="pagingMode" class="paging-mode">
        <el-radio-button value="NONE">不分页</el-radio-button>
        <el-radio-button value="PAGE_NUMBER">页码分页</el-radio-button>
        <el-radio-button value="CURSOR">游标分页</el-radio-button>
      </el-radio-group>

      <div v-if="pagingMode === 'PAGE_NUMBER'" class="paging-grid">
        <label>页码位置
          <el-select v-model="pageLocation">
            <el-option label="请求参数" value="PARAMS" />
            <el-option label="JSON Body" value="BODY" :disabled="method === 'GET'" />
          </el-select>
        </label>
        <label>页码字段
          <el-input :model-value="paging.pageField ?? ''" placeholder="page" @update:model-value="updatePaging({ pageField: $event })" />
        </label>
        <label>起始页
          <el-input-number
            :model-value="paging.startPageNumber ?? 1"
            :min="0"
            controls-position="right"
            @update:model-value="(value: number | undefined) => updatePaging({ startPageNumber: value ?? null })"
          />
        </label>
        <label>pageSize 字段
          <el-input :model-value="paging.pageSizeField ?? ''" placeholder="pageSize" @update:model-value="updatePaging({ pageSizeField: $event })" />
        </label>
        <label>pageSize 值
          <el-input-number
            :model-value="paging.pageSize ?? 20"
            :min="1"
            controls-position="right"
            @update:model-value="(value: number | undefined) => updatePaging({ pageSize: value ?? null })"
          />
        </label>
        <label>totalPageSize（可选）
          <el-input-number
            :model-value="paging.totalPageSize ?? undefined"
            :min="1"
            controls-position="right"
            placeholder="不限制"
            @update:model-value="(value: number | undefined) => updatePaging({ totalPageSize: value ?? null })"
          />
        </label>
      </div>

      <div v-else-if="pagingMode === 'CURSOR'" class="paging-grid cursor-grid">
        <label>游标位置
          <el-input model-value="请求参数" disabled />
        </label>
        <label>游标请求字段
          <el-input :model-value="paging.cursorField ?? ''" placeholder="cursor" @update:model-value="updatePaging({ cursorField: $event })" />
        </label>
        <label>游标响应字段
          <el-input :model-value="paging.cursorResponseField ?? ''" placeholder="nextCursor" @update:model-value="updatePaging({ cursorResponseField: $event })" />
        </label>
      </div>
    </section>

    <section class="section preview-section">
      <div class="preview-bar">
        <div class="section-title">响应预览</div>
        <div class="preview-actions">
          <el-tag v-if="previewStatus != null" :type="previewStatus >= 200 && previewStatus < 300 ? 'success' : 'danger'" disable-transitions>
            HTTP {{ previewStatus }}
          </el-tag>
          <span v-if="previewElapsed != null" class="elapsed">{{ previewElapsed }} ms</span>
          <el-button type="primary" :loading="loading" @click="emit('preview')">发送预览</el-button>
        </div>
      </div>
      <JsonViewer
        v-if="previewText"
        :text="previewText"
        selectable-arrays
        :selected-path="selectedPath"
        @select="selectArray"
      />
      <el-empty v-else description="发送预览后，可选择响应中的数据数组" :image-size="72" />
    </section>

    <section v-if="modelValue.contentField" class="section fields-section">
      <div class="section-title">
        数据字段
        <code>{{ modelValue.contentField }}</code>
      </div>
      <div class="field-help">根据数组中前 20 条对象记录推断；标量字段已加入，可勾选对象或数组中的嵌套标量字段。</div>

      <el-tree
        v-if="nestedCandidates.length"
        ref="nestedTree"
        :data="nestedCandidates"
        node-key="id"
        show-checkbox
        check-strictly
        default-expand-all
        :props="{ label: 'key', children: 'children', disabled: (data: Candidate) => data.path.length === 1 || !data.scalar }"
        class="nested-tree"
        @check="syncNestedFields"
      >
        <template #default="{ data }">
          <span class="candidate-label">
            <span>{{ data.key }}</span>
            <code>{{ toJsonPath(data.path) }}</code>
            <span v-if="data.scalar" class="candidate-type">{{ inferType(data.values) }}</span>
          </span>
        </template>
      </el-tree>

      <el-table :data="modelValue.fields" class="fields-table">
        <el-table-column label="字段名（只读）" min-width="180">
          <template #default="{ row }">
            <span>{{ row.name }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="150">
          <template #default="{ row, $index }">
            <el-select :model-value="row.type" @update:model-value="updateField($index, { type: $event })">
              <el-option v-for="type in fieldTypes" :key="type" :label="type" :value="type" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column v-if="modelValue.fields.some((field) => field.type === 'DECIMAL')" label="DECIMAL 精度" width="220">
          <template #default="{ row, $index }">
            <div v-if="row.type === 'DECIMAL'" class="decimal-options">
              <el-input-number
                :model-value="row.precision ?? 18"
                :min="1"
                :max="38"
                controls-position="right"
                @update:model-value="(value: number | undefined) => updateField($index, { precision: value ?? null })"
              />
              <el-input-number
                :model-value="row.scale ?? 2"
                :min="0"
                :max="row.precision ?? 38"
                controls-position="right"
                @update:model-value="(value: number | undefined) => updateField($index, { scale: value ?? null })"
              />
            </div>
          </template>
        </el-table-column>
        <el-table-column label="JsonPath" min-width="210">
          <template #default="{ row }">
            <code v-if="row.jsonPath">{{ row.jsonPath }}</code>
            <span v-else class="empty-inline">当前记录字段</span>
          </template>
        </el-table-column>
        <el-table-column label="主键" width="70" align="center">
          <template #default="{ row }">
            <el-checkbox
              :model-value="primaryKeys.includes(row.name)"
              @change="setPrimaryKey(row.name, $event)"
            />
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!modelValue.fields.length" description="所选数组中没有可推断的对象标量字段" :image-size="64" />
    </section>
  </div>
</template>

<style scoped>
.http-source {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.section {
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  background: #fff;
}

.section-title {
  margin-bottom: 12px;
  color: var(--el-text-color-primary);
  font-size: 15px;
  font-weight: 600;
}

.section-title code {
  margin-left: 8px;
  color: var(--el-color-primary);
  font-size: 12px;
  font-weight: 400;
}

.request-line {
  display: flex;
  gap: 12px;
}

.request-line .el-input {
  flex: 1;
}

.subsection-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 16px 0 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
  font-weight: 500;
}

.param-actions {
  display: flex;
  gap: 8px;
}

.param-actions .el-button + .el-button {
  margin-left: 0;
}

.params {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.param-row {
  display: grid;
  grid-template-columns: minmax(140px, 0.7fr) minmax(200px, 1.3fr) 48px;
  gap: 8px;
  align-items: center;
}

.empty-inline {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.body-field .subsection-title {
  justify-content: flex-start;
}

.code-input :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.paging-mode {
  margin-bottom: 14px;
}

.paging-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(160px, 1fr));
  gap: 12px 16px;
}

.paging-grid label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.paging-grid :deep(.el-select),
.paging-grid :deep(.el-input-number) {
  width: 100%;
}

.preview-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.preview-bar .section-title {
  margin: 0;
}

.preview-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.elapsed {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.field-help {
  margin: -4px 0 12px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.nested-tree {
  margin-bottom: 12px;
  padding: 8px;
  border: 1px solid var(--el-border-color-extra-light);
  border-radius: 6px;
  background: #fafbfc;
}

.candidate-label {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.candidate-label code {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}

.candidate-type {
  color: var(--el-color-primary);
  font-size: 11px;
}

.fields-table {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.fields-table :deep(th.el-table__cell) {
  background: #f8fafc;
}

.fields-table code {
  color: var(--el-text-color-regular);
  font-size: 12px;
  word-break: break-all;
}

.decimal-options {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}

.decimal-options :deep(.el-input-number) {
  width: 100%;
}

@media (max-width: 800px) {
  .request-line,
  .preview-bar {
    align-items: stretch;
    flex-direction: column;
  }

  .paging-grid {
    grid-template-columns: 1fr;
  }

  .param-row {
    grid-template-columns: 1fr;
  }
}
</style>

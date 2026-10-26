<script setup lang="ts">
/**
 * Field mapping per table (docs/design.md §7.8, §7.10): which source fields are synced, under
 * which target name and with what processing, the new fields (added or extracted), plus each
 * table's fixed filter and incremental field. Tables on the left, the chosen table on the right.
 * Columns that changed in the source table since the version was saved are marked (§12 P2, Q17).
 */
import { CirclePlus, EditPen, Key, Right, Scissor, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { encryptKeyApi, type AddedField, type ColumnMeta, type EncryptKeyView, type Extract, type FieldProcess, type NameRule, type WizardForm, type WizardTable } from '@/api/console'
import HelpTip from '@/components/HelpTip.vue'
import { columnChanges, describeChanges, type ColumnChanges } from '@/utils/columnChanges'
import { describeAdded, describeExtract, hasProcess, newFieldNames, processTags, readableFields, typeLabel } from '@/utils/fieldProcessing'
import { clashes, isEmptyRule, nameProblem, ruleFieldName, syncedFields, targetFieldName, targetTableName } from '@/utils/naming'

import AddFieldDialog from './AddFieldDialog.vue'
import ExtractDialog from './ExtractDialog.vue'
import FieldProcessDialog from './FieldProcessDialog.vue'
import NameRuleDialog, { type RuleScope } from './NameRuleDialog.vue'

const props = defineProps<{
  form: WizardForm
  columns: Record<string, ColumnMeta[]>
  loading: boolean
  /** Target names are lower case (ES indices). */
  lowerCase: boolean
  /** The source reads with a per-table filter (JDBC). */
  filters: boolean
  /** Why value processing, extracted and added fields are not offered now; null when they are. */
  processingBlocked?: string | null
  /** A real-time job, which syncs columns added to the source while it runs. */
  streaming?: boolean
}>()

const tableSearch = ref('')
const fieldSearch = ref('')
const current = ref(props.form.source.tables[0]?.name ?? '')
const checked = ref<string[]>([])
const ruleOpen = ref(false)

watch(
  () => props.form.source.tables.map((t) => t.name),
  (names) => {
    if (!names.includes(current.value)) current.value = names[0] ?? ''
  },
)
watch(current, () => {
  checked.value = []
  fieldSearch.value = ''
})

const table = computed(() => props.form.source.tables.find((t) => t.name === current.value) ?? null)
const upsert = computed(() => props.form.target.writeMode === 'UPSERT')
const incremental = computed(() => props.form.sync.mode === 'INCREMENTAL' && props.form.sync.window !== 'CUSTOM')
/**
 * A table's own incremental field only matters beside other tables (the shared one covers a single
 * table), but stays visible while it is set, so an override is never in effect unseen.
 */
const ownIncrementalField = (table: WizardTable) =>
  incremental.value && (props.form.source.tables.length > 1 || !!table.incrementalField)

const visibleTables = computed(() => {
  const q = tableSearch.value.trim().toLowerCase()
  return props.form.source.tables.filter(
    (t) => !q || t.name.toLowerCase().includes(q) || targetTableName(props.form, t, props.lowerCase).toLowerCase().includes(q),
  )
})

function allFields(t: WizardTable) {
  return (props.columns[t.name] ?? []).map((c) => c.name)
}

function synced(t: WizardTable) {
  return syncedFields(t, allFields(t))
}

/** Synced columns the table still has: a list saved earlier may name columns since dropped. */
function syncedCount(t: WizardTable) {
  const all = allFields(t)
  return all.length ? synced(t).filter((c) => all.includes(c)).length : synced(t).length
}

/** Target names of the synced fields that clash or are invalid, by table. */
function problems(t: WizardTable): Map<string, string> {
  const fields = synced(t)
  const names = fields.map((c) => targetFieldName(props.form, t, c))
  // new fields share the target table, so they count for clashes too
  const clashing = clashes([...names, ...newFieldNames(t)])
  const out = new Map<string, string>()
  fields.forEach((c, i) => {
    const p = nameProblem(names[i], clashing)
    if (p) out.set(c, p)
  })
  return out
}

const currentProblems = computed(() => (table.value ? problems(table.value) : new Map<string, string>()))

/** Problems of a table's new fields, by name (clashes with any other target field). */
function newProblems(t: WizardTable): Map<string, string> {
  const out = new Map<string, string>()
  const all = [...synced(t).map((c) => targetFieldName(props.form, t, c)), ...newFieldNames(t)]
  const clashing = clashes(all)
  newFieldNames(t).forEach((n) => {
    const p = nameProblem(n, clashing)
    if (p) out.set(n, p)
  })
  return out
}

const currentNewProblems = computed(() => (table.value ? newProblems(table.value) : new Map<string, string>()))

const rows = computed(() => {
  const t = table.value
  if (!t) return []
  const q = fieldSearch.value.trim().toLowerCase()
  const kept = new Set(synced(t))
  return (props.columns[t.name] ?? [])
    .map((c, i) => ({ ...c, no: i + 1, masked: !kept.has(c.name) }))
    .filter((c) => !q || c.name.toLowerCase().includes(q) || targetFieldName(props.form, t, c.name).toLowerCase().includes(q))
})

// ---- columns changed since the version was saved ----

function changesOf(t: WizardTable): ColumnChanges | null {
  return columnChanges(t.seenColumns, props.columns[t.name])
}

const changes = computed(() => (table.value ? changesOf(table.value) : null))

/**
 * A real-time job already passes on columns added to the source (its pruned columns are left out
 * by name), so a table that syncs a column list gets them added, rather than pruned on the next save.
 */
watch(
  () => props.form.source.tables.map((t) => [t.name, !!props.columns[t.name]]),
  () => {
    if (!props.streaming) return
    for (const t of props.form.source.tables) {
      const added = changesOf(t)?.added
      if (t.columns?.length && added) t.columns = [...t.columns, ...[...added].filter((n) => !t.columns!.includes(n))]
    }
  },
  { immediate: true },
)

/** Where the table's settings still name a column. */
function references(t: WizardTable, name: string): string[] {
  const out: string[] = []
  if (t.columns?.includes(name)) out.push('同步字段')
  if (t.fieldNames?.[name] != null) out.push('目标字段名')
  if (t.processes?.[name]) out.push('字段处理')
  if (t.incrementalField === name) out.push('增量字段')
  if ((t.extracts ?? []).some((e) => e.source === name)) out.push('提取字段')
  if ((t.addedFields ?? []).some((a) => (a.fields ?? []).includes(name))) out.push('新字段')
  return out
}

/** Drops a gone column from the column list, target names, processing and the incremental field. */
function forget(t: WizardTable, name: string) {
  if (t.columns?.length) t.columns = t.columns.filter((n) => n !== name)
  const names = { ...(t.fieldNames ?? {}) }
  delete names[name]
  t.fieldNames = Object.keys(names).length ? names : null
  const processes = { ...(t.processes ?? {}) }
  delete processes[name]
  t.processes = Object.keys(processes).length ? processes : null
  if (t.incrementalField === name) t.incrementalField = null
  const left = references(t, name)
  if (left.length) ElMessage.warning(`${left.join('、')}还用到 ${name}，请在下面的新字段里修改或删除`)
}

/**
 * Moves a renamed column's settings to its new name: synced or not, processing, the incremental
 * field, and the target name it had, so the target table keeps its column.
 */
function takeOver(t: WizardTable, from: string, to: string) {
  const target = shown(t, from)
  if (t.columns?.length) t.columns = t.columns.map((n) => (n === from ? to : n))
  const names = { ...(t.fieldNames ?? {}) }
  delete names[from]
  if (target !== ruleFieldName(props.form, t, to)) names[to] = target
  t.fieldNames = Object.keys(names).length ? names : null
  if (t.processes?.[from]) {
    const processes = { ...t.processes, [to]: t.processes[from] }
    delete processes[from]
    t.processes = processes
  }
  if (t.incrementalField === from) t.incrementalField = to
  ElMessage.success(`${to} 沿用了 ${from} 的设置，目标字段名仍是 ${target}`)
}

// ---- masking: the table's column list; null means every column ----

function setColumns(t: WizardTable, names: string[]) {
  const all = allFields(t)
  t.columns = names.length === all.length ? null : all.filter((n) => names.includes(n))
}

function lockedKey(c: ColumnMeta) {
  return upsert.value && c.primaryKey
}

/** Fields that processing, an extraction or a new field depends on; they must stay synced. */
function inUse(t: WizardTable): Set<string> {
  const used = new Set<string>()
  Object.entries(t.processes ?? {}).forEach(([f, p]) => hasProcess(p) && used.add(f))
  ;(t.extracts ?? []).forEach((e) => used.add(e.source))
  ;(t.addedFields ?? []).forEach((a) => (a.fields ?? []).forEach((f) => used.add(f)))
  return used
}

function mask(names: string[]) {
  const t = table.value!
  const used = inUse(t)
  const blocked = names.filter((n) => used.has(n))
  if (blocked.length) {
    ElMessage.warning(`${blocked.join('、')} 有处理，或被提取、新字段用到，先去掉这些再屏蔽`)
    names = names.filter((n) => !used.has(n))
    if (!names.length) return
  }
  const meta = new Map((props.columns[t.name] ?? []).map((c) => [c.name, c]))
  const allowed = names.filter((n) => !lockedKey(meta.get(n)!))
  if (allowed.length < names.length) ElMessage.warning('按主键 upsert 需要主键字段，主键没有屏蔽')
  const kept = synced(t).filter((n) => !allowed.includes(n))
  if (kept.length === 0) {
    ElMessage.warning('至少要同步一个字段')
    return
  }
  setColumns(t, kept)
  checked.value = []
}

function restore(names: string[]) {
  const t = table.value!
  setColumns(t, [...synced(t), ...names])
  checked.value = []
}

// ---- target names ----

function shown(t: WizardTable, column: string) {
  return t.fieldNames?.[column] ?? targetFieldName(props.form, t, column)
}

function onInput(t: WizardTable, column: string, value: string) {
  t.fieldNames = { ...(t.fieldNames ?? {}), [column]: value }
}

/** A name equal to the rule's, or left empty, follows the rule again. */
function onBlur(t: WizardTable, column: string) {
  if (!t.fieldNames || !(column in t.fieldNames)) return
  const typed = t.fieldNames[column].trim()
  const next = { ...t.fieldNames }
  if (!typed || typed === ruleFieldName(props.form, t, column)) delete next[column]
  else next[column] = typed
  t.fieldNames = Object.keys(next).length ? next : null
}

/** Drops the name set by hand, so the field follows the rule again. */
function followRule(t: WizardTable, column: string) {
  const next = { ...(t.fieldNames ?? {}) }
  delete next[column]
  t.fieldNames = Object.keys(next).length ? next : null
}

function byHandCount(t: WizardTable) {
  return Object.keys(t.fieldNames ?? {}).length
}

function applyRule(rule: NameRule, clearByHand: boolean, scope: RuleScope) {
  const value = isEmptyRule(rule) ? null : rule
  if (scope === 'all') {
    props.form.fieldRule = value
    props.form.source.tables.forEach((t) => {
      t.fieldRule = null
      if (clearByHand) t.fieldNames = null
    })
  } else {
    const t = table.value!
    // an empty table rule still matters when it keeps the job's rule off this table
    t.fieldRule = value ?? (props.form.fieldRule ? rule : null)
    if (clearByHand) t.fieldNames = null
  }
}

// ---- processing, new and extracted fields ----

const processing = ref<ColumnMeta | null>(null)

/** Enabled encryption keys, loaded when a table first encrypts a field. */
const keys = ref<EncryptKeyView[] | null>(null)
const encrypts = computed(() => Object.values(table.value?.processes ?? {}).some((p) => p.encrypt))
watch(encrypts, async (on) => {
  if (on && keys.value === null) keys.value = (await encryptKeyApi.list()).filter((k) => k.enabled || k.id === table.value?.encryptKeyId)
}, { immediate: true })
const processOpen = computed({ get: () => processing.value !== null, set: (o) => { if (!o) processing.value = null } })

function applyProcess(p: FieldProcess | null) {
  const t = table.value!
  const next = { ...(t.processes ?? {}) }
  if (p) next[processing.value!.name] = p
  else delete next[processing.value!.name]
  t.processes = Object.keys(next).length ? next : null
}

const addOpen = ref(false)
const editingAdded = ref<number | null>(null)
const extractOpen = ref(false)
const editingExtract = ref<number | null>(null)

function openAdd(index: number | null) {
  editingAdded.value = index
  addOpen.value = true
}

function openExtract(index: number | null) {
  editingExtract.value = index
  extractOpen.value = true
}

function applyAdded(a: AddedField) {
  const t = table.value!
  const list = [...(t.addedFields ?? [])]
  if (editingAdded.value == null) list.push(a)
  else list[editingAdded.value] = a
  t.addedFields = list
}

function applyExtract(e: Extract) {
  const t = table.value!
  const list = [...(t.extracts ?? [])]
  if (editingExtract.value == null) list.push(e)
  else list[editingExtract.value] = e
  t.extracts = list
}

function removeAdded(i: number) {
  const t = table.value!
  const list = (t.addedFields ?? []).filter((_, k) => k !== i)
  t.addedFields = list.length ? list : null
}

function removeExtract(i: number) {
  const t = table.value!
  const list = (t.extracts ?? []).filter((_, k) => k !== i)
  t.extracts = list.length ? list : null
}

/** Target names in use besides those of the new field being edited. */
function takenNames(t: WizardTable, except: { added?: number | null; extract?: number | null }) {
  const own = synced(t)
    .filter((c) => !(t.extracts ?? []).some((e, i) => i !== except.extract && e.dropSource && e.source === c))
    .map((c) => targetFieldName(props.form, t, c))
  const extracted = (t.extracts ?? []).filter((_, i) => i !== except.extract).flatMap((e) => e.outputs.map((o) => o.name))
  const added = (t.addedFields ?? []).filter((_, i) => i !== except.added).map((a) => a.name)
  return [...own, ...extracted, ...added]
}

/** One row per new field: the extracted ones, then the added ones. */
const newRows = computed(() => {
  const t = table.value
  if (!t) return []
  return [
    ...(t.extracts ?? []).flatMap((e, i) =>
      e.outputs.map((o) => ({ name: o.name, from: describeExtract(e) + (o.path ? `（${o.path}）` : ''), type: typeLabel(o.type), extract: i, added: null as number | null })),
    ),
    ...(t.addedFields ?? []).map((a, i) => ({ name: a.name, from: describeAdded(a), type: a.type ? typeLabel(a.type) : '按计算结果', extract: null as number | null, added: i })),
  ]
})

function timeFields(t: WizardTable) {
  return (props.columns[t.name] ?? []).filter((c) => c.temporal)
}
</script>

<template>
  <div class="field-mapping" v-loading="loading">
    <aside class="tables">
      <el-input v-model="tableSearch" :prefix-icon="Search" placeholder="搜索表名" clearable />
      <ul>
        <li
          v-for="t in visibleTables"
          :key="t.name"
          :class="{ active: t.name === current }"
          @click="current = t.name"
        >
          <div class="t-target">
            <span class="t-name">{{ targetTableName(form, t, lowerCase) }}</span>
            <span v-if="problems(t).size || newProblems(t).size" class="dot" title="有字段名冲突或不合法" />
            <el-tag v-if="changesOf(t)" size="small" type="warning" disable-transitions class="changed" :title="describeChanges(changesOf(t)!)">结构有变化</el-tag>
          </div>
          <div class="t-meta">
            <span class="t-source">{{ t.name }}</span>
            <span>已选 {{ syncedCount(t) }} / {{ allFields(t).length || '?' }}</span>
            <span v-if="byHandCount(t)">· 改名 {{ byHandCount(t) }}</span>
            <span v-if="newFieldNames(t).length">· 新字段 {{ newFieldNames(t).length }}</span>
            <span v-if="Object.keys(t.processes ?? {}).length">· 处理 {{ Object.keys(t.processes ?? {}).length }}</span>
          </div>
        </li>
      </ul>
    </aside>

    <section v-if="table" class="fields">
      <div class="fields-bar">
        <div class="table-title">
          {{ table.name }} <el-icon class="arrow"><Right /></el-icon> <b>{{ targetTableName(form, table, lowerCase) }}</b>
        </div>
        <div class="bar-actions">
          <el-tooltip :disabled="!processingBlocked" :content="processingBlocked ?? ''" placement="top">
            <span class="blockable">
              <el-button :icon="CirclePlus" :disabled="!!processingBlocked" @click="openAdd(null)">新增字段</el-button>
              <el-button :icon="Scissor" :disabled="!!processingBlocked" @click="openExtract(null)">提取字段</el-button>
            </span>
          </el-tooltip>
          <el-button :icon="EditPen" @click="ruleOpen = true">字段名规则</el-button>
          <el-button :disabled="!checked.length" @click="mask(checked)">屏蔽</el-button>
          <el-button :disabled="!checked.length" @click="restore(checked)">恢复</el-button>
        </div>
      </div>

      <div class="table-settings">
        <label v-if="filters">过滤条件
          <el-input v-model="table.filter" placeholder="可选，不含 where，如 status = 1；可以使用变量" class="code" clearable />
        </label>
        <label v-if="encrypts">加密密钥
          <el-select v-model="table.encryptKeyId" placeholder="选择这张表加密用的密钥" :class="{ invalid: !table.encryptKeyId }">
            <el-option v-for="k in keys ?? []" :key="k.id" :label="`${k.name}（${k.algorithm} · ${k.keyBits} 位）${k.enabled ? '' : '（已停用）'}`" :value="k.id" />
          </el-select>
          <span v-if="keys && !keys.length" class="error">还没有可用的密钥，请管理员在「运维管理 → 加密密钥」里新建</span>
        </label>
        <label v-if="ownIncrementalField(table)">增量字段
          <el-select
            v-model="table.incrementalField"
            :placeholder="form.sync.field ? `使用共用字段 ${form.sync.field}` : '选择时间字段'"
            clearable
          >
            <el-option v-for="c in timeFields(table)" :key="c.name" :label="`${c.name} (${c.type})`" :value="c.name" />
          </el-select>
        </label>
      </div>

      <el-alert v-if="changes" type="warning" :closable="false" show-icon class="changes">
        <template #title>源表在上次保存后{{ describeChanges(changes) }}，下面已按源表现在的结构列出并标出变化，保存后标记消失</template>
        <div v-if="streaming && changes.added.size">新增的列实时任务已经在同步，这里默认同步。</div>
        <div v-if="changes.renamed.size">改名的列按新字段处理，目标字段名会跟着变；要保持目标字段不变，点「沿用原设置」。</div>
        <div v-if="changes.dropped.length">删除的列列在表格下面；还被设置用到的，清理后才能保存。</div>
      </el-alert>

      <div class="search-row">
        <el-input v-model="fieldSearch" :prefix-icon="Search" placeholder="搜索字段名" clearable class="field-search" />
        <span class="tip">
          屏蔽的字段不会同步；目标字段名默认按规则生成，可以直接改<template v-if="currentProblems.size">，<span class="error">{{ currentProblems.size }} 个字段名有问题</span></template>
        </span>
      </div>

      <el-table
        :data="rows"
        row-key="name"
        max-height="460"
        class="table-box"
        :row-class-name="({ row }: { row: { masked: boolean } }) => (row.masked ? 'masked' : '')"
        @selection-change="(sel: { name: string }[]) => (checked = sel.map((r) => r.name))"
      >
        <el-table-column type="selection" width="44" />
        <el-table-column prop="no" label="序号" width="64" align="center" />
        <el-table-column label="源字段" min-width="220">
          <template #default="{ row }">
            <span class="f-name">{{ row.name }}</span>
            <el-tooltip v-if="row.primaryKey" content="主键" placement="top">
              <el-icon class="key"><Key /></el-icon>
            </el-tooltip>
            <template v-if="changes">
              <el-tag v-if="changes.added.has(row.name)" size="small" type="success" disable-transitions class="change-tag">新增</el-tag>
              <el-tag v-else-if="changes.renamed.has(row.name)" size="small" type="warning" disable-transitions class="change-tag">改名，原 {{ changes.renamed.get(row.name) }}</el-tag>
            </template>
            <div class="f-type">
              {{ row.type }}<span v-if="changes?.retyped.has(row.name)" class="was">（原 {{ changes.retyped.get(row.name) }}）</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="目标字段名" min-width="240">
          <template #default="{ row }">
            <span v-if="row.masked" class="tip">不同步</span>
            <template v-else>
              <el-input
                :model-value="shown(table, row.name)"
                :class="{ invalid: currentProblems.has(row.name) }"
                @update:model-value="(v: string) => onInput(table!, row.name, v)"
                @blur="onBlur(table!, row.name)"
              />
              <div v-if="currentProblems.has(row.name)" class="error">{{ currentProblems.get(row.name) }}</div>
              <div v-if="processTags(table.processes?.[row.name]).length" class="tags">
                <el-tag v-for="tag in processTags(table.processes?.[row.name])" :key="tag" size="small" type="warning" disable-transitions>{{ tag }}</el-tag>
              </div>
            </template>
          </template>
        </el-table-column>
        <el-table-column label="操作" :width="changes?.renamed.size ? 250 : 170">
          <template #default="{ row }">
            <div class="row-actions">
              <el-tooltip v-if="!row.masked && processingBlocked" :content="processingBlocked" placement="top">
                <span><el-button link type="primary" disabled>处理</el-button></span>
              </el-tooltip>
              <el-button v-else-if="!row.masked" link type="primary" @click="processing = row">处理</el-button>
              <el-button v-if="row.masked" link type="primary" @click="restore([row.name])">恢复</el-button>
              <el-tooltip v-else-if="lockedKey(row)" content="按主键 upsert 需要主键字段" placement="top">
                <span><el-button link type="primary" disabled>屏蔽</el-button></span>
              </el-tooltip>
              <el-button v-else link type="primary" @click="mask([row.name])">屏蔽</el-button>
              <el-button
                v-if="!row.masked && table.fieldNames?.[row.name] != null"
                link
                type="info"
                @click="followRule(table!, row.name)"
              >还原</el-button>
              <el-tooltip v-if="changes?.renamed.has(row.name)" :content="`同步与否、目标字段名、字段处理和增量字段都按 ${changes.renamed.get(row.name)} 原来的设置`" placement="top">
                <el-button link type="warning" @click="takeOver(table!, changes!.renamed.get(row.name)!, row.name)">沿用原设置</el-button>
              </el-tooltip>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <template v-if="changes?.dropped.length">
        <div class="new-title">源表已删除的字段</div>
        <ul class="dropped">
          <li v-for="c in changes.dropped" :key="c.name">
            <span class="f-name">{{ c.name }}</span>
            <span class="f-type">{{ c.type }}</span>
            <el-tag size="small" type="danger" disable-transitions>已删除</el-tag>
            <template v-if="references(table, c.name).length">
              <span class="error">{{ references(table, c.name).join('、') }}还用到这一列</span>
              <el-button link type="primary" @click="forget(table!, c.name)">清理设置</el-button>
            </template>
            <span v-else class="tip">设置里已经没有用到</span>
          </li>
        </ul>
      </template>

      <template v-if="newRows.length">
        <div class="new-title">新字段 <HelpTip text="按顺序写在目标表的最后" /></div>
        <el-table :data="newRows" class="table-box">
          <el-table-column label="字段名" prop="name" min-width="160">
            <template #default="{ row }">
              <span class="f-name">{{ row.name }}</span>
              <el-tag size="small" type="success" disable-transitions class="new-tag">{{ row.extract != null ? '提取' : '新增' }}</el-tag>
              <div v-if="currentNewProblems.get(row.name)" class="error">{{ currentNewProblems.get(row.name) }}</div>
            </template>
          </el-table-column>
          <el-table-column label="来源 / 算法" prop="from" min-width="260" show-overflow-tooltip />
          <el-table-column label="类型" prop="type" width="110" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="row.extract != null ? openExtract(row.extract) : openAdd(row.added)">编辑</el-button>
              <el-button link type="danger" @click="row.extract != null ? removeExtract(row.extract) : removeAdded(row.added)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <FieldProcessDialog v-model="processOpen" :column="processing" :process="processing ? table.processes?.[processing.name] : null" @apply="applyProcess" />
      <AddFieldDialog
        v-model="addOpen"
        :field="editingAdded == null ? null : (table.addedFields ?? [])[editingAdded] ?? null"
        :fields="readableFields(table, synced(table))"
        :taken="takenNames(table, { added: editingAdded })"
        @apply="applyAdded"
      />
      <ExtractDialog
        v-model="extractOpen"
        :extract="editingExtract == null ? null : (table.extracts ?? [])[editingExtract] ?? null"
        :fields="synced(table)"
        :keys="(columns[table.name] ?? []).filter((c) => c.primaryKey).map((c) => c.name)"
        :taken="takenNames(table, { extract: editingExtract })"
        @apply="applyExtract"
      />

      <NameRuleDialog
        v-model="ruleOpen"
        title="字段名规则"
        :rule="table.fieldRule ?? form.fieldRule"
        :items="synced(table).map((c) => ({ name: c, byHand: table!.fieldNames?.[c] }))"
        source-label="源字段名"
        target-label="目标字段名"
        scopes
        @apply="applyRule"
      />
    </section>
  </div>
</template>

<style scoped>
.field-mapping {
  display: grid;
  grid-template-columns: 280px minmax(0, 1fr);
  min-height: 420px;
  border: 1px solid #e8edf4;
  border-radius: 10px;
  overflow: hidden;
}

.tables {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 12px;
  background: #f8fafc;
  border-right: 1px solid #e8edf4;
}

.tables ul {
  flex: 1;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  max-height: 600px;
  list-style: none;
}

.tables li {
  padding: 10px 12px;
  border-left: 3px solid transparent;
  border-radius: 6px;
  cursor: pointer;
  transition: background 0.15s;
}

.tables li:hover {
  background: #eef3fb;
}

.tables li.active {
  background: #e8f1ff;
  border-left-color: var(--el-color-primary);
}

.t-target {
  display: flex;
  align-items: center;
  gap: 6px;
}

.t-name {
  overflow: hidden;
  color: var(--el-text-color-primary);
  font-weight: 500;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tables li.active .t-name {
  color: var(--el-color-primary);
}

.dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-danger);
}

.t-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 8px;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.t-source {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.fields {
  min-width: 0;
  padding: 14px 16px 16px;
}

.fields-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 12px;
}

.table-title {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 14px;
}

.table-title b {
  color: var(--el-text-color-primary);
  font-weight: 600;
}

.arrow {
  color: var(--el-text-color-placeholder);
}

.bar-actions {
  display: flex;
  gap: 0;
}

.blockable {
  display: inline-flex;
  margin-right: 12px;
}

.table-settings {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  gap: 16px;
  margin-bottom: 12px;
  padding: 12px 14px;
  background: #f8fafc;
  border-radius: 8px;
}

.table-settings label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.search-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.field-search {
  width: 260px;
}

.table-box {
  width: 100%;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.table-box :deep(th.el-table__cell) {
  background: #f8fafc;
}

.table-box :deep(.el-table__body tr:last-child > td.el-table__cell) {
  border-bottom: none;
}

.table-box :deep(tr.masked) {
  color: var(--el-text-color-placeholder);
}

.table-box :deep(tr.masked .f-name) {
  text-decoration: line-through;
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.table-box .row-actions :deep(.el-button.el-button.is-link) {
  margin-left: 0;
}

.f-name {
  font-weight: 500;
}

.key {
  margin-left: 4px;
  color: var(--el-color-warning);
  vertical-align: -2px;
}

.change-tag,
.changed {
  margin-left: 6px;
}

.was {
  color: var(--el-color-warning-dark-2);
}

.changes {
  margin-bottom: 12px;
}

.dropped {
  margin: 0 0 12px;
  padding: 0;
  list-style: none;
}

.dropped li {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 6px 0;
  font-size: 13px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 4px;
}

.new-title {
  margin: 16px 0 8px;
  font-weight: 500;
}

.new-tag {
  margin-left: 6px;
}

.f-type {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.error {
  color: var(--el-color-danger);
  font-size: 12px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.code :deep(input) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

@media (max-width: 900px) {
  .field-mapping {
    grid-template-columns: 1fr;
  }

  .tables {
    border-right: none;
    border-bottom: 1px solid #e8edf4;
  }

  .tables ul {
    max-height: 220px;
  }

  .table-settings {
    grid-template-columns: 1fr;
  }

  .field-search {
    width: 100%;
  }
}
</style>

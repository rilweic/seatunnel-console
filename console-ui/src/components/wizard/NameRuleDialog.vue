<script setup lang="ts">
/**
 * Edits a name rule with a live preview of every name it produces. Names set by hand win over the
 * rule and are kept unless "重置" clears them too.
 */
import { RefreshLeft, Search } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'

import type { NameRule } from '@/api/console'
import { applyRule } from '@/utils/naming'

export type RuleScope = 'table' | 'all'

const props = defineProps<{
  title: string
  rule: NameRule | null | undefined
  /** names the rule applies to, with the name set by hand if any */
  items: { name: string; byHand?: string | null }[]
  sourceLabel: string
  targetLabel: string
  /** offer find and replace */
  withReplace?: boolean
  /** results are lower-cased (Elasticsearch index names) */
  lower?: boolean
  /** offer applying to the current table or to all tables */
  scopes?: boolean
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ apply: [rule: NameRule, clearByHand: boolean, scope: RuleScope] }>()

const empty = (): NameRule => ({ find: '', replace: '', nameCase: 'KEEP', prefix: '', suffix: '' })
const draft = ref<NameRule>(empty())
const clearByHand = ref(false)
const scope = ref<RuleScope>('table')
const search = ref('')

watch(visible, (open) => {
  if (!open) return
  draft.value = { ...empty(), ...(props.rule ?? {}) }
  clearByHand.value = false
  scope.value = 'table'
  search.value = ''
})

function reset() {
  draft.value = empty()
  clearByHand.value = true
}

const rows = computed(() => {
  const q = search.value.trim().toLowerCase()
  return props.items
    .filter((i) => !q || i.name.toLowerCase().includes(q))
    .map((i) => {
      const byHand = !clearByHand.value && i.byHand?.trim() ? i.byHand.trim() : null
      const name = byHand ?? applyRule(draft.value, i.name)
      return { source: i.name, target: props.lower ? name.toLowerCase() : name, byHand: !!byHand }
    })
})
const byHandCount = computed(() => (clearByHand.value ? 0 : props.items.filter((i) => i.byHand?.trim()).length))

function confirm() {
  emit('apply', { ...draft.value }, clearByHand.value, scope.value)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" :title="title" width="760px" append-to-body class="rule-dialog">
    <div class="rule-head">
      <span class="rule-label">规则配置</span>
      <el-button link type="primary" :icon="RefreshLeft" @click="reset">重置</el-button>
      <span class="tip">重置会清空规则，也会清掉手动改过的名字</span>
    </div>
    <div class="rule-box">
      <div v-if="withReplace" class="rule-row two">
        <label>查找文本<el-input v-model="draft.find" placeholder="名字里要替换的文字" clearable /></label>
        <label>替换为<el-input v-model="draft.replace" placeholder="留空表示删除" clearable /></label>
      </div>
      <div class="rule-row three">
        <label>大小写
          <el-select v-model="draft.nameCase">
            <el-option label="不变" value="KEEP" />
            <el-option label="转大写" value="UPPER" />
            <el-option label="转小写" value="LOWER" />
          </el-select>
        </label>
        <label>前缀<el-input v-model="draft.prefix" placeholder="如 ods_" clearable /></label>
        <label>后缀<el-input v-model="draft.suffix" placeholder="可选" clearable /></label>
      </div>
      <div class="tip order">按"查找替换 → 大小写 → 前后缀"的顺序生成<template v-if="lower">；ES 索引名一律转小写</template></div>
    </div>

    <div class="preview-bar">
      <el-input v-model="search" :prefix-icon="Search" placeholder="搜索名字" clearable class="search" />
      <span v-if="byHandCount" class="tip">{{ byHandCount }} 个名字是手动改的，规则不影响它们</span>
    </div>
    <el-table :data="rows" max-height="320" class="preview">
      <el-table-column :label="sourceLabel" prop="source" min-width="200" show-overflow-tooltip />
      <el-table-column width="40" align="center">
        <template #default><span class="arrow">›</span></template>
      </el-table-column>
      <el-table-column :label="targetLabel" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <span :class="{ changed: row.target !== row.source }">{{ row.target }}</span>
          <el-tag v-if="row.byHand" size="small" type="info" class="hand">手改</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <template #footer>
      <div class="footer">
        <el-radio-group v-if="scopes" v-model="scope" class="scope">
          <el-radio value="table">应用到当前表</el-radio>
          <el-radio value="all">应用到全部表</el-radio>
        </el-radio-group>
        <span v-else />
        <div>
          <el-button @click="visible = false">取消</el-button>
          <el-button type="primary" @click="confirm">确定</el-button>
        </div>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.rule-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.rule-label {
  font-weight: 500;
}

.rule-box {
  padding: 14px 16px 10px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 10px;
}

.rule-row {
  display: grid;
  gap: 16px;
  margin-bottom: 12px;
}

.rule-row.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.rule-row.three {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.rule-row label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.order {
  margin-top: -4px;
}

.preview-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 16px 0 10px;
}

.search {
  width: 280px;
}

.preview {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.preview :deep(.el-table__body tr:last-child > td.el-table__cell) {
  border-bottom: none;
}

.arrow {
  color: var(--el-text-color-placeholder);
  font-size: 16px;
}

.changed {
  color: var(--el-color-primary);
}

.hand {
  margin-left: 6px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

@media (max-width: 720px) {
  .rule-row.two,
  .rule-row.three {
    grid-template-columns: 1fr;
  }

  .search {
    width: 100%;
  }
}
</style>

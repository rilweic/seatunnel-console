<script setup lang="ts">
/**
 * Which target table (or index) each source table writes to (docs/design.md §7.8): derived by the
 * table rule, or set by hand row by row.
 */
import { EditPen, Right } from '@element-plus/icons-vue'
import { computed, ref } from 'vue'

import type { NameRule, WizardForm, WizardTable } from '@/api/console'
import { clashes, isEmptyRule, nameProblem, ruleTableName, targetTableName } from '@/utils/naming'

import NameRuleDialog from './NameRuleDialog.vue'

/** @property lowerCase target names are lower case (ES indices); tableWord: 目标表, 索引 */
const props = defineProps<{ form: WizardForm; lowerCase: boolean; tableWord: string }>()

const ruleOpen = ref(false)
const what = computed(() => props.tableWord)

const names = computed(() => props.form.source.tables.map((t) => targetTableName(props.form, t, props.lowerCase)))
const clashing = computed(() => clashes(names.value))
const byHandCount = computed(() => props.form.source.tables.filter((t) => t.targetName != null).length)

const ruleText = computed(() => {
  const r = props.form.target.tableRule
  if (isEmptyRule(r)) return '与源表同名'
  const parts: string[] = []
  if (r!.find) parts.push(`把 "${r!.find}" 替换为 "${r!.replace ?? ''}"`)
  if (r!.nameCase === 'UPPER') parts.push('转大写')
  if (r!.nameCase === 'LOWER') parts.push('转小写')
  if (r!.prefix?.trim() || r!.suffix?.trim()) parts.push(`${r!.prefix?.trim() ?? ''}{源表名}${r!.suffix?.trim() ?? ''}`)
  return parts.join('，')
})

/** What the input shows: the name being typed, else the derived one. */
function shown(t: WizardTable, i: number) {
  return t.targetName != null ? t.targetName : names.value[i]
}

function onInput(t: WizardTable, value: string) {
  t.targetName = value
}

/** A name equal to the rule's, or left empty, follows the rule again. */
function onBlur(t: WizardTable) {
  const typed = t.targetName?.trim()
  t.targetName = !typed || typed === ruleTableName(props.form, t, props.lowerCase) ? null : typed
}

function applyRule(rule: NameRule, clearByHand: boolean) {
  props.form.target.tableRule = rule
  if (clearByHand) props.form.source.tables.forEach((t) => (t.targetName = null))
}
</script>

<template>
  <div class="mapping">
    <div class="mapping-heading">
      <div>
        <div class="block-title">表映射</div>
        <div class="tip">每张源表写入哪张{{ what }}。命名规则：<b>{{ ruleText }}</b>；也可以直接改某一行，改过的行不受规则影响。</div>
      </div>
      <div class="actions">
        <el-tag v-if="byHandCount" type="info" disable-transitions>手动改了 {{ byHandCount }} 张</el-tag>
        <el-button :icon="EditPen" @click="ruleOpen = true">批量改名</el-button>
      </div>
    </div>
    <el-table :data="form.source.tables" max-height="420" class="table-box" row-key="name">
      <el-table-column type="index" label="序号" width="64" align="center" />
      <el-table-column label="源表" prop="name" min-width="220" show-overflow-tooltip />
      <el-table-column width="48" align="center">
        <template #default><el-icon class="arrow"><Right /></el-icon></template>
      </el-table-column>
      <el-table-column :label="`${what}名`" min-width="280">
        <template #default="{ row, $index }">
          <el-input
            :model-value="shown(row, $index)"
            :class="{ invalid: nameProblem(names[$index], clashing) }"
            @update:model-value="(v: string) => onInput(row, v)"
            @blur="onBlur(row)"
          />
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="200">
        <template #default="{ row, $index }">
          <span v-if="nameProblem(names[$index], clashing)" class="error">{{ nameProblem(names[$index], clashing) }}</span>
          <template v-else-if="row.targetName != null">
            <el-tag size="small" type="info" disable-transitions>手改</el-tag>
            <el-button link type="primary" class="reset" @click="row.targetName = null">恢复规则</el-button>
          </template>
          <span v-else-if="names[$index] === row.name" class="tip">同名</span>
          <span v-else class="tip">按规则</span>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="lowerCase" class="tip note">{{ what }}名一律转为小写。</div>

    <NameRuleDialog
      v-model="ruleOpen"
      :title="`${what}批量改名`"
      :rule="form.target.tableRule"
      :items="form.source.tables.map((t) => ({ name: t.name, byHand: t.targetName }))"
      source-label="源表名"
      :target-label="`${what}名`"
      with-replace
      :lower="lowerCase"
      @apply="applyRule"
    />
  </div>
</template>

<style scoped>
.mapping {
  margin-top: 20px;
  padding: 18px 20px 20px;
  border: 1px solid #e8edf4;
  border-radius: 10px;
}

.mapping-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 14px;
}

.block-title {
  margin-bottom: 4px;
  font-size: 15px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.actions {
  display: flex;
  flex: none;
  align-items: center;
  gap: 10px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.tip b {
  font-weight: 500;
  color: var(--el-text-color-regular);
}

.note {
  margin-top: 8px;
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

.arrow {
  color: var(--el-text-color-placeholder);
}

.invalid :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.error {
  color: var(--el-color-danger);
  font-size: 13px;
}

.reset {
  margin-left: 8px;
  font-size: 13px;
}

@media (max-width: 720px) {
  .mapping {
    padding: 14px;
  }

  .mapping-heading {
    flex-direction: column;
  }
}
</style>

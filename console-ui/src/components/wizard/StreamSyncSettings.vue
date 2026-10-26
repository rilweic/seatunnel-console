<script setup lang="ts">
/**
 * How a real-time job reads and applies changes (design.md §12 P2): where it starts, whether
 * table-structure changes are passed on, and whether deletes are. Which choices exist depends on
 * the source (PostgreSQL only streams added columns, and cannot start from a time) and the target
 * (Elasticsearch only applies added columns), as the cluster tests showed.
 */
import { computed } from 'vue'

import type { StreamSettings } from '@/api/console'
import HelpTip from '@/components/HelpTip.vue'

const props = defineProps<{
  /** the source is PostgreSQL */
  postgres: boolean
  /** the target applies every column change (false for Elasticsearch) */
  allColumnChanges: boolean
  readonly: boolean
}>()
const stream = defineModel<StreamSettings>({ required: true })

const startupTime = computed({
  get: () => (stream.value.startupTimestamp ? new Date(stream.value.startupTimestamp) : null),
  set: (value: Date | null) => (stream.value.startupTimestamp = value ? value.getTime() : null),
})

/** What a change of the source table does with the current choices. */
const changesText = computed(() => {
  if (!stream.value.schemaChanges) {
    return '源表加的列不会同步；源表改名、删列或改类型后，需要修改任务并重新启动。'
  }
  if (props.postgres) {
    return 'PostgreSQL 只能同步加列；源表改名、删列或改类型会让任务停下，需要修改任务后全新启动。'
  }
  if (!props.allColumnChanges) {
    return '目标是 Elasticsearch：只能自动同步加列；源表改名、改类型或删列会让任务停下，需要处理后全新启动。'
  }
  return stream.value.dropColumns
    ? '加列、改类型、改列名、删列都同步到目标表。'
    : '加列、改类型、改列名同步到目标表；源表删掉的列在目标端保留，之后写入空值。'
})

const dropsOffered = computed(() => stream.value.schemaChanges && !props.postgres && props.allColumnChanges)
</script>

<template>
  <el-form label-width="120px" class="form-card" :disabled="readonly">
    <el-form-item label="从哪里开始">
      <el-radio-group v-model="stream.startup">
        <el-radio value="INITIAL">全量 + 增量</el-radio>
        <el-radio value="LATEST">只同步之后的变更</el-radio>
        <el-radio v-if="!postgres" value="TIMESTAMP">从某个时间点开始</el-radio>
      </el-radio-group>
      <div class="tip full">
        <template v-if="stream.startup === 'INITIAL'">先把表里现有的数据同步过去，再持续同步之后的变更。</template>
        <template v-else-if="stream.startup === 'LATEST'">不同步现有数据，只同步启动之后发生的插入、更新和删除。</template>
        <template v-else>从这个时间点之后的 binlog 开始同步；这个时间点的 binlog 必须还在（没有过期清理）。</template>
        这个设置只在全新启动时生效；从 savepoint 或 checkpoint 恢复时接着上次的位置。
      </div>
      <el-date-picker
        v-if="stream.startup === 'TIMESTAMP'"
        v-model="startupTime"
        type="datetime"
        placeholder="开始同步的时间"
        class="w320 picker"
      />
    </el-form-item>
    <el-form-item label="表结构变更">
      <el-switch v-model="stream.schemaChanges" active-text="同步表结构变更" @change="(on: boolean) => !on && (stream.dropColumns = false)" />
      <HelpTip text="源表加列、改列名、改类型时，自动修改目标表。打开后不能使用字段处理（替换、脱敏、类型转换、加密、新增和提取字段）：源表改名或删列时，字段处理会让任务失败" />
      <div class="tip full">{{ changesText }}</div>
      <el-checkbox v-if="dropsOffered" v-model="stream.dropColumns" class="full">同步删列（目标表也删掉这一列，历史数据一起删除）</el-checkbox>
      <div v-if="stream.schemaChanges && !postgres" class="tip full">
        源表改列名请用 <code>ALTER TABLE … CHANGE COLUMN</code>；MySQL 8 的 <code>RENAME COLUMN</code> 不会被同步，之后这一列在目标端会是空值。
      </div>
    </el-form-item>
    <el-form-item label="删除">
      <el-switch v-model="stream.skipDeletes" active-text="不同步删除" />
      <div class="tip full">
        {{ stream.skipDeletes ? '源表删除的行在目标端保留（只增不删）。' : '源表删除的行，目标端也按主键删除。' }}
      </div>
    </el-form-item>
  </el-form>
</template>

<style scoped>
.form-card {
  box-sizing: border-box;
  padding: 18px 20px 6px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 10px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.full {
  width: 100%;
  margin-top: 6px;
}

.w320 {
  width: 320px;
}

.picker {
  margin-top: 8px;
}
</style>

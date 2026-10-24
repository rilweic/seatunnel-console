<script setup lang="ts">
import type { InstanceView } from '@/api/console'
import { formatNumber } from '@/utils/format'

defineProps<{ instance: Pick<InstanceView, 'validationStatus' | 'sourceCount' | 'targetCount' | 'validationMessage'> }>()

const text = { PASSED: '一致', MISMATCH: '不一致', ERROR: '校验出错' } as const
const type = { PASSED: 'success', MISMATCH: 'danger', ERROR: 'warning' } as const
</script>

<template>
  <el-tooltip v-if="instance.validationStatus && instance.validationStatus !== 'SKIPPED'" :content="instance.validationMessage ?? ''" placement="top">
    <el-tag size="small" :type="type[instance.validationStatus]">
      {{ text[instance.validationStatus] }}
      <template v-if="instance.validationStatus === 'MISMATCH'">
        {{ formatNumber(instance.sourceCount) }} / {{ formatNumber(instance.targetCount) }}
      </template>
    </el-tag>
  </el-tooltip>
  <span v-else class="none">-</span>
</template>

<style scoped>
.none {
  color: var(--el-text-color-secondary);
}
</style>

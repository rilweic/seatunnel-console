<script setup lang="ts">
import { QuestionFilled } from '@element-plus/icons-vue'

import { textTooltip } from '@/utils/tooltip'

withDefaults(defineProps<{ text: string; placement?: 'top' | 'right' | 'bottom' | 'left' }>(), { placement: 'top' })
</script>

<template>
  <el-tooltip :content="text" :placement="placement" v-bind="textTooltip">
    <button type="button" class="help-tip" aria-label="查看说明">
      <el-icon><QuestionFilled /></el-icon>
    </button>
  </el-tooltip>
</template>

<style scoped>
.help-tip {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-left: 5px;
  padding: 0;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1;
  vertical-align: -2px;
  cursor: help;
  background: transparent;
  border: 0;
}

.help-tip:hover,
.help-tip:focus-visible {
  color: var(--el-color-primary);
}
</style>

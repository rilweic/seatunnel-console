<script setup lang="ts">
/**
 * The arrow before a detail page's title. It goes back to wherever the user came from inside the
 * console (a filtered list, a job's run tab, ...); opened directly, it goes up to `fallback`.
 */
import { ArrowLeft } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'

const props = defineProps<{ fallback: string }>()
const router = useRouter()

function back() {
  // vue-router keeps the previous in-app location in history.state.back.
  if ((window.history.state as { back?: string | null } | null)?.back) {
    router.back()
  } else {
    router.push(props.fallback)
  }
}
</script>

<template>
  <button type="button" class="back" title="返回" @click="back">
    <el-icon><ArrowLeft /></el-icon>
  </button>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 16px;
  cursor: pointer;
  flex: none;
}

.back:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}
</style>

<script setup lang="ts">
/** Title, one-line explanation and primary actions at the top of a page card. */
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div class="page-header">
    <div class="text">
      <div class="title">{{ title }}</div>
      <div v-if="description" class="description">{{ description }}</div>
    </div>
    <div class="actions"><slot /></div>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 20px;
}

.title {
  font-size: 18px;
  font-weight: 600;
  line-height: 28px;
  color: var(--el-text-color-primary);
}

.description {
  margin-top: 6px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.actions {
  display: flex;
  gap: 8px;
  flex: none;
  padding-top: 12px;
}
</style>

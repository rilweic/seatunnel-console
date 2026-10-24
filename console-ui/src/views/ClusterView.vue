<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { clusterApi } from '@/api/console'

const overview = ref<Record<string, string> | null>(null)
const loading = ref(false)
const failed = ref(false)

async function load() {
  loading.value = true
  failed.value = false
  try {
    overview.value = await clusterApi.overview()
  } catch {
    failed.value = true
    overview.value = null
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card v-loading="loading" shadow="never">
    <template #header>
      <div class="card-header">
        <span>Zeta 集群概览</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>
    </template>
    <el-result
      v-if="failed"
      icon="error"
      title="无法连接集群"
      sub-title="请检查 console.zeta.master-urls 和 Basic 认证配置"
    />
    <el-descriptions v-else-if="overview" :column="2" border>
      <el-descriptions-item v-for="(value, key) in overview" :key="key" :label="String(key)">
        {{ value }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>

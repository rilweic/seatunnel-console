<script setup lang="ts">
/**
 * "检查实时同步条件" of a datasource (design.md §12 P2, Q8 and Q30): binlog or logical replication,
 * the account's rights and, for PostgreSQL, free replication slots. The console only reports; each
 * failed item comes with the SQL or setting for the DBA.
 */
import { ref, watch } from 'vue'

import { datasourceApi, type CdcCheck } from '@/api/console'

const props = defineProps<{ datasource: { id: string; name: string } | null }>()
const visible = defineModel<boolean>({ required: true })

const checks = ref<CdcCheck[]>([])
const loading = ref(false)

const levelTag = { OK: 'success', WARN: 'warning', ERROR: 'danger' } as const
const levelText = { OK: '满足', WARN: '注意', ERROR: '不满足' } as const

async function run() {
  if (!props.datasource) return
  loading.value = true
  checks.value = []
  try {
    checks.value = await datasourceApi.cdcCheck(props.datasource.id)
  } finally {
    loading.value = false
  }
}

watch(visible, (open) => open && run())
</script>

<template>
  <el-dialog v-model="visible" :title="`检查实时同步条件：${datasource?.name ?? ''}`" width="720px">
    <div v-loading="loading" class="body">
      <div class="intro">实时同步要读取数据库的变更日志（MySQL binlog / PostgreSQL WAL）。下面是这个数据源现在的情况，表级的条件（如 REPLICA IDENTITY）在实时任务的预检查里按所选的表检查。</div>
      <ul class="checks">
        <li v-for="c in checks" :key="c.item">
          <el-tag :type="levelTag[c.level]" size="small">{{ levelText[c.level] }}</el-tag>
          <div class="text">
            <div><b>{{ c.item }}</b><span v-if="c.actual" class="actual">当前：{{ c.actual }}</span></div>
            <div class="message">{{ c.message }}</div>
            <pre v-if="c.fix" class="fix">{{ c.fix }}</pre>
          </div>
        </li>
      </ul>
    </div>
    <template #footer>
      <el-button :loading="loading" @click="run">重新检查</el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 120px;
}

.intro {
  margin-bottom: 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.checks {
  margin: 0;
  padding: 0;
  list-style: none;
}

.checks li {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  padding: 10px 0;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.text {
  flex: 1;
  min-width: 0;
  font-size: 13px;
}

.actual {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
}

.message {
  margin-top: 2px;
  color: var(--el-text-color-regular);
}

.fix {
  margin: 6px 0 0;
  padding: 8px 10px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
</style>

<script setup lang="ts">
/**
 * "检查同步条件" on the real-time jobs page (design.md §12 P2, Q8 and Q30): for a datasource that can
 * be read with CDC, binlog or logical replication, the account's rights and, for PostgreSQL, free
 * replication slots. The console only reports; each failed item comes with the SQL or setting for
 * the DBA.
 */
import { computed, ref, watch } from 'vue'

import { datasourceApi, type CdcCheck, type DatasourceView } from '@/api/console'
import DatasourceTypeIcon from '@/components/DatasourceTypeIcon.vue'
import { useDatasourceTypes } from '@/composables/useDatasourceTypes'

const visible = defineModel<boolean>({ required: true })

const { capabilities, ready } = useDatasourceTypes()
const datasources = ref<DatasourceView[]>([])
const datasourceId = ref<string | null>(null)
const datasource = computed(() => datasources.value.find((d) => d.id === datasourceId.value) ?? null)
const checks = ref<CdcCheck[]>([])
const loading = ref(false)

/** The datasources a real-time job can read from and the user may check (their own, or all for an admin). */
async function loadDatasources() {
  const [list] = await Promise.all([datasourceApi.list({ size: 200 }), ready])
  datasources.value = list.items.filter((d) => d.editable && capabilities(d.type)?.cdcConnector)
  if (!datasources.value.some((d) => d.id === datasourceId.value)) {
    datasourceId.value = datasources.value.length === 1 ? datasources.value[0]!.id : null
  }
}

const levelTag = { OK: 'success', WARN: 'warning', ERROR: 'danger' } as const
const levelText = { OK: '满足', WARN: '注意', ERROR: '不满足' } as const

async function run() {
  checks.value = []
  if (!datasourceId.value) return
  loading.value = true
  try {
    checks.value = await datasourceApi.cdcCheck(datasourceId.value)
  } finally {
    loading.value = false
  }
}

watch(visible, async (open) => {
  if (!open) return
  await loadDatasources()
  await run()
})
watch(datasourceId, () => visible.value && run())
</script>

<template>
  <el-dialog v-model="visible" title="检查实时同步条件" width="720px">
    <div class="intro">实时同步要读取源库的变更日志（MySQL binlog / PostgreSQL WAL）。选一个数据源查看它现在的情况；表级的条件（如 REPLICA IDENTITY）在任务的预检查里按所选的表检查，每次启动也会再查一遍。</div>
    <el-select v-model="datasourceId" placeholder="选择源数据源" filterable class="picker" no-data-text="没有可以实时读取的数据源">
      <el-option v-for="d in datasources" :key="d.id" :label="d.name" :value="d.id">
        <span class="option"><DatasourceTypeIcon :type="d.type" />{{ d.name }}<span class="address">{{ d.address }}</span></span>
      </el-option>
    </el-select>
    <div v-loading="loading" class="body">
      <el-empty v-if="!datasource && !loading" description="选择数据源后开始检查" :image-size="60" />
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
      <el-button :loading="loading" :disabled="!datasource" @click="run">重新检查</el-button>
      <el-button type="primary" @click="visible = false">关闭</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.body {
  min-height: 120px;
}

.picker {
  width: 100%;
  margin-bottom: 8px;
}

.option {
  display: inline-flex;
  gap: 6px;
  align-items: center;
}

.address {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
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

<script setup lang="ts">
/**
 * Deleting a real-time job (design.md §12 P2, Q9 and Q29): like an offline job, plus the
 * replication slot and publication it keeps on a PostgreSQL source, which hold WAL until dropped.
 * What could not be dropped comes back as SQL to run by hand.
 */
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'

import { streamApi } from '@/api/console'

const props = defineProps<{ jobId: string | null; jobName: string }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ deleted: [] }>()

const deleteRuns = ref(true)
const dropReplication = ref(true)
const deleting = ref(false)
const leftover = ref<string[]>([])

watch(visible, (open) => {
  if (open) {
    deleteRuns.value = true
    dropReplication.value = true
    leftover.value = []
  }
})

async function confirm() {
  if (!props.jobId) return
  deleting.value = true
  try {
    leftover.value = await streamApi.remove(props.jobId, deleteRuns.value, dropReplication.value)
    emit('deleted')
    if (leftover.value.length) {
      ElMessage.warning('任务已删除，但复制槽或 publication 没能删除，请让 DBA 执行下面的 SQL')
    } else {
      ElMessage.success('已删除')
      visible.value = false
    }
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="删除实时任务" width="540px">
    <template v-if="!leftover.length">
      <div class="message">删除实时任务「{{ jobName }}」？任务必须已经停止。</div>
      <el-checkbox v-model="dropReplication" class="option">同时删除源库上的复制槽和 publication（PostgreSQL 来源）</el-checkbox>
      <div class="tip">复制槽留在源库会一直占用 WAL，磁盘会被慢慢撑满。只在打算用同名任务接着同步时才保留。</div>
      <el-checkbox v-model="deleteRuns" class="option">同时删除启停历史、指标和归档日志</el-checkbox>
    </template>
    <template v-else>
      <div class="message">任务已删除。下面这些没能自动删除，请在源库执行：</div>
      <pre class="sql">{{ leftover.join('\n') }}</pre>
    </template>
    <template #footer>
      <template v-if="!leftover.length">
        <el-button @click="visible = false">取消</el-button>
        <el-button type="danger" :loading="deleting" @click="confirm">删除</el-button>
      </template>
      <el-button v-else type="primary" @click="visible = false">知道了</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.message {
  margin-bottom: 12px;
}

.option {
  display: flex;
  margin-top: 6px;
}

.tip {
  margin: 0 0 6px 24px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}

.sql {
  margin: 0;
  padding: 10px 12px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}
</style>

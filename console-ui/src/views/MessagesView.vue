<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { messageApi, type MessageView } from '@/api/console'
import PageHeader from '@/components/PageHeader.vue'
import { useQueryState } from '@/composables/useQueryState'
import { useMessageStore } from '@/stores/messages'
import { formatTime } from '@/utils/format'
import { categoryText, levelTag, levelText, messageCategories, messageLevels } from '@/utils/messages'

const router = useRouter()
const store = useMessageStore()
const rows = ref<MessageView[]>([])
const total = ref(0)
const loading = ref(false)
const { state: query, apply } = useQueryState(
  { unread: false, unresolved: false, level: '', category: '', page: 1, size: 20 },
  () => load(),
)

async function load() {
  loading.value = true
  try {
    const result = await messageApi.list(query)
    rows.value = result.items
    total.value = result.total
  } finally {
    loading.value = false
  }
  store.refresh()
}

async function markRead(m: MessageView) {
  if (m.read) return
  await messageApi.markRead(m.id)
  m.read = true
  store.refresh()
}

async function open(m: MessageView) {
  await markRead(m)
  if (m.link) router.push(m.link)
}

async function readAll() {
  await messageApi.markAllRead()
  ElMessage.success('已全部标为已读')
  load()
}

function status(m: MessageView): { text: string; type: 'success' | 'danger' | 'warning' } | null {
  if (!m.resolvable) return null
  if (m.resolvedAt) return { text: '已恢复', type: 'success' }
  return { text: '未解决', type: m.level === 'ERROR' ? 'danger' : 'warning' }
}

onMounted(load)
</script>

<template>
  <el-card shadow="never">
    <PageHeader
      title="消息中心"
      description="运行失败、实时任务异常、补数暂停和集群连不上等需要处理的事情。你会收到自己创建的任务的消息，管理员收到全部；同一个问题没解决之前合并成一条，之后成功运行或恢复正常会标为已恢复。"
    >
      <el-button :disabled="store.unread === 0" @click="readAll">全部已读</el-button>
    </PageHeader>
    <div class="toolbar">
      <el-radio-group v-model="query.unread" class="scope" @change="apply()">
        <el-radio-button :value="false">全部</el-radio-button>
        <el-radio-button :value="true">未读{{ store.unread ? ` (${store.unread > 99 ? "99+" : store.unread})` : "" }}</el-radio-button>
      </el-radio-group>
      <el-select v-model="query.level" placeholder="全部级别" clearable class="filter" @change="apply()">
        <el-option v-for="l in messageLevels" :key="l.value" :label="l.label" :value="l.value" />
      </el-select>
      <el-select v-model="query.category" placeholder="全部类别" clearable class="filter" @change="apply()">
        <el-option v-for="c in messageCategories" :key="c.value" :label="c.label" :value="c.value" />
      </el-select>
      <el-checkbox v-model="query.unresolved" @change="apply()">只看未解决</el-checkbox>
    </div>
    <el-table v-loading="loading" :data="rows" row-key="id">
      <el-table-column type="expand">
        <template #default="{ row }">
          <pre class="detail">{{ row.content }}</pre>
        </template>
      </el-table-column>
      <el-table-column label="级别" width="80">
        <template #default="{ row }">
          <el-tag :type="levelTag(row.level)" size="small" disable-transitions>{{ levelText(row.level) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="消息" min-width="360">
        <template #default="{ row }">
          <div class="message" :class="{ unread: !row.read }">
            <span v-if="!row.read" class="unread-dot" />
            <div class="message-text">
              <el-link :type="row.link ? 'primary' : 'default'" underline="never" class="title" @click="open(row)">{{ row.title }}</el-link>
              <div class="content">{{ row.content }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="140">
        <template #default="{ row }">{{ categoryText(row.category) }}</template>
      </el-table-column>
      <el-table-column label="次数" width="70" align="right">
        <template #default="{ row }">{{ row.repeatCount }}</template>
      </el-table-column>
      <el-table-column label="最近发生" width="170">
        <template #default="{ row }">
          {{ formatTime(row.lastAt) }}
          <div v-if="row.repeatCount > 1" class="sub">首次 {{ formatTime(row.createdAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <template v-if="status(row)">
            <el-tag :type="status(row)!.type" size="small" effect="plain" disable-transitions>{{ status(row)!.text }}</el-tag>
            <div v-if="row.resolvedAt" class="sub">{{ formatTime(row.resolvedAt).slice(5, 16) }}</div>
          </template>
          <span v-else class="sub">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="!row.link" @click="open(row)">查看</el-button>
          <el-button link type="primary" :disabled="row.read" @click="markRead(row)">已读</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination
      v-model:current-page="query.page"
      v-model:page-size="query.size"
      :page-sizes="[10, 20, 50, 100]"
      :total="total"
      layout="total, sizes, prev, pager, next"
      @size-change="apply()"
      @current-change="apply(false)"
    />
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.scope {
  flex: none;
}

.filter {
  width: 160px;
}

.message {
  display: flex;
  gap: 8px;
  min-width: 0;
}

.message-text {
  min-width: 0;
}

.unread-dot {
  flex: none;
  width: 6px;
  height: 6px;
  margin-top: 8px;
  border-radius: 50%;
  background: var(--el-color-primary);
}

.title {
  font-weight: normal;
}

.unread .title {
  font-weight: 600;
}

.content {
  overflow: hidden;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.detail {
  margin: 0;
  padding: 8px 16px 8px 48px;
  font-family: inherit;
  font-size: 13px;
  line-height: 20px;
  white-space: pre-wrap;
  word-break: break-all;
  color: var(--el-text-color-regular);
}
</style>

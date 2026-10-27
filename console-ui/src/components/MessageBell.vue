<script setup lang="ts">
/** Header bell: the unread count, and the latest messages one click away (docs/design.md §10.7). */
import { Bell } from '@element-plus/icons-vue'
import { ref } from 'vue'
import { useRouter } from 'vue-router'

import { messageApi, type MessageView } from '@/api/console'
import { useMessageStore } from '@/stores/messages'
import { shortTime } from '@/utils/messages'

const router = useRouter()
const store = useMessageStore()
const visible = ref(false)
const loading = ref(false)
const items = ref<MessageView[]>([])

async function load() {
  loading.value = true
  try {
    items.value = (await messageApi.list({ size: 8 })).items
  } finally {
    loading.value = false
  }
  store.refresh()
}

async function open(m: MessageView) {
  visible.value = false
  if (!m.read) {
    await messageApi.markRead(m.id)
    store.refresh()
  }
  router.push(m.link || '/messages')
}

async function readAll() {
  await messageApi.markAllRead()
  items.value = items.value.map((m) => ({ ...m, read: true }))
  store.refresh()
}

function all() {
  visible.value = false
  router.push('/messages')
}
</script>

<template>
  <el-popover v-model:visible="visible" trigger="click" placement="bottom-end" :width="400" popper-class="message-popper" @show="load">
    <template #reference>
      <span class="bell" title="消息" role="button" aria-label="消息">
        <el-badge :value="store.unread" :hidden="store.unread === 0" :max="99">
          <el-icon :size="18"><Bell /></el-icon>
        </el-badge>
      </span>
    </template>
    <div class="head">
      <span class="head-title">消息</span>
      <el-button link type="primary" :disabled="store.unread === 0" @click="readAll">全部已读</el-button>
    </div>
    <div v-loading="loading" class="list">
      <div v-for="m in items" :key="m.id" class="item" :class="{ unread: !m.read }" @click="open(m)">
        <span class="dot" :class="m.level.toLowerCase()" />
        <div class="body">
          <div class="title-row">
            <span class="title">{{ m.title }}</span>
            <span class="time">{{ shortTime(m.lastAt) }}</span>
          </div>
          <div class="content">{{ m.content }}</div>
          <div v-if="m.repeatCount > 1 || m.resolvedAt" class="meta">
            <span v-if="m.repeatCount > 1">共 {{ m.repeatCount }} 次</span>
            <span v-if="m.resolvedAt" class="resolved">已恢复</span>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && items.length === 0" description="暂无消息" :image-size="60" />
    </div>
    <div class="foot">
      <el-button link type="primary" @click="all">查看全部</el-button>
    </div>
  </el-popover>
</template>

<style scoped>
.bell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 6px;
  cursor: pointer;
  color: var(--el-text-color-regular);
}

.bell:hover {
  background: var(--el-fill-color-light);
  color: var(--el-color-primary);
}

.head,
.foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 4px 8px;
}

.foot {
  justify-content: center;
  padding: 8px 0 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.head-title {
  font-weight: 600;
}

.list {
  max-height: 440px;
  min-height: 80px;
  overflow-y: auto;
}

.item {
  display: flex;
  gap: 10px;
  padding: 10px 8px;
  border-radius: 6px;
  cursor: pointer;
}

.item:hover {
  background: var(--el-fill-color-light);
}

.dot {
  flex: none;
  width: 8px;
  height: 8px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--el-color-info);
}

.dot.error {
  background: var(--el-color-danger);
}

.dot.warn {
  background: var(--el-color-warning);
}

.body {
  flex: 1;
  min-width: 0;
}

.title-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
}

.title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-text-color-regular);
}

.unread .title {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.time {
  flex: none;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.content {
  display: -webkit-box;
  margin-top: 2px;
  overflow: hidden;
  font-size: 12px;
  line-height: 18px;
  color: var(--el-text-color-secondary);
  word-break: break-all;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.meta {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.resolved {
  color: var(--el-color-success);
}
</style>

<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

import JsonNode from './JsonNode.vue'

const props = defineProps<{ text: string }>()

const collapsed = ref<Set<string>>(new Set())
const fullscreen = ref(false)
let pageBodyOverflow = ''

const parsed = computed<{ value: unknown; error: string }>(() => {
  try {
    return { value: JSON.parse(props.text) as unknown, error: '' }
  } catch {
    return { value: undefined, error: '配置内容不是有效的 JSON，以下为原始文本' }
  }
})
const formatted = computed(() => (parsed.value.error ? props.text : JSON.stringify(parsed.value.value, null, 2)))
const containerPaths = computed(() => {
  const paths: string[] = []
  function visit(value: unknown, path: (string | number)[]) {
    if (value === null || typeof value !== 'object') return
    paths.push(JSON.stringify(path))
    const children = Array.isArray(value) ? value.map((child, key) => ({ key, value: child })) : Object.entries(value).map(([key, child]) => ({ key, value: child }))
    children.forEach((child) => visit(child.value, [...path, child.key]))
  }
  visit(parsed.value.value, [])
  return paths
})

watch(
  () => props.text,
  () => {
    collapsed.value = new Set()
  },
)

function toggle(path: string) {
  const next = new Set(collapsed.value)
  if (next.has(path)) next.delete(path)
  else next.add(path)
  collapsed.value = next
}

function setFullscreen(value: boolean) {
  if (fullscreen.value === value) return
  if (value) {
    pageBodyOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
  } else {
    document.body.style.overflow = pageBodyOverflow
  }
  fullscreen.value = value
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') setFullscreen(false)
}

async function copy() {
  try {
    await navigator.clipboard.writeText(formatted.value)
    ElMessage.success('已复制格式化 JSON')
  } catch {
    ElMessage.warning('复制失败，请手动选中复制')
  }
}

onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  if (fullscreen.value) document.body.style.overflow = pageBodyOverflow
})
</script>

<template>
  <section class="json-viewer" :class="{ 'page-fullscreen': fullscreen }">
    <div class="toolbar">
      <span class="format-label">JSON</span>
      <div class="actions">
        <el-button size="small" :disabled="!!parsed.error" @click="collapsed = new Set()">展开全部</el-button>
        <el-button size="small" :disabled="!!parsed.error" @click="collapsed = new Set(containerPaths)">收起全部</el-button>
        <el-button size="small" @click="copy">复制 JSON</el-button>
        <el-button size="small" @click="setFullscreen(!fullscreen)">{{ fullscreen ? '退出全屏' : '全屏查看' }}</el-button>
      </div>
    </div>

    <template v-if="parsed.error">
      <el-alert :title="parsed.error" type="warning" :closable="false" show-icon class="parse-error" />
      <pre class="raw">{{ text }}</pre>
    </template>
    <div v-else class="json-content">
      <JsonNode :value="parsed.value" :collapsed="collapsed" @toggle="toggle" />
    </div>
  </section>
</template>

<style scoped>
.json-viewer {
  overflow: hidden;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  background: #fafbfc;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: #fff;
}

.format-label {
  color: var(--el-text-color-secondary);
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  font-weight: 600;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

.json-content,
.raw {
  max-height: 420px;
  margin: 0;
  overflow: auto;
  padding: 12px 14px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
}

.raw {
  white-space: pre-wrap;
  word-break: break-all;
}

.parse-error {
  margin: 12px 12px 0;
  width: auto;
}

.json-viewer.page-fullscreen {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  flex-direction: column;
  box-sizing: border-box;
  padding: 16px;
  border: 0;
  border-radius: 0;
  background: #fff;
}

.page-fullscreen .toolbar {
  flex: none;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px 6px 0 0;
}

.page-fullscreen .json-content,
.page-fullscreen .raw {
  flex: 1;
  min-height: 0;
  max-height: none;
  border: 1px solid var(--el-border-color-lighter);
  border-top: 0;
  border-radius: 0 0 6px 6px;
  background: #fafbfc;
}

@media (max-width: 640px) {
  .toolbar {
    align-items: flex-start;
  }

  .actions {
    justify-content: flex-end;
  }
}
</style>

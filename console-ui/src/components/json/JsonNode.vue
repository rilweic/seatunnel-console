<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    value: unknown
    name?: string | number
    path?: (string | number)[]
    collapsed: Set<string>
    comma?: boolean
    selectableArrays?: boolean
    selectedPath?: string
  }>(),
  { path: () => [], comma: false, selectableArrays: false, selectedPath: '' },
)

const emit = defineEmits<{
  toggle: [path: string]
  select: [path: (string | number)[]]
}>()

const pathKey = computed(() => JSON.stringify(props.path))
const isContainer = computed(() => props.value !== null && typeof props.value === 'object')
const isArray = computed(() => Array.isArray(props.value))
const collapsedHere = computed(() => props.collapsed.has(pathKey.value))
const selectedHere = computed(() => isArray.value && props.selectedPath === pathKey.value)
const entries = computed<{ key: string | number; value: unknown }[]>(() => {
  if (isArray.value) return (props.value as unknown[]).map((value, key) => ({ key, value }))
  if (isContainer.value) return Object.entries(props.value as Record<string, unknown>).map(([key, value]) => ({ key, value }))
  return []
})
const openingBracket = computed(() => (isArray.value ? '[' : '{'))
const closingBracket = computed(() => (isArray.value ? ']' : '}'))
const displayValue = computed(() => (props.value === null ? 'null' : JSON.stringify(props.value)))
const valueKind = computed(() => (props.value === null ? 'null' : typeof props.value))

function childPath(key: string | number) {
  return [...props.path, key]
}
</script>

<template>
  <div class="json-node">
    <div class="json-line">
      <button
        v-if="isContainer"
        type="button"
        class="fold"
        :class="{ collapsed: collapsedHere }"
        :title="collapsedHere ? '展开' : '折叠'"
        :aria-label="`${collapsedHere ? '展开' : '折叠'}${name === undefined ? '根节点' : String(name)}`"
        :aria-expanded="!collapsedHere"
        @click="emit('toggle', pathKey)"
      >
        {{ collapsedHere ? '+' : '−' }}
      </button>
      <span v-else class="fold-space" />
      <template v-if="name !== undefined">
        <span class="json-key">{{ typeof name === 'string' ? JSON.stringify(name) : name }}</span>
        <span class="punctuation colon">:</span>
      </template>
      <template v-if="isContainer">
        <span class="punctuation">{{ openingBracket }}</span>
        <template v-if="collapsedHere">
          <span class="summary">{{ entries.length }} 项</span>
          <span class="punctuation">{{ closingBracket }}</span>
        </template>
      </template>
      <span v-else class="json-value" :class="valueKind">{{ displayValue }}</span>
      <el-button
        v-if="selectableArrays && isArray"
        link
        size="small"
        :type="selectedHere ? 'success' : 'primary'"
        class="select-array"
        @click.stop="emit('select', path)"
      >
        {{ selectedHere ? '已选为数据数组' : '选为数据数组' }}
      </el-button>
      <span v-if="comma && (!isContainer || collapsedHere)" class="punctuation">,</span>
    </div>

    <template v-if="isContainer">
      <div v-show="!collapsedHere" class="json-children">
        <JsonNode
          v-for="(entry, index) in entries"
          :key="JSON.stringify(childPath(entry.key))"
          :value="entry.value"
          :name="entry.key"
          :path="childPath(entry.key)"
          :collapsed="collapsed"
          :comma="index < entries.length - 1"
          :selectable-arrays="selectableArrays"
          :selected-path="selectedPath"
          @toggle="emit('toggle', $event)"
          @select="emit('select', $event)"
        />
      </div>
      <div v-show="!collapsedHere" class="json-line json-closing">
        <span class="fold-space" />
        <span class="punctuation">{{ closingBracket }}</span>
        <span v-if="comma" class="punctuation">,</span>
      </div>
    </template>
  </div>
</template>

<style scoped>
.json-line {
  display: flex;
  align-items: flex-start;
  min-height: 22px;
  line-height: 22px;
}

.fold,
.fold-space {
  flex: none;
  width: 18px;
  height: 18px;
  margin: 2px 4px 2px 0;
}

.fold {
  padding: 0;
  border: 1px solid #d4380d;
  border-radius: 4px;
  background: #fff;
  color: #cf1322;
  font: 16px/15px Arial, sans-serif;
  text-align: center;
  cursor: pointer;
  user-select: none;
}

.fold:hover,
.fold:focus-visible {
  border-color: #ff4d4f;
  background: #fff1f0;
  outline: none;
}

.fold.collapsed {
  border-color: var(--el-color-primary);
  color: var(--el-color-primary);
}

.fold.collapsed:hover,
.fold.collapsed:focus-visible {
  background: var(--el-color-primary-light-9);
}

.json-children {
  margin-left: 8px;
  padding-left: 12px;
  border-left: 1px solid var(--el-border-color-lighter);
}

.json-key {
  flex: none;
  color: #87348c;
}

.colon {
  margin-right: 7px;
}

.punctuation {
  color: var(--el-text-color-regular);
}

.summary {
  margin: 0 7px;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  user-select: none;
}

.select-array {
  margin-left: 12px;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
}

.json-value {
  min-width: 0;
  white-space: pre-wrap;
  word-break: break-all;
}

.json-value.string {
  color: #237a3b;
}

.json-value.number {
  color: #0958d9;
}

.json-value.boolean {
  color: #b76e00;
}

.json-value.null {
  color: var(--el-text-color-secondary);
}
</style>

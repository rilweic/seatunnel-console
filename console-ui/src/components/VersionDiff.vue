<script setup lang="ts">
/** Side-by-side choice of two versions of a job, shown as a unified line diff of config and params. */
import { computed, ref, watch } from 'vue'

import { jobApi, type VersionDetail, type VersionSummary } from '@/api/console'
import { diffLines, foldUnchanged } from '@/utils/diff'

const props = defineProps<{ jobId: string; versions: VersionSummary[]; leftId: string; rightId: string }>()
const visible = defineModel<boolean>({ required: true })

const left = ref(props.leftId)
const right = ref(props.rightId)
const details = ref<Record<string, VersionDetail>>({})
const loading = ref(false)

watch(
  () => [props.leftId, props.rightId],
  ([l, r]) => {
    left.value = l!
    right.value = r!
  },
)

watch(
  [left, right, visible],
  async () => {
    if (!visible.value) return
    loading.value = true
    try {
      for (const id of [left.value, right.value]) {
        if (id && !details.value[id]) details.value[id] = await jobApi.version(props.jobId, id)
      }
    } finally {
      loading.value = false
    }
  },
  { immediate: true },
)

/** What is compared: the config, then the job parameters sorted by name. */
function text(v: VersionDetail | undefined): string {
  if (!v) return ''
  const params = Object.entries(v.params ?? {}).sort(([a], [b]) => a.localeCompare(b))
  return v.hocon.trimEnd() + (params.length ? '\n\n# 任务参数\n' + params.map(([k, val]) => `${k} = ${val}`).join('\n') : '')
}

const lines = computed(() => diffLines(text(details.value[left.value]), text(details.value[right.value])))
const shown = computed(() => foldUnchanged(lines.value))
const stats = computed(() => ({
  add: lines.value.filter((l) => l.kind === 'add').length,
  del: lines.value.filter((l) => l.kind === 'del').length,
}))
const label = (v: VersionSummary) => `v${v.versionNo}${v.remark ? ' · ' + v.remark : ''}`
</script>

<template>
  <el-dialog v-model="visible" title="版本对比" width="960px" top="6vh">
    <div class="bar">
      <el-select v-model="left" class="pick">
        <el-option v-for="v in versions" :key="v.id" :label="label(v)" :value="v.id" />
      </el-select>
      <span class="arrow">→</span>
      <el-select v-model="right" class="pick">
        <el-option v-for="v in versions" :key="v.id" :label="label(v)" :value="v.id" />
      </el-select>
      <span class="stats"><span class="plus">+{{ stats.add }}</span> <span class="minus">-{{ stats.del }}</span></span>
    </div>
    <div v-loading="loading" class="diff">
      <div v-if="!loading && stats.add + stats.del === 0" class="empty">两个版本的配置和任务参数完全相同</div>
      <template v-else>
        <div v-for="(l, i) in shown" :key="i" class="line" :class="l.kind">
          <template v-if="l.kind === 'fold'">
            <span class="fold">⋯ {{ l.count }} 行未改动</span>
          </template>
          <template v-else>
            <span class="no">{{ l.left ?? '' }}</span>
            <span class="no">{{ l.right ?? '' }}</span>
            <span class="sign">{{ l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' ' }}</span>
            <span class="text">{{ l.text }}</span>
          </template>
        </div>
      </template>
    </div>
  </el-dialog>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.pick {
  width: 300px;
}

.arrow {
  color: var(--el-text-color-secondary);
}

.stats {
  margin-left: auto;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
}

.plus {
  color: var(--el-color-success-dark-2);
}

.minus {
  color: var(--el-color-danger);
}

.diff {
  max-height: 65vh;
  min-height: 120px;
  overflow: auto;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 20px;
}

.line {
  display: flex;
  white-space: pre-wrap;
  word-break: break-all;
}

.line.add {
  background: #f6ffed;
}

.line.del {
  background: #fff1f0;
}

.line.fold {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-secondary);
}

.no {
  flex: none;
  width: 40px;
  padding-right: 8px;
  text-align: right;
  color: var(--el-text-color-placeholder);
  user-select: none;
}

.sign {
  flex: none;
  width: 16px;
  color: var(--el-text-color-secondary);
  user-select: none;
}

.add .sign {
  color: var(--el-color-success-dark-2);
}

.del .sign {
  color: var(--el-color-danger);
}

.fold {
  padding-left: 104px;
}

.empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
}
</style>

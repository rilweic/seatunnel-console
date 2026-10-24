<script setup lang="ts">
/**
 * Takes new fields out of a synced field (docs/design.md §7.10): JSON paths, regex groups or the
 * parts between a separator. A value that cannot be extracted becomes null.
 */
import { Delete, Plus } from '@element-plus/icons-vue'
import { computed, reactive, ref, watch } from 'vue'

import type { Extract, ExtractOutput } from '@/api/console'
import { FIELD_TYPES } from '@/utils/fieldProcessing'

const props = defineProps<{
  extract: Extract | null
  /** synced fields to extract from */
  fields: string[]
  /** primary keys, which must stay in the target */
  keys: string[]
  /** names already used by the table's target fields, other than this extract's */
  taken: string[]
}>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ apply: [extract: Extract] }>()

const e = reactive<Extract>({ source: '', kind: 'JSON', pattern: '', outputs: [], dropSource: false })
const sample = ref('')

watch(visible, (open) => {
  if (!open) return
  const x = props.extract
  Object.assign(e, {
    source: x?.source ?? '',
    kind: x?.kind ?? 'JSON',
    pattern: x?.pattern ?? '',
    outputs: (x?.outputs ?? [{ name: '', path: '$.' }]).map((o) => ({ ...o })),
    dropSource: x?.dropSource ?? false,
  })
  sample.value = ''
})

/** Capturing groups of the regex, or null when it does not compile. */
const groups = computed(() => {
  if (e.kind !== 'REGEX' || !e.pattern) return null
  try {
    return new RegExp(`${e.pattern}|`).exec('')!.length - 1
  } catch {
    return null
  }
})

// a regex has one new field per group
watch(groups, (n) => {
  if (n == null || n < 1) return
  while (e.outputs.length < n) e.outputs.push({ name: '' })
  e.outputs.splice(n)
})

function addOutput() {
  e.outputs.push(e.kind === 'JSON' ? { name: '', path: '$.' } : { name: '' })
}

/** What the sample value gives each new field, done the way the transform does it. */
const preview = computed<(string | null)[]>(() => {
  const v = sample.value
  if (!v) return []
  try {
    if (e.kind === 'SPLIT') {
      const parts = e.pattern ? v.split(e.pattern) : [v]
      const n = e.outputs.length
      // like Java's split with a limit: the last field takes the rest
      return e.outputs.map((_, i) => (i < n - 1 ? parts[i] ?? null : parts.slice(n - 1).join(e.pattern ?? '') || null))
    }
    if (e.kind === 'REGEX') {
      const m = new RegExp(e.pattern ?? '').exec(v)
      return e.outputs.map((_, i) => (m ? m[i + 1] ?? null : null))
    }
    const json = JSON.parse(v)
    return e.outputs.map((o) => {
      const keys = (o.path ?? '').replace(/^\$\.?/, '').split(/\.|\[['"]?|['"]?\]/).filter(Boolean)
      let cur: unknown = json
      for (const k of keys) cur = cur == null ? null : (cur as Record<string, unknown>)[k]
      return cur == null ? null : typeof cur === 'object' ? JSON.stringify(cur) : String(cur)
    })
  } catch {
    return e.outputs.map(() => null)
  }
})

const problem = computed(() => {
  if (!e.source) return '请选择来源字段'
  if (e.kind !== 'JSON' && !e.pattern) return e.kind === 'REGEX' ? '请填写正则' : '请填写分隔符'
  if (e.kind === 'REGEX' && groups.value == null) return '正则写法不对'
  if (e.kind === 'REGEX' && groups.value === 0) return '正则里要用括号标出要提取的部分，每对括号一个新字段'
  if (!e.outputs.length) return '至少要有一个新字段'
  const names = e.outputs.map((o) => o.name.trim())
  if (names.some((n) => !n)) return '请填写每个新字段的名字'
  if (names.some((n) => /[\s.$`"'\\]/.test(n))) return '字段名不能包含空格、点、引号、$ 或反斜杠'
  const lower = names.map((n) => n.toLowerCase())
  if (new Set(lower).size < lower.length || lower.some((n) => props.taken.some((t) => t.toLowerCase() === n))) return '新字段名和已有字段重复'
  if (e.kind === 'JSON' && e.outputs.some((o) => !o.path?.trim().startsWith('$'))) return 'JSON 路径要以 $ 开头，如 $.address.city'
  return null
})

function apply() {
  emit('apply', {
    source: e.source,
    kind: e.kind,
    pattern: e.kind === 'JSON' ? null : e.pattern,
    outputs: e.outputs.map((o): ExtractOutput => ({
      name: o.name.trim(),
      path: e.kind === 'JSON' ? o.path?.trim() : null,
      type: o.type || null,
      precision: o.type === 'DECIMAL' ? o.precision ?? 18 : null,
      scale: o.type === 'DECIMAL' ? o.scale ?? 2 : null,
    })),
    dropSource: e.dropSource,
  })
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" :title="extract ? '编辑提取' : '提取字段'" width="680px" append-to-body>
    <el-form label-width="96px">
      <el-form-item label="来源字段">
        <el-select v-model="e.source" filterable class="w200">
          <el-option v-for="c in fields" :key="c" :label="c" :value="c" />
        </el-select>
        <el-checkbox v-model="e.dropSource" :disabled="keys.includes(e.source)" class="ml">提取后不同步来源字段</el-checkbox>
      </el-form-item>
      <el-form-item label="提取方式">
        <el-radio-group v-model="e.kind">
          <el-radio-button value="JSON">JSON 路径</el-radio-button>
          <el-radio-button value="REGEX">正则</el-radio-button>
          <el-radio-button value="SPLIT">按分隔符拆分</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="e.kind === 'REGEX'" label="正则">
        <el-input v-model="e.pattern" class="code" placeholder="如 ^(\w+)@(\w+)\.com$，每对括号提取一个字段" />
        <div class="tip">有 {{ groups ?? '?' }} 对括号，就提取 {{ groups ?? '?' }} 个新字段；匹配不上时新字段为空</div>
      </el-form-item>
      <el-form-item v-if="e.kind === 'SPLIT'" label="分隔符">
        <el-input v-model="e.pattern" class="w200" placeholder="如 , 或 |" />
        <div class="tip">按顺序拆成下面几个字段；段数不够的字段为空，多出来的都放进最后一个字段</div>
      </el-form-item>

      <el-form-item label="新字段">
        <div class="outputs">
          <div v-for="(o, i) in e.outputs" :key="i" class="output">
            <el-input v-model="o.name" placeholder="字段名" class="w160" />
            <el-input v-if="e.kind === 'JSON'" v-model="o.path" placeholder="$.address.city" class="code path" />
            <el-select v-model="o.type" clearable placeholder="字符串" class="w120">
              <el-option v-for="t in FIELD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <span v-if="preview.length" class="pv">{{ preview[i] ?? '空' }}</span>
            <el-button v-if="e.kind !== 'REGEX'" link type="danger" :icon="Delete" :disabled="e.outputs.length < 2" @click="e.outputs.splice(i, 1)" />
          </div>
          <el-button v-if="e.kind !== 'REGEX'" :icon="Plus" size="small" class="add" @click="addOutput">再加一个</el-button>
          <div class="tip">类型不选就是字符串；转换失败的值为空</div>
        </div>
      </el-form-item>
      <el-form-item label="试一下">
        <el-input v-model="sample" :type="e.kind === 'JSON' ? 'textarea' : 'text'" :rows="2" class="code"
          :placeholder="e.kind === 'JSON' ? '粘一段 JSON，如 {&quot;address&quot;: {&quot;city&quot;: &quot;上海&quot;}}' : '输入一个示例值，结果显示在每个新字段后面'" />
      </el-form-item>
    </el-form>
    <template #footer>
      <span v-if="problem" class="problem">{{ problem }}</span>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="!!problem" @click="apply">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.w120 {
  width: 120px;
}

.w160 {
  width: 160px;
}

.w200 {
  width: 200px;
}

.ml {
  margin-left: 12px;
}

.outputs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.output {
  display: flex;
  align-items: center;
  gap: 8px;
}

.path {
  flex: 1;
}

.pv {
  max-width: 140px;
  overflow: hidden;
  color: var(--el-color-primary);
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.add {
  align-self: flex-start;
}

.tip {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.code :deep(input),
.code :deep(textarea) {
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 13px;
}

.problem {
  margin-right: 12px;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>

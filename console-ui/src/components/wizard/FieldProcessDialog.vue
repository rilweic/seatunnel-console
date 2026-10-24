<script setup lang="ts">
/**
 * Processing of one synced field (docs/design.md §7.10): replace, mask and type conversion,
 * applied in that order. A primary key is never processed: the target writes by it.
 */
import { computed, reactive, ref, watch } from 'vue'

import type { ColumnMeta, FieldProcess, FieldType, MaskPreset } from '@/api/console'
import { FIELD_TYPES, MASKS, maskPreview } from '@/utils/fieldProcessing'

const props = defineProps<{ column: ColumnMeta | null; process: FieldProcess | null | undefined }>()
const visible = defineModel<boolean>({ required: true })
const emit = defineEmits<{ apply: [process: FieldProcess | null] }>()

const on = reactive({ replace: false, mask: false, cast: false })
const replace = reactive({ find: '', replacement: '', regex: false })
const mask = reactive({ preset: 'PHONE' as MaskPreset, keepFirst: 2, keepLast: 2, maskChar: '*' })
const cast = reactive({ type: 'STRING' as FieldType, precision: 18, scale: 2 })
const sample = ref('')

watch(visible, (open) => {
  if (!open) return
  const p = props.process ?? {}
  on.replace = !!p.replace?.find
  on.mask = !!p.mask?.preset
  on.cast = !!p.cast?.type
  Object.assign(replace, { find: p.replace?.find ?? '', replacement: p.replace?.replacement ?? '', regex: !!p.replace?.regex })
  Object.assign(mask, {
    preset: p.mask?.preset ?? 'PHONE',
    keepFirst: p.mask?.keepFirst ?? 2,
    keepLast: p.mask?.keepLast ?? 2,
    maskChar: p.mask?.maskChar || '*',
  })
  Object.assign(cast, { type: p.cast?.type ?? 'STRING', precision: p.cast?.precision ?? 18, scale: p.cast?.scale ?? 2 })
  sample.value = MASKS.find((m) => m.value === mask.preset)!.example
})

watch(() => mask.preset, (preset) => (sample.value = MASKS.find((m) => m.value === preset)!.example))

const locked = computed(() => !!props.column?.primaryKey)

/** The sample after replace and mask, as the job would write it. */
const preview = computed(() => {
  let v = sample.value
  if (on.replace && replace.find) {
    try {
      // JavaScript and Java both read $1 in the replacement as a group
      v = replace.regex ? v.replace(new RegExp(replace.find, 'g'), replace.replacement) : v.split(replace.find).join(replace.replacement)
    } catch {
      return '正则写法不对'
    }
  }
  return on.mask ? maskPreview(v, mask.preset, mask.keepFirst, mask.keepLast, mask.maskChar) : v
})

function apply() {
  const process: FieldProcess = {
    replace: on.replace && replace.find ? { ...replace } : null,
    mask: on.mask ? { preset: mask.preset, keepFirst: mask.keepFirst, keepLast: mask.keepLast, maskChar: mask.maskChar || '*' } : null,
    cast: on.cast ? { type: cast.type, precision: cast.type === 'DECIMAL' ? cast.precision : null, scale: cast.type === 'DECIMAL' ? cast.scale : null } : null,
    encrypt: props.process?.encrypt ?? null,
  }
  emit('apply', process.replace || process.mask || process.cast || process.encrypt ? process : null)
  visible.value = false
}
</script>

<template>
  <el-dialog v-model="visible" :title="`字段处理 · ${column?.name ?? ''}`" width="640px" append-to-body>
    <el-alert v-if="locked" type="warning" :closable="false" show-icon class="locked"
      title="这是主键字段：目标表按主键写入，主键的值和类型要保持不变，不能处理" />
    <template v-else>
      <div class="hint">按 值替换 → 脱敏 → 类型转换 的顺序执行。源字段类型：{{ column?.type }}</div>

      <section class="block">
        <el-checkbox v-model="on.replace" class="title">值替换</el-checkbox>
        <div v-if="on.replace" class="body">
          <div class="row">
            <el-input v-model="replace.find" :placeholder="replace.regex ? '正则，如 \\s+' : '要替换的文字'" />
            <span class="arrow">→</span>
            <el-input v-model="replace.replacement" placeholder="替换成（留空表示删除）" />
          </div>
          <el-checkbox v-model="replace.regex">按正则匹配<span class="tip">（替换成里可以用 $1 引用分组）</span></el-checkbox>
        </div>
      </section>

      <section class="block">
        <el-checkbox v-model="on.mask" class="title">脱敏</el-checkbox>
        <div v-if="on.mask" class="body">
          <el-radio-group v-model="mask.preset" size="small">
            <el-radio-button v-for="m in MASKS" :key="m.value" :value="m.value">{{ m.label }}</el-radio-button>
          </el-radio-group>
          <div v-if="mask.preset === 'CUSTOM'" class="row">
            保留前 <el-input-number v-model="mask.keepFirst" :min="0" :max="50" size="small" controls-position="right" class="num" />
            位、后 <el-input-number v-model="mask.keepLast" :min="0" :max="50" size="small" controls-position="right" class="num" />
            位，其余用
            <el-input v-model="mask.maskChar" maxlength="1" size="small" class="ch" />
            代替
          </div>
          <div class="tip">长度不够的值原样保留；结果是文本。</div>
        </div>
      </section>

      <section class="block">
        <el-checkbox v-model="on.cast" class="title">类型转换</el-checkbox>
        <div v-if="on.cast" class="body">
          <div class="row">
            转为
            <el-select v-model="cast.type" class="type">
              <el-option v-for="t in FIELD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
            <template v-if="cast.type === 'DECIMAL'">
              总位数 <el-input-number v-model="cast.precision" :min="1" :max="38" size="small" controls-position="right" class="num" />
              小数位 <el-input-number v-model="cast.scale" :min="0" :max="cast.precision" size="small" controls-position="right" class="num" />
            </template>
          </div>
          <div class="tip warn">转换失败的值会变成空（null），不会让任务失败。</div>
        </div>
      </section>

      <div v-if="on.replace || on.mask" class="preview">
        试一下：<el-input v-model="sample" size="small" class="sample" /> → <b>{{ preview }}</b>
      </div>
    </template>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :disabled="locked" @click="apply">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.locked {
  margin-bottom: 8px;
}

.hint {
  margin-bottom: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.block {
  margin-bottom: 10px;
  padding: 10px 14px;
  background: #f8fafc;
  border: 1px solid #edf1f6;
  border-radius: 8px;
}

.title {
  font-weight: 500;
}

.body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}

.row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-regular);
  font-size: 13px;
}

.row > .el-input {
  flex: 1;
  min-width: 160px;
}

.arrow {
  color: var(--el-text-color-placeholder);
}

.num {
  width: 90px;
}

.ch {
  width: 48px;
}

.type {
  width: 140px;
}

.tip {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.warn {
  color: var(--el-color-warning-dark-2);
}

.preview {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}

.preview b {
  color: var(--el-color-primary);
  font-weight: 500;
}

.sample {
  width: 220px;
}
</style>

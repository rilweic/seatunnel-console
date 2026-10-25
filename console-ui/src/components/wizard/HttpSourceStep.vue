<script setup lang="ts">
/**
 * Step ① for an HTTP source (design.md §7.13): the request and the fields confirmed from a preview
 * of its first page. The job has one table, named after the last path segment.
 */
import { ref, watch } from 'vue'

import { jobApi, type HttpSourceSpec, type WizardForm } from '@/api/console'

import type { SourceContext } from './connectors'
import HttpSourcePanel from './HttpSourcePanel.vue'

const props = defineProps<{ form: WizardForm; context: SourceContext }>()
/** The table when no primary key is chosen: rows can then only be appended. */
const emit = defineEmits<{ keyless: [names: string[]] }>()

const previewText = ref('')
const previewStatus = ref<number | null>(null)
const previewElapsed = ref<number | null>(null)
const previewLoading = ref(false)

const spec = () => props.form.source.spec as HttpSourceSpec | null | undefined

async function preview() {
  if (!spec()) return
  previewLoading.value = true
  try {
    const result = await jobApi.httpPreview(props.context.jobId, { form: props.context.payload(), params: props.context.params() })
    previewText.value = result.body
    previewStatus.value = result.statusCode
    previewElapsed.value = result.elapsedMillis
  } finally {
    previewLoading.value = false
  }
}

watch(
  () => [spec()?.path, spec()?.method, spec()?.params, spec()?.body, spec()?.paging],
  () => {
    previewText.value = ''
    previewStatus.value = null
    previewElapsed.value = null
  },
  { deep: true },
)

watch(
  () => [spec()?.fields, spec()?.path, spec()?.primaryKeys] as const,
  (_current, previous) => {
    const http = spec()
    if (!http) return
    const old = props.form.source.tables[0]
    const keyless = () => emit('keyless', (http.primaryKeys?.length ?? 0) === 0 ? props.form.source.tables.map((t) => t.name) : [])
    if (!previous && old) {
      // opening a saved form: the table is as it was saved
      keyless()
      return
    }
    const name = http.path.split('/').filter(Boolean).pop() || 'http_data'
    // An HTTP job has exactly one table, so its settings (blocked fields, renames, processing) stay
    // when the path and with it the table name changes, even while the path is being typed.
    const previousColumns = old ? old.columns : undefined
    const available = http.fields.map((f) => f.name)
    const oldFieldNames = new Set((previous?.[0] ?? []).map((field) => field.name))
    const added = available.filter((field) => !oldFieldNames.has(field))
    const columns = old && previousColumns == null
      ? previousColumns
      : previousColumns
        ? [
            ...previousColumns.filter((field) => available.includes(field)),
            ...added.filter((field) => !previousColumns.includes(field)),
          ]
        : available
    props.form.source.tables = [{
      ...(old ?? {}),
      name,
      columns,
      filter: null,
      incrementalField: null,
    }]
    keyless()
  },
  { deep: true, immediate: true },
)
</script>

<template>
  <HttpSourcePanel
    v-if="form.source.spec"
    v-model="form.source.spec"
    :loading="previewLoading"
    :preview-text="previewText"
    :preview-status="previewStatus"
    :preview-elapsed="previewElapsed"
    @preview="preview"
  />
</template>

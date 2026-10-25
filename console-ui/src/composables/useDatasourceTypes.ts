import { ref } from 'vue'

import { datasourceApi, type DatasourceCapabilities, type DatasourceTypeView } from '@/api/console'

const types = ref<DatasourceTypeView[]>([])
let loading: Promise<void> | null = null

/**
 * The datasource types and what each can do, loaded once from the server, so pages ask about
 * capabilities instead of comparing type names (design.md §6.1).
 */
export function useDatasourceTypes() {
  if (!loading) {
    loading = datasourceApi.types().then((list) => {
      types.value = list
    }).catch((e) => {
      loading = null
      throw e
    })
  }

  function capabilities(type: string | null | undefined): DatasourceCapabilities | null {
    return types.value.find((t) => t.type === type)?.capabilities ?? null
  }

  function label(type: string | null | undefined) {
    return capabilities(type)?.label ?? type ?? ''
  }

  return { types, ready: loading, capabilities, label }
}

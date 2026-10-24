import { reactive, watch } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'

type Value = string | number | boolean

/**
 * Keeps a list page's filters and page number in the URL query, so refresh, back and copied links
 * show the same list. Values equal to their default stay out of the URL.
 *
 * The URL is the single source: change `state`, call `apply()`, and `onChange` runs once the URL
 * has changed. It also runs when the page is opened again with another query (the side menu, a
 * link from another page), since the layout keeps the page mounted across query changes.
 */
export function useQueryState<T extends Record<string, Value>>(defaults: T, onChange: () => void) {
  const route = useRoute()
  const router = useRouter()
  const path = route.path
  const state = reactive({ ...defaults }) as T
  const values = state as Record<string, Value>

  function read(query: LocationQuery) {
    for (const [key, fallback] of Object.entries(defaults)) {
      const raw = query[key]
      const text = Array.isArray(raw) ? raw[0] : raw
      let value: Value = fallback
      if (typeof text === 'string') {
        if (typeof fallback === 'number') {
          const n = Number(text)
          value = Number.isFinite(n) && n > 0 ? n : fallback
        } else if (typeof fallback === 'boolean') {
          value = text === 'true' ? true : text === 'false' ? false : fallback
        } else {
          value = text
        }
      }
      values[key] = value
    }
  }

  /** Writes the state to the URL. A changed filter starts again from page 1. */
  function apply(resetPage = true) {
    if (resetPage && 'page' in defaults) {
      values.page = 1
    }
    const query: Record<string, string> = {}
    for (const [key, fallback] of Object.entries(defaults)) {
      const value = values[key]
      if (value !== fallback && value !== '' && value !== null && value !== undefined) {
        query[key] = String(value)
      }
    }
    router.replace({ query })
  }

  read(route.query)
  watch(
    () => route.query,
    (query) => {
      // Leaving the page also changes the query; the page is about to unmount then.
      if (route.path !== path) return
      read(query)
      onChange()
    },
  )
  return { state, apply }
}

import { ref } from 'vue'
import type { RouteLocationNormalized, Router } from 'vue-router'

/** One step of the header's breadcrumb; the last one is the current page and has no link. */
export interface Crumb {
  title: string
  to?: string
}

const crumbs = ref<Crumb[]>([])

/** Last URL of each list page, filters and page number included, so going up returns to it. */
const lastListUrl = new Map<string, string>()

/**
 * Detail pages set their breadcrumb once they know what they show (a job's name, a run's job).
 * The layout clears it whenever another page or record opens; pages without one show their title.
 */
export function setBreadcrumb(items: Crumb[]) {
  crumbs.value = items
}

export function useBreadcrumbState() {
  return crumbs
}

/** A list page's URL as the user last left it, e.g. "/jobs?mine=false&page=2". */
export function listUrl(path: string): string {
  return lastListUrl.get(path) ?? path
}

/** Remembers list URLs; installed once on the router. */
export function trackListUrls(router: Router) {
  router.afterEach((to: RouteLocationNormalized) => {
    if (to.meta.list) {
      lastListUrl.set(to.path, to.fullPath)
    }
  })
}

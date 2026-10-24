import type { ParamRow } from '@/components/ParamsEditor.vue'

export function toMap(rows: ParamRow[]): Record<string, string> {
  return Object.fromEntries(rows.filter((r) => r.key.trim()).map((r) => [r.key.trim(), r.value]))
}

export function toRows(map: Record<string, string> | null | undefined): ParamRow[] {
  return Object.entries(map ?? {}).map(([key, value]) => ({ key, value }))
}

/** "2026-10-24T09:30:00" in local time, the format the backend expects. */
export function nowText(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** The message of a failed API call, for inline errors. */
export function errorMessage(e: unknown, fallback: string): string {
  return (e as { response?: { data?: { message?: string } } }).response?.data?.message ?? fallback
}

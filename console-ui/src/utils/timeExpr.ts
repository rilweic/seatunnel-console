/*
 * Builds, reads back and previews the time expressions a parameter value can hold (docs/design.md
 * §8). The server evaluates them (TimeExpression); this only covers what the parameter editor's
 * picker can produce, and a value it cannot read back stays a hand-written expression.
 */

export type TimeKind = 'offset' | 'monthFirst' | 'monthLast' | 'weekFirst' | 'weekLast'
export type TimeUnit = 'day' | 'hour' | 'month'

export interface TimeSpec {
  kind: TimeKind
  /** signed: negative is earlier than the scheduled time */
  amount: number
  /** for 'offset' only; month and week kinds move by months and weeks */
  unit: TimeUnit
  format: string
}

export const TIME_FORMATS = [
  { value: 'yyyyMMdd', label: 'yyyyMMdd（20261024）' },
  { value: 'yyyy-MM-dd', label: 'yyyy-MM-dd（2026-10-24）' },
  { value: 'yyyy-MM-dd HH:mm:ss', label: 'yyyy-MM-dd HH:mm:ss' },
  { value: 'yyyyMMddHH', label: 'yyyyMMddHH（2026102402）' },
  { value: 'yyyyMM', label: 'yyyyMM（202610）' },
  { value: 'yyyy-MM', label: 'yyyy-MM（2026-10）' },
  { value: 'HH', label: 'HH（小时，02）' },
]

export const BUILTINS = [
  { name: 'system.biz.date', label: '业务日期', help: '计划时间的前一天，yyyyMMdd' },
  { name: 'system.biz.curdate', label: '计划日期', help: '计划时间当天，yyyyMMdd' },
  { name: 'system.datetime', label: '计划时间', help: 'yyyyMMddHHmmss' },
  { name: 'system.task.instance.id', label: '运行实例 ID', help: '每次运行不同' },
  { name: 'system.task.definition.name', label: '任务名称', help: '' },
  { name: 'system.project.name', label: '项目名称', help: '' },
]

const FUNCTIONS: Record<Exclude<TimeKind, 'offset'>, string> = {
  monthFirst: 'month_first_day',
  monthLast: 'month_last_day',
  weekFirst: 'week_first_day',
  weekLast: 'week_last_day',
}

export function buildTime(spec: TimeSpec): string {
  const n = Math.trunc(spec.amount) || 0
  if (spec.kind !== 'offset') return `$[${FUNCTIONS[spec.kind]}(${spec.format},${n})]`
  if (spec.unit === 'month') return `$[add_months(${spec.format},${n})]`
  if (n === 0) return `$[${spec.format}]`
  const sign = n < 0 ? '-' : '+'
  return `$[${spec.format}${sign}${Math.abs(n)}${spec.unit === 'hour' ? '/24' : ''}]`
}

const knownFormat = (f: string) => TIME_FORMATS.some((x) => x.value === f)

/** The picker's settings for a value it produced; null for anything else. */
export function readTime(value: string): TimeSpec | null {
  const m = /^\$\[(.+)]$/.exec(value.trim())
  if (!m) return null
  const body = m[1]
  const fn = /^(\w+)\(([^,]+),\s*(-?\d+)\)$/.exec(body)
  if (fn) {
    const format = fn[2].trim()
    if (!knownFormat(format)) return null
    const amount = Number(fn[3])
    if (fn[1] === 'add_months') return { kind: 'offset', unit: 'month', amount, format }
    const kind = (Object.keys(FUNCTIONS) as (keyof typeof FUNCTIONS)[]).find((k) => FUNCTIONS[k] === fn[1])
    return kind ? { kind, unit: 'day', amount, format } : null
  }
  const offset = /^(.*?)([+-])(\d+)(\/24)?$/.exec(body)
  if (offset && knownFormat(offset[1])) {
    const amount = Number(offset[3]) * (offset[2] === '-' ? -1 : 1)
    return { kind: 'offset', unit: offset[4] ? 'hour' : 'day', amount, format: offset[1] }
  }
  return knownFormat(body) ? { kind: 'offset', unit: 'day', amount: 0, format: body } : null
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0')

function format(fmt: string, d: Date): string {
  return fmt
    .replace('yyyy', String(d.getFullYear()))
    .replace('MM', pad(d.getMonth() + 1))
    .replace('dd', pad(d.getDate()))
    .replace('HH', pad(d.getHours()))
    .replace('mm', pad(d.getMinutes()))
    .replace('ss', pad(d.getSeconds()))
}

/** What the picker's value becomes for a run scheduled at {@code base}. */
export function previewTime(spec: TimeSpec, base: Date): string {
  const d = new Date(base)
  const n = Math.trunc(spec.amount) || 0
  switch (spec.kind) {
    case 'offset':
      if (spec.unit === 'day') d.setDate(d.getDate() + n)
      else if (spec.unit === 'hour') d.setHours(d.getHours() + n)
      else d.setMonth(d.getMonth() + n)
      break
    case 'monthFirst':
      d.setDate(1)
      d.setMonth(d.getMonth() + n)
      break
    case 'monthLast':
      d.setDate(1)
      d.setMonth(d.getMonth() + n + 1, 0)
      break
    case 'weekFirst':
    case 'weekLast': {
      const monday = (d.getDay() + 6) % 7
      d.setDate(d.getDate() - monday + n * 7 + (spec.kind === 'weekLast' ? 6 : 0))
      break
    }
  }
  return format(spec.format, d)
}

/** Builtin variables' values for a run scheduled at {@code base}; null when only known at run time. */
export function previewBuiltin(name: string, base: Date): string | null {
  const day = new Date(base)
  switch (name) {
    case 'system.biz.date':
      day.setDate(day.getDate() - 1)
      return format('yyyyMMdd', day)
    case 'system.biz.curdate':
      return format('yyyyMMdd', base)
    case 'system.datetime':
      return format('yyyyMMddHHmmss', base)
    default:
      return null
  }
}

export function formatBase(base: Date): string {
  return format('yyyy-MM-dd HH:mm', base)
}

const NAME = /^[A-Za-z_][A-Za-z0-9_.]*$/

/** What is wrong with a parameter name among all names, or null. */
export function paramNameProblem(name: string, all: string[]): string | null {
  const key = name.trim()
  if (!key) return null
  if (!NAME.test(key)) return '只能用字母、数字、_ 和 .，且不能以数字开头'
  if (key.startsWith('system.')) return 'system. 开头的名称是内置变量，不能使用'
  if (all.filter((n) => n.trim() === key).length > 1) return '名称重复'
  return null
}

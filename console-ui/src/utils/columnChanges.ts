/**
 * How a source table changed since a job's version was saved (design.md §12 P2, Q17), for the
 * wizard's field list. Mirrors the server's SchemaDiff: a column that disappeared where one of the
 * same type appeared at the same position counts as a rename; names compare ignoring case.
 */
import type { SeenColumn } from '@/api/console'

export interface ColumnChanges {
  /** current names of columns that are new */
  added: Set<string>
  /** current name → the name it had */
  renamed: Map<string, string>
  /** current name → the type it had */
  retyped: Map<string, string>
  /** columns that are gone, as they were */
  dropped: SeenColumn[]
}

const key = (name: string) => name.toLowerCase()
const sameType = (a: string, b: string) => a.toLowerCase() === b.toLowerCase()

/** Null when nothing changed, or when nothing was seen to compare with. */
export function columnChanges(seen: SeenColumn[] | null | undefined, now: SeenColumn[] | undefined): ColumnChanges | null {
  if (!seen?.length || !now) return null
  const was = new Map(seen.map((c) => [key(c.name), c]))
  const is = new Map(now.map((c) => [key(c.name), c]))
  const added = now.filter((c) => !was.has(key(c.name)))
  const dropped = seen.filter((c) => !is.has(key(c.name)))
  const renamed = new Map<string, string>()
  for (const gone of [...dropped]) {
    const replacement = now[seen.indexOf(gone)]
    if (replacement && added.includes(replacement) && sameType(gone.type, replacement.type)) {
      renamed.set(replacement.name, gone.name)
      dropped.splice(dropped.indexOf(gone), 1)
      added.splice(added.indexOf(replacement), 1)
    }
  }
  const retyped = new Map<string, string>()
  for (const c of seen) {
    const current = is.get(key(c.name))
    if (current && !sameType(c.type, current.type)) retyped.set(current.name, c.type)
  }
  if (!added.length && !dropped.length && !renamed.size && !retyped.size) return null
  return { added: new Set(added.map((c) => c.name)), renamed, retyped, dropped }
}

/** e.g. "新增 1 列、改名 1 列". */
export function describeChanges(c: ColumnChanges): string {
  const parts = [
    c.added.size && `新增 ${c.added.size} 列`,
    c.renamed.size && `改名 ${c.renamed.size} 列`,
    c.retyped.size && `改类型 ${c.retyped.size} 列`,
    c.dropped.length && `删除 ${c.dropped.length} 列`,
  ]
  return parts.filter(Boolean).join('、')
}

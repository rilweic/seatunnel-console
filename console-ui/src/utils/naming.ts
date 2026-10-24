import type { NameRule, WizardForm, WizardTable } from '@/api/console'
import { newFieldNames } from '@/utils/fieldProcessing'

/*
 * Target names of the job wizard (docs/design.md §7.8). The same rules run on the server
 * (WizardGenerator.targetName / targetField), which generates the config; keep the two in step.
 */

export function applyRule(rule: NameRule | null | undefined, name: string): string {
  if (!rule) return name
  let out = name
  if (rule.find) out = out.split(rule.find).join(rule.replace ?? '')
  if (rule.nameCase === 'UPPER') out = out.toUpperCase()
  else if (rule.nameCase === 'LOWER') out = out.toLowerCase()
  return (rule.prefix?.trim() ?? '') + out + (rule.suffix?.trim() ?? '')
}

export function isEmptyRule(rule: NameRule | null | undefined): boolean {
  return !rule || (!rule.find && (!rule.nameCase || rule.nameCase === 'KEEP') && !rule.prefix?.trim() && !rule.suffix?.trim())
}

/** The table's name from the rule alone, ignoring a name set by hand. */
export function ruleTableName(form: WizardForm, table: WizardTable, es: boolean): string {
  const name = applyRule(form.target.tableRule, table.name)
  return es ? name.toLowerCase() : name
}

export function targetTableName(form: WizardForm, table: WizardTable, es: boolean): string {
  const name = table.targetName?.trim() ? table.targetName.trim() : applyRule(form.target.tableRule, table.name)
  return es ? name.toLowerCase() : name
}

export function fieldRuleOf(form: WizardForm, table: WizardTable): NameRule | null | undefined {
  return table.fieldRule ?? form.fieldRule
}

export function ruleFieldName(form: WizardForm, table: WizardTable, column: string): string {
  return applyRule(fieldRuleOf(form, table), column)
}

export function targetFieldName(form: WizardForm, table: WizardTable, column: string): string {
  const byHand = table.fieldNames?.[column]?.trim()
  return byHand ? byHand : ruleFieldName(form, table, column)
}

/** Characters a target name may not hold (the server rejects them too). */
export const BAD_NAME = /[\s.$`"'\\]/

/** Names that clash with another, ignoring case: name -> true. */
export function clashes(names: string[]): Set<string> {
  const seen = new Map<string, number>()
  for (const n of names) seen.set(n.toLowerCase(), (seen.get(n.toLowerCase()) ?? 0) + 1)
  return new Set(names.filter((n) => (seen.get(n.toLowerCase()) ?? 0) > 1))
}

/**
 * Forms saved before name rules: prefix/suffix become the table rule, a single table's name its
 * own target name, so the mapping reads exactly as before.
 */
export function migrateNaming(form: WizardForm) {
  if (form.target.tableRule == null) {
    form.target.tableRule = { find: '', replace: '', nameCase: 'KEEP', prefix: form.target.prefix ?? '', suffix: form.target.suffix ?? '' }
    if (form.source.tables.length === 1 && form.target.tableName?.trim()) {
      form.source.tables[0].targetName = form.target.tableName.trim()
    }
  }
  form.target.tableName = null
  form.target.prefix = null
  form.target.suffix = null
}

/** Synced source fields of a table, given its column list as loaded. */
export function syncedFields(table: WizardTable, all: string[]): string[] {
  return table.columns?.length ? table.columns : all
}

/** What is wrong with a target name, or null. */
export function nameProblem(name: string, clashing: Set<string>): string | null {
  if (!name.trim()) return '名字为空'
  if (BAD_NAME.test(name)) return '不能包含空格、点、引号、$ 或反斜杠'
  if (clashing.has(name)) return '与其他名字重复'
  return null
}

/** The first problem among target table names, for blocking the step. */
export function tableNamesProblem(form: WizardForm, es: boolean): string | null {
  const names = form.source.tables.map((t) => targetTableName(form, t, es))
  const clashing = clashes(names)
  for (const [i, t] of form.source.tables.entries()) {
    const problem = nameProblem(names[i], clashing)
    if (problem) return `${t.name} 的目标${es ? '索引' : '表'}名${problem}`
  }
  return null
}

/** The first problem among target field names of tables whose columns are loaded. */
export function fieldNamesProblem(form: WizardForm, columns: Record<string, { name: string }[]>): string | null {
  for (const t of form.source.tables) {
    const all = columns[t.name]?.map((c) => c.name)
    if (!all) continue
    const dropped = new Set((t.extracts ?? []).filter((e) => e.dropSource).map((e) => e.source))
    const fields = syncedFields(t, all).filter((c) => !dropped.has(c))
    const names = fields.map((c) => targetFieldName(form, t, c))
    const added = newFieldNames(t)
    const clashing = clashes([...names, ...added])
    for (const [i, c] of fields.entries()) {
      const problem = nameProblem(names[i], clashing)
      if (problem) return `${t.name}.${c} 的目标字段名${problem}`
    }
    for (const n of added) {
      const problem = nameProblem(n, clashing)
      if (problem) return `${t.name} 的新字段 ${n || '（未命名）'}：${problem}`
    }
  }
  return null
}

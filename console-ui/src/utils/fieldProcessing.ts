/*
 * Labels and previews for the wizard's field processing (docs/design.md §7.10). The server turns
 * it into SeaTunnel transforms (FieldProcessing); this only describes it to people.
 */
import type { AddedField, Extract, FieldProcess, FieldType, MaskPreset, WizardTable } from '@/api/console'

export const FIELD_TYPES: { value: FieldType; label: string }[] = [
  { value: 'STRING', label: '字符串' },
  { value: 'BIGINT', label: '整数' },
  { value: 'DECIMAL', label: '小数' },
  { value: 'DATE', label: '日期' },
  { value: 'TIMESTAMP', label: '日期时间' },
  { value: 'BOOLEAN', label: '布尔' },
]

export const typeLabel = (t?: FieldType | null) => FIELD_TYPES.find((x) => x.value === t)?.label ?? '字符串'

export const MASKS: { value: MaskPreset; label: string; keep: [number, number]; example: string }[] = [
  { value: 'PHONE', label: '手机号', keep: [3, 4], example: '13812345678' },
  { value: 'ID_CARD', label: '身份证号', keep: [6, 4], example: '110101199003071234' },
  { value: 'EMAIL', label: '邮箱', keep: [1, 0], example: 'zhangsan@example.com' },
  { value: 'NAME', label: '姓名', keep: [1, 0], example: '张小明' },
  { value: 'BANK_CARD', label: '银行卡号', keep: [0, 4], example: '6222021234567890123' },
  { value: 'CUSTOM', label: '自定义', keep: [0, 0], example: 'ABCDEFGHIJ' },
]

/** What a mask does to a value, the way the server's expression does it. */
export function maskPreview(value: string, preset: MaskPreset, keepFirst = 0, keepLast = 0, maskChar = '*'): string {
  const ch = (maskChar || '*').charAt(0)
  if (preset === 'EMAIL') {
    const at = value.indexOf('@')
    return at > 0 ? value.charAt(0) + ch.repeat(3) + value.substring(at) : value
  }
  const m = MASKS.find((x) => x.value === preset)!
  const [first, last] = preset === 'CUSTOM' ? [Math.max(0, keepFirst), Math.max(0, keepLast)] : m.keep
  if (value.length <= first + last) return value
  return value.substring(0, first) + ch.repeat(value.length - first - last) + (last ? value.substring(value.length - last) : '')
}

/** Short tags for a processed field, e.g. 替换 · 脱敏(手机号) · 转为整数. */
export function processTags(p?: FieldProcess | null): string[] {
  if (!p) return []
  const tags: string[] = []
  if (p.replace?.find) tags.push(p.replace.regex ? '正则替换' : '替换')
  if (p.mask?.preset) tags.push(`脱敏(${MASKS.find((m) => m.value === p.mask!.preset)?.label})`)
  if (p.cast?.type) tags.push(`转为${typeLabel(p.cast.type)}`)
  if (p.encrypt) tags.push('加密')
  return tags
}

export function hasProcess(p?: FieldProcess | null): boolean {
  return processTags(p).length > 0
}

export const ADD_KINDS: { value: AddedField['kind']; label: string; help: string }[] = [
  { value: 'CONSTANT', label: '常量或变量', help: '固定值、内置变量或日期时间，如同步时间、批次日期' },
  { value: 'CONCAT', label: '拼接字段', help: '把几个字段用分隔符连起来，空值会被跳过' },
  { value: 'SUBSTRING', label: '截取', help: '取字段的前几位、后几位或中间一段' },
  { value: 'CASE', label: '大小写 / 去空格', help: '转大写、转小写，或去掉首尾空格' },
  { value: 'DATE_FORMAT', label: '日期格式化', help: '把日期时间字段转成指定格式的文本' },
  { value: 'ARITHMETIC', label: '数值计算', help: '字段和一个数或另一个字段做加减乘除' },
  { value: 'DEFAULT', label: '为空时用默认值', help: '字段为空时取一个默认值' },
  { value: 'UUID', label: '生成 UUID', help: '每行一个随机唯一值' },
  { value: 'NOW', label: '当前时间', help: '写入时的时间' },
  { value: 'EXPRESSION', label: '高级：手写表达式', help: 'SeaTunnel SQL 表达式，只有运行时才会检查写法' },
]

const kindLabel = (k: AddedField['kind']) => ADD_KINDS.find((x) => x.value === k)?.label ?? k

/** One line describing how a new field is computed. */
export function describeAdded(a: AddedField): string {
  const f = a.fields ?? []
  switch (a.kind) {
    case 'CONSTANT':
      return `= ${a.value ?? ''}`
    case 'CONCAT':
      return `拼接 ${f.join(' + ')}${a.value ? `（分隔符 "${a.value}"）` : ''}`
    case 'SUBSTRING':
      return a.op === 'RIGHT' ? `${f[0]} 的后 ${a.length} 位` : a.op === 'MID' ? `${f[0]} 从第 ${a.start} 位起取 ${a.length} 位` : `${f[0]} 的前 ${a.length} 位`
    case 'CASE':
      return `${f[0]} ${a.op === 'LOWER' ? '转小写' : a.op === 'TRIM' ? '去空格' : '转大写'}`
    case 'DATE_FORMAT':
      return `${f[0]} 格式化为 ${a.format}`
    case 'ARITHMETIC':
      return `${f[0]} ${a.op} ${f[1] || a.value}`
    case 'DEFAULT':
      return `${f[0]}，为空时取 "${a.value ?? ''}"`
    case 'EXPRESSION':
      return a.value ?? ''
    default:
      return kindLabel(a.kind)
  }
}

export function describeExtract(e: Extract): string {
  if (e.kind === 'JSON') return `从 ${e.source} 按 JSON 路径提取`
  if (e.kind === 'REGEX') return `从 ${e.source} 按正则 ${e.pattern} 提取`
  return `把 ${e.source} 按 "${e.pattern}" 拆分`
}

/** Fields an added field may read: the synced ones plus the extracted ones. */
export function readableFields(table: WizardTable, synced: string[]): string[] {
  const dropped = new Set((table.extracts ?? []).filter((e) => e.dropSource).map((e) => e.source))
  return [...synced, ...(table.extracts ?? []).flatMap((e) => e.outputs.map((o) => o.name))].filter((f) => f && !dropped.has(f))
}

/** Names of the new fields (extracted and added), as the target gets them. */
export function newFieldNames(table: WizardTable): string[] {
  return [...(table.extracts ?? []).flatMap((e) => e.outputs.map((o) => o.name.trim())), ...(table.addedFields ?? []).map((a) => a.name.trim())]
}

export const DATE_FORMATS = ['yyyy-MM-dd', 'yyyyMMdd', 'yyyy-MM-dd HH:mm:ss', 'yyyyMMddHHmmss', 'yyyy/MM/dd', 'yyyy-MM', 'HH:mm:ss']

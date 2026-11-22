import { del, get, LONG_TIMEOUT, post, put, type PageResult } from './http'
import type { Owned } from './projects'

/** A datasource type name from the server, e.g. MYSQL; the list comes from {@link datasourceApi.types}. */
export type DatasourceType = string

/** What a datasource type can do (DataSourceCapabilities on the server, design.md §6.1). */
export interface DatasourceCapabilities {
  label: string
  /** SeaTunnel source connector the wizard generates; null when the wizard cannot read from it. */
  sourceConnector: string | null
  /** SeaTunnel sink connector the wizard generates; null when the wizard cannot write to it. */
  sinkConnector: string | null
  /** Jobs pick a database or schema first. */
  namespaces: boolean
  multiTable: boolean
  /** How rows are counted for validation; null when they cannot be. */
  countBy: 'SQL' | 'INDEX' | null
  maxParallelism: number
  lowerCaseNames: boolean
  jdbcDriver: boolean
  /** SeaTunnel CDC source real-time jobs read it with, e.g. MySQL-CDC; null when it cannot be a real-time source. */
  cdcConnector: string | null
}

/** One condition for real-time (CDC) reads, from {@link datasourceApi.cdcCheck} (design.md §12 P2). */
export interface CdcCheck {
  item: string
  level: 'OK' | 'WARN' | 'ERROR'
  actual: string | null
  message: string
  /** SQL or a setting to apply by hand; null when the condition holds. */
  fix: string | null
}

export interface DatasourceTypeView {
  type: DatasourceType
  capabilities: DatasourceCapabilities
}

export interface DatasourceView extends Owned {
  id: string
  name: string
  type: DatasourceType
  description: string | null
  /** Where it points, e.g. db1:3306/shop, worked out by the server per type. */
  address: string
  config: Record<string, string>
  secretsSet: string[]
  createdAt: string
  updatedAt: string
}

export interface ConnectionTestResult {
  success: boolean
  message: string
  elapsedMillis: number
}

export interface TableMeta {
  name: string
  primaryKeys: string[]
}

export interface ColumnMeta {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  temporal: boolean
}

export const datasourceApi = {
  types: () => get<DatasourceTypeView[]>('/datasources/types'),
  namespaces: (id: string) => get<string[]>(`/datasources/${id}/namespaces`),
  tables: (id: string, namespace?: string) => get<TableMeta[]>(`/datasources/${id}/tables`, { namespace }),
  columns: (id: string, namespace: string | undefined, table: string) =>
    get<ColumnMeta[]>(`/datasources/${id}/columns`, { namespace, table }),
  list: (params: { keyword?: string; type?: string; ownerId?: string | null; page?: number; size?: number }) =>
    get<PageResult<DatasourceView>>('/datasources', params),
  create: (body: { name: string; type: DatasourceType; description?: string; props: Record<string, string> }) =>
    post<DatasourceView>('/datasources', body),
  update: (id: string, body: { name: string; description?: string; props: Record<string, string> }) =>
    put<DatasourceView>(`/datasources/${id}`, body),
  remove: (id: string) => del<void>(`/datasources/${id}`),
  test: (body: { datasourceId?: string; type: DatasourceType; props: Record<string, string> }) =>
    post<ConnectionTestResult>('/datasources/test', body),
  cdcCheck: (id: string) => post<CdcCheck[]>(`/datasources/${id}/cdc-check`, undefined, LONG_TIMEOUT.check),
}

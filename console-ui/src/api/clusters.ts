import { del, get, post, put } from './http'

export type EngineType = 'ZETA' | 'FLINK'

export interface ClusterMasterStatus {
  url: string
  reachable: boolean
  authRequired: boolean | null
  version: string | null
  latencyMillis: number
  error: string | null
}

export interface ClusterView {
  id: string
  name: string
  engineType: EngineType
  description: string | null
  restUrls: string
  username: string | null
  hasPassword: boolean
  flinkVersion: string | null
  flinkHome: string | null
  seatunnelHome: string | null
  javaHome: string | null
  isDefault: boolean
  jobCount: number
  createdAt: string | null
  updatedAt: string | null
}

export interface ClusterRequest {
  name: string
  engineType: EngineType
  description?: string
  restUrls: string
  username?: string
  password?: string
  flinkVersion?: string
  flinkHome?: string
  seatunnelHome?: string
  javaHome?: string
  isDefault?: boolean
}

export interface ClusterTestResult {
  ok: boolean
  version: string | null
  latencyMillis: number
  masters: ClusterMasterStatus[]
  warnings: string[]
  error: string | null
}

export const clustersApi = {
  list: () => get<ClusterView[]>('/clusters'),
  create: (body: ClusterRequest) => post<ClusterView>('/clusters', body),
  update: (id: string, body: ClusterRequest) => put<ClusterView>(`/clusters/${id}`, body),
  remove: (id: string) => del<void>(`/clusters/${id}`),
  setDefault: (id: string) => post<void>(`/clusters/${id}/default`),
  test: (body: ClusterRequest, existingId?: string) =>
    post<ClusterTestResult>(existingId ? `/clusters/test?existingId=${encodeURIComponent(existingId)}` : '/clusters/test', body),
}

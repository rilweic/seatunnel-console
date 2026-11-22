import { del, get, post, put, type PageResult } from './http'

/** Owner fields every owned resource carries; editable is computed for the current user. */
export interface Owned {
  ownerId: string | null
  ownerName: string | null
  editable: boolean
}

export interface ProjectView extends Owned {
  id: string
  name: string
  description: string | null
  jobCount: number
  createdAt: string
}

export interface ParamItem {
  key: string
  value: string
  description?: string | null
}

export const projectApi = {
  list: (params: { keyword?: string; mine?: boolean; page?: number; size?: number }) => get<PageResult<ProjectView>>('/projects', params),
  create: (body: { name: string; description?: string }) => post<ProjectView>('/projects', body),
  update: (id: string, body: { name: string; description?: string }) => put<ProjectView>(`/projects/${id}`, body),
  remove: (id: string) => del<void>(`/projects/${id}`),
  params: (id: string) => get<ParamItem[]>(`/projects/${id}/params`),
  saveParams: (id: string, items: ParamItem[]) => put<ParamItem[]>(`/projects/${id}/params`, items),
}

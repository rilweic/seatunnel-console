import { get, post, put } from './http'

export interface UserView {
  id: string
  username: string
  displayName: string | null
  role: 'ADMIN' | 'USER'
  mustChangePassword: boolean
}

export const authApi = {
  login: (username: string, password: string) => post<UserView>('/auth/login', { username, password }),
  me: () => get<UserView>('/auth/me', undefined, true),
  logout: () => post<void>('/auth/logout'),
  changePassword: (oldPassword: string, newPassword: string) => put<UserView>('/auth/password', { oldPassword, newPassword }),
}

export interface AccountView {
  id: string
  username: string
  displayName: string | null
  role: 'ADMIN' | 'USER'
  enabled: boolean
  mustChangePassword: boolean
  lastLoginAt: string | null
  createdAt: string
  projects: number
  datasources: number
  jobs: number
}

export interface EncryptKeyView {
  id: string
  name: string
  description: string | null
  algorithm: 'AES_GCM' | 'AES_CBC'
  keyBits: number
  enabled: boolean
  createdByName: string | null
  createdAt: string
}

/** Field encryption keys (docs/design.md §7.11); the key itself never comes back. */
export const encryptKeyApi = {
  list: () => get<EncryptKeyView[]>('/encrypt-keys'),
  create: (body: { name: string; description?: string; algorithm: string; bits?: number; importedKey?: string }) =>
    post<EncryptKeyView>('/encrypt-keys', body),
  update: (id: string, body: { name: string; description?: string | null; enabled: boolean }) => put<EncryptKeyView>(`/encrypt-keys/${id}`, body),
}

export const userApi = {
  list: () => get<AccountView[]>('/users'),
  create: (body: { username: string; displayName?: string; role: string; password: string }) => post<AccountView>('/users', body),
  update: (id: string, body: { displayName?: string | null; role: string; enabled: boolean }) => put<AccountView>(`/users/${id}`, body),
  resetPassword: (id: string, password: string) => post<AccountView>(`/users/${id}/reset-password`, { password }),
  transfer: (id: string, toUserId: string) =>
    post<{ projects: number; datasources: number; jobs: number }>(`/users/${id}/transfer`, { toUserId }),
}

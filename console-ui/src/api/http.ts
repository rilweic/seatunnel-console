import axios from 'axios'
import { ElMessage } from 'element-plus'

/** Mirrors com.dataintegration.console.common.Result on the backend. */
export interface Result<T> {
  code: number
  message: string
  data: T
}

/** Mirrors com.dataintegration.console.common.PageResult. */
export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
}

// Same-origin requests carry the XSRF-TOKEN cookie back as X-XSRF-TOKEN (axios defaults),
// matching Spring Security's SPA CSRF setup.
const http = axios.create({ baseURL: '/api', timeout: 60_000 })

http.interceptors.response.use(
  (response) => {
    const body = response.data as Result<unknown>
    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      ElMessage.error(body.message)
      return Promise.reject(new Error(body.message))
    }
    return response
  },
  async (error) => {
    if (error.response?.status === 401) {
      // Imported lazily: the router imports views that import this module.
      const { default: router } = await import('@/router')
      if (router.currentRoute.value.path !== '/login') {
        router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      } else if (error.config?.silent !== true) {
        // e.g. wrong password on the login page itself
        ElMessage.error(error.response?.data?.message ?? '登录失败')
      }
      return Promise.reject(error)
    }
    const message = error.response?.data?.message ?? error.message
    if (error.config?.silent !== true) {
      ElMessage.error(message)
    }
    return Promise.reject(error)
  },
)

export async function get<T>(url: string, params?: Record<string, unknown>, silent = false): Promise<T> {
  const response = await http.get<Result<T>>(url, { params, silent } as never)
  return response.data.data
}

export async function post<T>(url: string, body?: unknown): Promise<T> {
  const response = await http.post<Result<T>>(url, body)
  return response.data.data
}

export async function put<T>(url: string, body?: unknown): Promise<T> {
  const response = await http.put<Result<T>>(url, body)
  return response.data.data
}

export async function del<T>(url: string): Promise<T> {
  const response = await http.delete<Result<T>>(url)
  return response.data.data
}

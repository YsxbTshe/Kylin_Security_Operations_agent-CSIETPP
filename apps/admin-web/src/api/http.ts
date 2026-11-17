import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { toast } from '../ui'

export interface ApiResponse<T> {
  success: boolean
  data: T
  message: string
}

export interface RequestConfig extends AxiosRequestConfig {
  silentErrorToast?: boolean
}

export const AUTH_TOKEN_KEY = 'kylin.ops.token'

let memoryToken = ''

export function setAuthToken(token: string) {
  memoryToken = token
  window.localStorage.removeItem(AUTH_TOKEN_KEY)
}

export function authToken() {
  return memoryToken
}

export const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api/v1',
  timeout: 30000,
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  if (memoryToken) {
    config.headers = config.headers ?? {}
    config.headers.Authorization = `Bearer ${memoryToken}`
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = error.config as RequestConfig | undefined
    const message = extractErrorMessage(error, '请求失败')
    if (!config?.silentErrorToast) {
      toast.error(message)
    }
    if (error.response?.status === 401) {
      setAuthToken('')
      if (window.location.pathname !== '/login') {
        const target = encodeURIComponent(window.location.pathname + window.location.search)
        window.location.href = `/login?redirect=${target}`
      }
    }
    return Promise.reject(error)
  },
)

export function extractErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof AxiosError) {
    const message = error.response?.data?.message
    if (typeof message === 'string' && message.trim()) {
      return message.trim()
    }
  }
  if (error instanceof Error && error.message.trim()) {
    return error.message.trim()
  }
  return fallback
}

export async function getData<T>(url: string, config?: RequestConfig): Promise<T> {
  const response = await http.get<ApiResponse<T>>(url, config)
  return response.data.data
}

export async function postData<T>(url: string, payload?: unknown, config?: RequestConfig): Promise<T> {
  const response = await http.post<ApiResponse<T>>(url, payload, config)
  return response.data.data
}

export async function putData<T>(url: string, payload?: unknown, config?: RequestConfig): Promise<T> {
  const response = await http.put<ApiResponse<T>>(url, payload, config)
  return response.data.data
}

export async function deleteData<T>(url: string, config?: RequestConfig): Promise<T> {
  const response = await http.delete<ApiResponse<T>>(url, config)
  return response.data.data
}

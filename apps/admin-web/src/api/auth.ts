import { getData, postData } from './http'

export interface OrganizationView {
  id: string
  code: string
  name: string
  hostCount: number
  memberCount: number
  current: boolean
}

export interface RoleView {
  code: string
  name: string
  description: string
  permissions: string[]
}

export interface CurrentUser {
  id: string
  username: string
  displayName: string
  email: string | null
  avatarUrl: string | null
  enabled: boolean
  mustChangePassword: boolean
  currentOrganizationId: string
  organizations: OrganizationView[]
  roles: RoleView[]
  permissions: string[]
}

export interface LoginPayload {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  user: CurrentUser
  mustChangePassword: boolean
}

export function login(payload: LoginPayload) {
  return postData<LoginResponse>('/auth/login', payload, { silentErrorToast: true })
}

export function logout() {
  return postData<{ status: string }>('/auth/logout')
}

export function changePassword(payload: { currentPassword: string; newPassword: string }) {
  return postData<void>('/auth/change-password', payload)
}

export function fetchMe() {
  return getData<CurrentUser>('/auth/me', { silentErrorToast: true })
}

export function switchOrganization(organizationId: string) {
  return postData<CurrentUser>('/auth/switch-organization', { organizationId })
}

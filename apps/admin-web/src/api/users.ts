import { getData, postData, putData } from './http'
import type { OrganizationView, RoleView } from './auth'

export interface UserView {
  id: string
  username: string
  displayName: string
  email: string | null
  avatarUrl: string | null
  enabled: boolean
  defaultOrganizationId: string
  roles: RoleView[]
  organizations: OrganizationView[]
}

export interface CreateUserPayload {
  username: string
  displayName: string
  email: string
  avatarUrl: string
  password: string
  enabled: boolean
  roleCodes: string[]
  organizationIds: string[]
  defaultOrganizationId: string
}

export interface UpdateUserPayload {
  displayName: string
  email: string
  avatarUrl: string
  password?: string
  enabled: boolean
  roleCodes: string[]
  organizationIds: string[]
  defaultOrganizationId: string
}

export interface AvatarUploadResponse {
  avatarUrl: string
}

export function fetchUsers() {
  return getData<UserView[]>('/users')
}

export function createUser(payload: CreateUserPayload) {
  return postData<UserView>('/users', payload)
}

export function updateUser(userId: string, payload: UpdateUserPayload) {
  return putData<UserView>(`/users/${userId}`, payload)
}

export function uploadUserAvatar(file: File) {
  const formData = new FormData()
  formData.append('file', file)
  return postData<AvatarUploadResponse>('/users/avatar', formData, {
    headers: {
      'Content-Type': 'multipart/form-data',
    },
  })
}

import { deleteData, getData, postData, putData } from './http'

export interface PermissionView {
  code: string
  name: string
  module: string
  description: string
}

export interface SavePermissionPayload {
  code: string
  name: string
  module: string
  description: string
}

export function fetchPermissions() {
  return getData<PermissionView[]>('/permissions')
}

export function createPermission(payload: SavePermissionPayload) {
  return postData<PermissionView>('/permissions', payload)
}

export function updatePermission(permissionCode: string, payload: Omit<SavePermissionPayload, 'code'>) {
  return putData<PermissionView>(`/permissions/${permissionCode}`, payload)
}

export function deletePermission(permissionCode: string) {
  return deleteData<boolean>(`/permissions/${permissionCode}`)
}

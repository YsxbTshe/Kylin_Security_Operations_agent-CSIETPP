import { deleteData, getData, postData, putData } from './http'
import type { RoleView } from './auth'

export function fetchRoles() {
  return getData<RoleView[]>('/roles')
}

export interface SaveRolePayload {
  code: string
  name: string
  description: string
  permissions: string[]
}

export function createRole(payload: SaveRolePayload) {
  return postData<RoleView>('/roles', payload)
}

export function updateRole(roleCode: string, payload: Omit<SaveRolePayload, 'code'>) {
  return putData<RoleView>(`/roles/${roleCode}`, payload)
}

export function deleteRole(roleCode: string) {
  return deleteData<boolean>(`/roles/${roleCode}`)
}

import { getData, postData, putData } from './http'
import type { OrganizationView } from './auth'

export function fetchOrganizations() {
  return getData<OrganizationView[]>('/organizations')
}

export interface SaveOrganizationPayload {
  code: string
  name: string
}

export function createOrganization(payload: SaveOrganizationPayload) {
  return postData<OrganizationView>('/organizations', payload)
}

export function updateOrganization(organizationId: string, payload: SaveOrganizationPayload) {
  return putData<OrganizationView>(`/organizations/${organizationId}`, payload)
}

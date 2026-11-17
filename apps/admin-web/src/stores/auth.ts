import { defineStore } from 'pinia'
import { setAuthToken } from '../api/http'
import { changePassword, fetchMe, login, logout, switchOrganization, type CurrentUser, type LoginPayload } from '../api/auth'

interface AuthState {
  token: string
  user: CurrentUser | null
  initialized: boolean
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: '',
    user: null,
    initialized: false,
  }),
  getters: {
    isAuthenticated: (state) => Boolean(state.user),
    mustChangePassword: (state) => Boolean(state.user?.mustChangePassword),
    currentOrganizationId: (state) => state.user?.currentOrganizationId ?? '',
    currentOrganization: (state) => state.user?.organizations.find((organization) => organization.id === state.user?.currentOrganizationId) ?? null,
  },
  actions: {
    async initialize() {
      if (this.initialized) {
        return
      }
      setAuthToken('')
      try {
        this.user = await fetchMe()
        this.token = this.token || 'cookie-session'
      } catch {
        this.clearAuthState()
      } finally {
        this.initialized = true
      }
    },
    async signIn(payload: LoginPayload) {
      const response = await login(payload)
      this.token = response.token
      this.user = response.user
      this.initialized = true
      setAuthToken(response.token)
    },
    async signOut() {
      try {
        if (this.user) {
          await logout()
        }
      } finally {
        this.clearAuthState()
      }
    },
    async changeOrganization(organizationId: string) {
      this.user = await switchOrganization(organizationId)
    },
    async updatePassword(currentPassword: string, newPassword: string) {
      await changePassword({ currentPassword, newPassword })
      if (this.user) {
        this.user = { ...this.user, mustChangePassword: false }
      }
    },
    hasPermission(permission?: string) {
      if (!permission) {
        return true
      }
      return this.user?.permissions.includes(permission) ?? false
    },
    clearAuthState() {
      this.token = ''
      this.user = null
      this.initialized = true
      setAuthToken('')
    },
  },
})

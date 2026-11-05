import type { AdministrationPermission, AdministrationUser, AdministrationUserInput } from "@/lib/domain/administration"
import type { AdministrationUserQuery, AdministrationUserRepository, CredentialActionToken } from "@/lib/services/administration"
import { browserApiTransport } from "./http-transport"

type ApiUser = {
  id: string
  fullName: string
  email: string
  role: "USER" | "ADMIN"
  active: boolean
  validFrom?: string | null
  validUntil?: string | null
  lastLoginAt?: string | null
  presence: "online" | "away" | "offline"
  activeAssignedCases: number
  permissions: AdministrationPermission[]
}

function mapUser(user: ApiUser): AdministrationUser {
  return {
    ...user,
    validFrom: user.validFrom?.slice(0, 10) ?? new Date().toISOString().slice(0, 10),
    validUntil: user.validUntil?.slice(0, 10),
    lastLoginAt: user.lastLoginAt ?? undefined,
  }
}

function body(input: AdministrationUserInput) {
  return {
    ...input,
    validFrom: input.validFrom ? `${input.validFrom}T00:00:00.000Z` : null,
    validUntil: input.validUntil ? `${input.validUntil}T23:59:59.999Z` : null,
  }
}

function queryString(query: AdministrationUserQuery = {}) {
  const params = new URLSearchParams()
  if (query.search) params.set("search", query.search)
  if (query.role) params.set("role", query.role)
  if (query.active !== undefined) params.set("active", String(query.active))
  const value = params.toString()
  return value ? `?${value}` : ""
}

export const apiAdministrationUserRepository: AdministrationUserRepository = {
  async list(query) {
    const users = await browserApiTransport.request<ApiUser[]>({ method: "GET", path: `/api/v1/admin/users${queryString(query)}` })
    return users.map(mapUser)
  },
  async getCurrent() {
    return mapUser(await browserApiTransport.request<ApiUser>({ method: "GET", path: "/api/v1/admin/users/current" }))
  },
  async save(input, id) {
    return mapUser(await browserApiTransport.request<ApiUser>({
      method: id ? "PUT" : "POST",
      path: id ? `/api/v1/admin/users/${encodeURIComponent(id)}` : "/api/v1/admin/users",
      body: body(input),
    }))
  },
  async deactivate(id) {
    return mapUser(await browserApiTransport.request<ApiUser>({ method: "PATCH", path: `/api/v1/admin/users/${encodeURIComponent(id)}/deactivate` }))
  },
  async delete(id) {
    await browserApiTransport.request<void>({ method: "DELETE", path: `/api/v1/admin/users/${encodeURIComponent(id)}` })
  },
  async issueInvitation(id) {
    return browserApiTransport.request<CredentialActionToken>({ method: "POST", path: `/api/v1/admin/users/${encodeURIComponent(id)}/invitation` })
  },
  async issuePasswordReset(id) {
    return browserApiTransport.request<CredentialActionToken>({ method: "POST", path: `/api/v1/admin/users/${encodeURIComponent(id)}/password-reset` })
  },
}

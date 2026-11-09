import { createApiClient } from "@usi/api-client/generated"
import { administrationPermissions } from "@/lib/domain/administration"
import type { AdministrationPermission, AdministrationUser, AdministrationUserInput } from "@/lib/domain/administration"
import type { AdministrationUserRepository, CredentialActionToken } from "@/lib/services/administration"
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
  permissions?: AdministrationPermission[]
}

function mapUser(user: ApiUser): AdministrationUser {
  return {
    ...user,
    permissions: user.permissions ?? [],
    validFrom: user.validFrom?.slice(0, 10) ?? "",
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

export const apiAdministrationUserRepository: AdministrationUserRepository = {
  supportsMutations: true,
  async list(query) {
    const client = createApiClient(browserApiTransport)
    const users: AdministrationUser[] = []
    let cursor: string | undefined
    do {
      const page = await client.listAdminUsers({ ...query, cursor, limit: 100 })
      users.push(...page.items.map((user) => mapUser({ ...user, permissions: user.permissions as AdministrationPermission[] | undefined })))
      cursor = page.nextCursor ?? undefined
    } while (cursor)
    return users
  },
  async getCurrent() {
    const client = createApiClient(browserApiTransport)
    const session = await client.getCurrentSession()
    if (session.role !== "ADMIN") return undefined
    const page = await client.listAdminUsers({ search: session.email, limit: 100 })
    const current = page.items.find((user) => user.id === session.id)
    if (!current) return undefined
    return mapUser({ ...current, permissions: session.effectivePermissions.filter((value): value is AdministrationPermission => (administrationPermissions as readonly string[]).includes(value)) })
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

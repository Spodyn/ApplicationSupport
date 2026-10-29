import { createApiClient } from "@usi/api-client/generated"
import type { AdminUserItem } from "@usi/api-client/generated"
import type {
  AdministrationPermission,
  AdministrationUser,
} from "@/lib/domain/administration"
import { administrationPermissions } from "@/lib/domain/administration"
import type {
  AdministrationUserQuery,
  AdministrationUserRepository,
} from "@/lib/services/administration"
import { browserApiTransport } from "./http-transport"

const client = createApiClient(browserApiTransport)
const allowedPermissions = new Set<string>(administrationPermissions)

function mapUser(user: AdminUserItem): AdministrationUser {
  return {
    id: user.id,
    fullName: user.fullName,
    email: user.email,
    role: user.role,
    active: user.active,
    presence: user.presence,
    validFrom: user.validFrom?.slice(0, 10) ?? "",
    validUntil: user.validUntil?.slice(0, 10) ?? undefined,
    activeAssignedCases: user.activeAssignedCases,
    lastLoginAt: user.lastLoginAt ?? undefined,
    // The read-only directory does not yet expose other users' editable grants.
    // Never invent grants or use the former mock administration data.
    permissions: [],
  }
}

async function listUsers(query: AdministrationUserQuery = {}): Promise<AdministrationUser[]> {
  const items: AdministrationUser[] = []
  let cursor: string | undefined
  do {
    const page = await client.listAdminUsers({
      search: query.search,
      role: query.role,
      active: query.active,
      cursor,
      limit: 100,
    })
    items.push(...page.items.map(mapUser))
    cursor = page.nextCursor ?? undefined
  } while (cursor)
  return items
}

function mutationsNotAvailable(): never {
  throw new Error(
    "Zapisywanie zmian użytkowników wymaga wdrożenia osobnych operacji administracyjnych.",
  )
}

/**
 * The directory is live; mutations are intentionally disabled until USI-72–75.
 * Never route real user IDs into the mock user mutation repository.
 */
export const apiAdministrationUserRepository: AdministrationUserRepository = {
  supportsMutations: false,

  list: listUsers,

  async getCurrent() {
    const session = await client.getCurrentSession()
    if (session.role !== "ADMIN") return undefined
    const page = await client.listAdminUsers({ search: session.email, limit: 100 })
    const current = page.items.find((item) => item.id === session.id)
    if (!current) return undefined
    const permissions = session.effectivePermissions.filter(
      (value): value is AdministrationPermission => allowedPermissions.has(value),
    )
    return { ...mapUser(current), permissions }
  },

  async save() {
    return mutationsNotAvailable()
  },

  async deactivate() {
    return mutationsNotAvailable()
  },

  async delete() {
    return mutationsNotAvailable()
  },
}

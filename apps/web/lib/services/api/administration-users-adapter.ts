import { createApiClient } from "@usi/api-client/generated"
import type { AdminUserItem } from "@usi/api-client/generated"
import type {
  AdministrationPermission,
  AdministrationUser,
  AdministrationUserInput,
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

function toInstant(day: string | undefined): string | undefined {
  if (!day) return undefined
  return new Date(`${day}T00:00:00.000Z`).toISOString()
}

async function createUser(
  input: AdministrationUserInput,
  idempotencyKey?: string,
): Promise<AdministrationUser> {
  if (!input.initialPassword) {
    throw new Error("Podaj hasło początkowe nowego użytkownika.")
  }
  if (!idempotencyKey) {
    throw new Error("Brakuje identyfikatora bezpiecznego ponowienia operacji.")
  }

  const { initialPassword } = input
  // The generated client owns request/response typing; UI sees only AdministrationUser.
  const created = await client.createAdminUser({
    "Idempotency-Key": idempotencyKey,
    body: {
      email: input.email,
      fullName: input.fullName,
      role: input.role,
      active: input.active,
      validFrom: toInstant(input.validFrom),
      validUntil: toInstant(input.validUntil),
      permissions: input.permissions,
      initialPassword,
    },
  })
  return {
    id: created.id,
    email: created.email,
    fullName: created.fullName,
    role: created.role,
    active: created.active,
    validFrom: created.validFrom?.slice(0, 10) ?? "",
    validUntil: created.validUntil?.slice(0, 10),
    presence: "offline",
    activeAssignedCases: 0,
    permissions: created.role === "ADMIN" ? [...administrationPermissions] : [],
  }
}

function mutationsNotAvailable(): never {
  throw new Error(
    "Zapisywanie zmian użytkowników wymaga wdrożenia osobnych operacji administracyjnych.",
  )
}

/**
 * Directory and initial-password creation use the real API. Edit/deactivate/delete
 * remain disabled until USI-73–75; never mutate real users through mocks.
 */
export const apiAdministrationUserRepository: AdministrationUserRepository = {
  supportsMutations: false,
  supportsCreation: true,

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

  async save(input, id, key) {
    if (id) return mutationsNotAvailable()
    return createUser(input, key)
  },

  async deactivate() {
    return mutationsNotAvailable()
  },

  async delete() {
    return mutationsNotAvailable()
  },
}

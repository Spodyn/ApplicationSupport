import type {
  AdministrationPermission,
  AdministrationRole,
  AdministrationSettings,
  AdministrationUser,
  AdministrationUserInput,
  IntegrationConfigurationInput,
  ManagedChannel,
  ManagedCustomer,
  ManagedIntegration,
} from "@/lib/domain/administration"
import {
  administrationPermissionLabels,
  hasAdministrationPermission,
} from "@/lib/domain/administration"
import { mockCurrentUser } from "@/mocks/users"
import {
  mockAdministrationSettings,
  mockAdministrationUsers,
} from "@/mocks/administration"

export interface AdministrationUserQuery {
  search?: string
  role?: AdministrationRole
  active?: boolean
}

export interface AdministrationUserRepository {
  list(query?: AdministrationUserQuery): Promise<AdministrationUser[]>
  getCurrent(): Promise<AdministrationUser | undefined>
  save(input: AdministrationUserInput, id?: string): Promise<AdministrationUser>
  deactivate(id: string): Promise<AdministrationUser>
  delete(id: string): Promise<void>
  issueInvitation(id: string): Promise<CredentialActionToken>
  issuePasswordReset(id: string): Promise<CredentialActionToken>
}

export interface CredentialActionToken {
  token: string
  expiresAt: string
}

export interface AdministrationSettingsRepository {
  get(): Promise<AdministrationSettings>
  saveSection<K extends keyof AdministrationSettings>(
    key: K,
    value: AdministrationSettings[K],
  ): Promise<AdministrationSettings[K]>
  configureIntegration(input: IntegrationConfigurationInput): Promise<ManagedIntegration>
  setIntegrationStatus(
    id: string,
    status: ManagedIntegration["status"],
  ): Promise<ManagedIntegration>
  testIntegration(id: string): Promise<ManagedIntegration>
  discoverChannels(id: string): Promise<ManagedChannel[]>
  configureChannel(id: string, customerId: string | undefined, ignored: boolean): Promise<void>
  saveCustomer(input: Pick<ManagedCustomer, "name" | "externalRef">, id?: string): Promise<ManagedCustomer>
  deactivateCustomer(id: string): Promise<ManagedCustomer>
  toggleNotification(id: string, enabled: boolean): Promise<void>
  replayNotificationDelivery(id: string): Promise<void>
}

let usersState = structuredClone(mockAdministrationUsers)
const settingsState = structuredClone(mockAdministrationSettings)

function delay<T>(value: T, ms = 260): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(structuredClone(value)), ms))
}

function normalize(value: string) {
  return value.trim().toLocaleLowerCase("pl")
}

export class PermissionDeniedError extends Error {
  readonly status = 403
  readonly code = "PERMISSION_DENIED"

  constructor(permission: AdministrationPermission) {
    super(`Brak wymaganego uprawnienia: ${administrationPermissionLabels[permission]}.`)
    this.name = "PermissionDeniedError"
  }
}

export class AdministratorRoleRequiredError extends Error {
  readonly status = 403
  readonly code = "ADMINISTRATOR_ROLE_REQUIRED"

  constructor() {
    super("Ta funkcja wymaga roli administratora.")
    this.name = "AdministratorRoleRequiredError"
  }
}

export function getAdministrationUserSnapshot(email: string) {
  return usersState.find((user) => normalize(user.email) === normalize(email))
}

export function getCurrentAdministrationSettingsSnapshot() {
  return settingsState
}

export function requireCurrentAdministrationPermission(
  permission: AdministrationPermission,
) {
  const current = requireCurrentAdministrator()
  if (!hasAdministrationPermission(current, permission)) {
    throw new PermissionDeniedError(permission)
  }
  return current
}

export function requireCurrentAdministrator() {
  const current = getAdministrationUserSnapshot(mockCurrentUser.email)
  const today = new Date().toISOString().slice(0, 10)
  const valid = Boolean(
    current?.active &&
      current.validFrom <= today &&
      (!current.validUntil || current.validUntil >= today),
  )
  if (!valid || current?.role !== "ADMIN") {
    throw new AdministratorRoleRequiredError()
  }
  return current
}

export const mockAdministrationUserRepository: AdministrationUserRepository = {
  async list(query = {}) {
    requireCurrentAdministrationPermission("manage_users")
    let result = [...usersState]
    if (query.search) {
      const search = normalize(query.search)
      result = result.filter(
        (user) =>
          normalize(user.fullName).includes(search) ||
          normalize(user.email).includes(search),
      )
    }
    if (query.role) result = result.filter((user) => user.role === query.role)
    if (typeof query.active === "boolean") {
      result = result.filter((user) => user.active === query.active)
    }
    return delay(result)
  },

  async getCurrent() {
    return delay(getAdministrationUserSnapshot(mockCurrentUser.email))
  },

  async save(input, id) {
    requireCurrentAdministrationPermission("manage_users")
    if (input.role === "USER" && input.permissions.length > 0) {
      throw new Error("Rola USER nie może otrzymywać uprawnień administracyjnych.")
    }
    const duplicate = usersState.find(
      (user) => normalize(user.email) === normalize(input.email) && user.id !== id,
    )
    if (duplicate) throw new Error("Użytkownik z tym adresem e-mail już istnieje.")

    if (id) {
      const index = usersState.findIndex((user) => user.id === id)
      if (index < 0) throw new Error("Nie znaleziono użytkownika.")
      usersState[index] = { ...usersState[index], ...structuredClone(input) }
      return delay(usersState[index])
    }

    const created: AdministrationUser = {
      ...structuredClone(input),
      id: `adm-u-${Date.now()}`,
      presence: "offline",
      activeAssignedCases: 0,
    }
    usersState = [created, ...usersState]
    return delay(created)
  },

  async deactivate(id) {
    requireCurrentAdministrationPermission("manage_users")
    const user = usersState.find((item) => item.id === id)
    if (!user) throw new Error("Nie znaleziono użytkownika.")
    user.active = false
    user.presence = "offline"
    user.validUntil = new Date().toISOString().slice(0, 10)
    return delay(user)
  },

  async delete(id) {
    requireCurrentAdministrationPermission("manage_users")
    const user = usersState.find((item) => item.id === id)
    if (!user) throw new Error("Nie znaleziono użytkownika.")
    if (normalize(user.email) === normalize(mockCurrentUser.email)) {
      throw new Error("Nie możesz usunąć własnego konta.")
    }
    usersState = usersState.filter((item) => item.id !== id)
    return delay(undefined)
  },

  async issueInvitation() {
    throw new Error("Zaproszenia są dostępne wyłącznie przez API.")
  },

  async issuePasswordReset() {
    throw new Error("Reset hasła jest dostępny wyłącznie przez API.")
  },
}

export const mockAdministrationSettingsRepository: AdministrationSettingsRepository = {
  async get() {
    requireCurrentAdministrator()
    return delay(settingsState)
  },

  async saveSection(key, value) {
    const permissionBySection: Partial<
      Record<keyof AdministrationSettings, AdministrationPermission>
    > = {
      sla: "manage_sla",
      schedule: "manage_schedule",
      outOfOffice: "manage_schedule",
      integrations: "manage_integrations",
      channels: "manage_integrations",
      notifications: "manage_notifications",
      rolePermissions: "manage_users",
    }
    const requiredPermission = permissionBySection[key]
    if (requiredPermission) {
      requireCurrentAdministrationPermission(requiredPermission)
    } else {
      requireCurrentAdministrator()
    }
    settingsState[key] = structuredClone(value) as never
    return delay(settingsState[key])
  },

  async configureIntegration(input) {
    requireCurrentAdministrationPermission("manage_integrations")
    const integration = input.id
      ? settingsState.integrations.find((item) => item.id === input.id)
      : undefined
    const configured: ManagedIntegration = integration ?? {
      id: `int-${Date.now()}`,
      platform: input.platform,
      displayName: input.displayName,
      status: "configuring",
      workspace: input.workspaceName,
      workspaceId: input.workspaceId,
      hasSecretReference: true,
      health: "unknown",
    }
    configured.displayName = input.displayName
    configured.workspace = input.workspaceName
    configured.workspaceId = input.workspaceId
    configured.hasSecretReference = true
    configured.status = "configuring"
    configured.health = "unknown"
    if (!integration) settingsState.integrations.push(configured)
    return delay(configured)
  },

  async setIntegrationStatus(id, status) {
    requireCurrentAdministrationPermission("manage_integrations")
    const integration = settingsState.integrations.find((item) => item.id === id)
    if (!integration) throw new Error("Nie znaleziono integracji.")
    integration.status = status
    return delay(integration)
  },

  async testIntegration(id) {
    requireCurrentAdministrationPermission("manage_integrations")
    const integration = settingsState.integrations.find((item) => item.id === id)
    if (!integration) throw new Error("Nie znaleziono integracji.")
    if (integration.status === "disabled") {
      throw new Error("Najpierw włącz integrację.")
    }
    if (integration.status === "configuring") {
      throw new Error("Najpierw dokończ konfigurację integracji.")
    }
    integration.health = "healthy"
    integration.lastEventAt = new Date().toISOString()
    return delay(integration, 600)
  },

  async discoverChannels() {
    requireCurrentAdministrationPermission("manage_integrations")
    return delay(settingsState.channels)
  },

  async configureChannel(id, customerId, ignored) {
    requireCurrentAdministrationPermission("manage_integrations")
    const channel = settingsState.channels.find((item) => item.id === id)
    if (!channel) throw new Error("Nie znaleziono kanału.")
    const customer = settingsState.customers.find((item) => item.id === customerId)
    channel.customerId = customer?.id
    channel.customer = customer?.name ?? "Nie przypisano"
    channel.ignored = ignored
    return delay(undefined)
  },

  async saveCustomer(input, id) {
    requireCurrentAdministrationPermission("manage_integrations")
    const existing = id ? settingsState.customers.find((item) => item.id === id) : undefined
    const customer = existing ?? { id: `customer-${Date.now()}`, name: "", active: true }
    customer.name = input.name
    customer.externalRef = input.externalRef
    if (!existing) settingsState.customers.push(customer)
    return delay(customer)
  },

  async deactivateCustomer(id) {
    requireCurrentAdministrationPermission("manage_integrations")
    const customer = settingsState.customers.find((item) => item.id === id)
    if (!customer) throw new Error("Nie znaleziono klienta.")
    customer.active = false
    return delay(customer)
  },

  async toggleNotification(id, enabled) {
    requireCurrentAdministrationPermission("manage_notifications")
    const destination = settingsState.notifications.find((item) => item.id === id)
    if (!destination) throw new Error("Nie znaleziono celu powiadomień.")
    destination.enabled = enabled
    return delay(undefined)
  },

  async replayNotificationDelivery() {
    throw new Error("Ponowienie dostawy jest dostępne wyłącznie przez API.")
  },
}

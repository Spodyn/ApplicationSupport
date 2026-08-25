import type { Channel, UserPresence } from "./shared"

export const administrationRoles = ["USER", "ADMIN"] as const

export type AdministrationRole = (typeof administrationRoles)[number]

export const administrationPermissions = [
  "manage_users",
  "manage_integrations",
  "manage_sla",
  "manage_schedule",
  "manage_notifications",
  "view_global_statistics",
  "reassign_cases",
  "force_resolve",
  "view_audit",
] as const

export type AdministrationPermission = (typeof administrationPermissions)[number]

export interface AdministrationUser {
  id: string
  fullName: string
  email: string
  role: AdministrationRole
  active: boolean
  presence: UserPresence
  validFrom: string
  validUntil?: string
  activeAssignedCases: number
  lastLoginAt?: string
  permissions: AdministrationPermission[]
}

export type AdministrationUserInput = Omit<
  AdministrationUser,
  "id" | "presence" | "activeAssignedCases" | "lastLoginAt"
>

export interface GeneralSettings {
  organizationName: string
  interfaceLanguage: "pl"
  compactMode: boolean
}

export interface SlaSettings {
  firstResponseMinutes: number
  unclaimedReminderMinutes: number
  inProgressReminderMinutes: number
  warningBeforeDeadlineMinutes: number
  repeatedBreachMinutes: number
  businessHoursOnly: boolean
  pauseWhileWaiting: boolean
}

export interface WorkDay {
  key: "mon" | "tue" | "wed" | "thu" | "fri" | "sat" | "sun"
  label: string
  enabled: boolean
  start: string
  end: string
  breakEnabled: boolean
  breakStart: string
  breakEnd: string
}

export interface ScheduleException {
  id: string
  date: string
  name: string
  closed: boolean
  start?: string
  end?: string
}

export interface WorkScheduleSettings {
  timezone: string
  days: WorkDay[]
  exceptions: ScheduleException[]
}

export interface OutOfOfficeSettings {
  enabled: boolean
  template: string
  sendOncePerClosure: boolean
}

export type IntegrationStatus = "connected" | "disconnected" | "reauthorization"
export type IntegrationHealth = "healthy" | "degraded" | "unavailable"

export interface ManagedIntegration {
  id: string
  platform: Channel
  status: IntegrationStatus
  workspace: string
  lastEventAt?: string
  health: IntegrationHealth
}

export interface ManagedChannel {
  id: string
  platform: Channel
  channelName: string
  customer: string
  ignored: boolean
  lastMessageAt?: string
}

export type NotificationType =
  | "unclaimed_too_long"
  | "in_progress_too_long"
  | "sla_warning"
  | "sla_breached"
  | "integration_disconnected"

export interface NotificationDestination {
  id: string
  name: string
  provider: Channel
  integrationId: string
  channelName: string
  types: NotificationType[]
  enabled: boolean
}

export interface RolePermissions {
  role: AdministrationRole
  permissions: AdministrationPermission[]
}

export interface AdministrationSettings {
  general: GeneralSettings
  sla: SlaSettings
  schedule: WorkScheduleSettings
  outOfOffice: OutOfOfficeSettings
  integrations: ManagedIntegration[]
  channels: ManagedChannel[]
  notifications: NotificationDestination[]
  rolePermissions: RolePermissions[]
}

export const administrationPermissionLabels: Record<AdministrationPermission, string> = {
  manage_users: "Zarządzanie użytkownikami",
  manage_integrations: "Zarządzanie integracjami i kanałami",
  manage_sla: "Konfiguracja SLA",
  manage_schedule: "Konfiguracja godzin pracy i poza biurem",
  manage_notifications: "Zarządzanie powiadomieniami",
  view_global_statistics: "Statystyki globalne",
  reassign_cases: "Przepisywanie i odpinanie spraw",
  force_resolve: "Administracyjne rozwiązanie sprawy",
  view_audit: "Dostęp do audytu",
}

export const allAdministrationPermissions: readonly AdministrationPermission[] =
  administrationPermissions

export function hasAdministrationPermission(
  principal: Pick<AdministrationUser, "role" | "permissions"> | undefined,
  permission: AdministrationPermission,
) {
  return principal?.role === "ADMIN" && principal.permissions.includes(permission)
}

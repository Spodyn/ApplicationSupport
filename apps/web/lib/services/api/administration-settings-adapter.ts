import type {
  AdministrationSettings,
  ChannelGroupingStrategy,
  ManagedChannel,
  ManagedCustomer,
  ManagedIntegration,
  NotificationDestination,
  NotificationDelivery,
  NotificationType,
  ScheduleException,
  WorkScheduleSettings,
} from "@/lib/domain/administration"
import type { Channel } from "@/lib/domain/shared"
import type { AdministrationSettingsRepository } from "@/lib/services/administration"
import { mapApiChannel } from "./channel-adapter"
import { browserApiTransport } from "./http-transport"

type ApiProvider = "SLACK" | "TEAMS" | "TELEGRAM"

type ApiIntegration = {
  id: string
  provider: ApiProvider
  displayName: string
  status: "CONFIGURING" | "ENABLED" | "DISABLED"
  health: "UNKNOWN" | "HEALTHY" | "DEGRADED" | "UNAVAILABLE"
  workspaceExternalId?: string | null
  workspaceName?: string | null
  secretConfigured: boolean
  lastEventAt?: string | null
  lastErrorCode?: string | null
}

type ApiIntegrationTest = {
  integration: ApiIntegration
  outcome: "SUCCESS" | "TIMEOUT" | "UNAUTHORIZED" | "UNAVAILABLE"
  errorCode?: string | null
}

type ApiCustomer = {
  id: string
  name: string
  externalRef?: string | null
  active: boolean
}

type ApiChannelRecord = {
  id: string
  integrationId: string
  provider: ApiProvider
  externalChannelId: string
  name: string
  customerId?: string | null
  customerName?: string | null
  ignored: boolean
  groupingStrategy: ChannelGroupingStrategy
  active: boolean
  lastMessageAt?: string | null
}

export type ApiBusinessHoursInterval = {
  dayOfWeek: number
  start: string
  end: string
}

export type ApiBusinessHoursSchedule = {
  id: string
  timezone: string
  active: boolean
  intervals: ApiBusinessHoursInterval[]
  exceptions?: {
    date: string
    type: "CLOSED" | "OPEN" | "OVERRIDE"
    start?: string | null
    end?: string | null
    note?: string | null
  }[]
  updatedBy: string
  updatedAt: string
}

type ApiNotificationDestination = {
  id: string
  name: string
  provider: ApiProvider
  integrationId: string
  targetRef: string
  enabled: boolean
  secretConfigured: boolean
  configConfigured: boolean
  version: number
  createdAt: string
  updatedAt: string
}

type ApiNotificationRule = {
  id: string
  destinationId: string
  name: string
  enabled: boolean
  eventTypes: string[]
  severityFilters: string[]
  version: number
  createdAt: string
  updatedAt: string
}

type ApiNotificationDelivery = NotificationDelivery & {
  lastErrorCode?: string | null
  terminalReason?: string | null
  sentAt?: string | null
}

type ApiOutOfOfficePolicy = {
  enabled: boolean
  template: string
  sendOncePerClosure: boolean
  version: number
  updatedAt: string
  updatedBy: string
}

type ApiOutOfOfficePreview = {
  message: string
  nextOpening?: string | null
  timezone: string
}

type ApiSlaPolicy = {
  id: string
  name: string
  firstResponseMinutes: number
  unclaimedWarningMinutes: number
  unclaimedBreachMinutes: number
  inProgressWarningMinutes: number
  inProgressBreachMinutes: number
  pauseWaiting: boolean
  version: number
  updatedAt: string
}

type ApiOrganizationSettings = {
  general: AdministrationSettings["general"]
  rolePermissions: AdministrationSettings["rolePermissions"]
  warningBeforeDeadlineMinutes: number
  businessHoursOnly: boolean
}

const scheduleDays = [
  { dayOfWeek: 1, key: "mon", label: "Poniedziałek" },
  { dayOfWeek: 2, key: "tue", label: "Wtorek" },
  { dayOfWeek: 3, key: "wed", label: "Środa" },
  { dayOfWeek: 4, key: "thu", label: "Czwartek" },
  { dayOfWeek: 5, key: "fri", label: "Piątek" },
  { dayOfWeek: 6, key: "sat", label: "Sobota" },
  { dayOfWeek: 7, key: "sun", label: "Niedziela" },
] as const

const supportedNotificationTypes = new Set<NotificationType>([
  "unclaimed_too_long",
  "in_progress_too_long",
  "sla_warning",
  "sla_breached",
  "integration_disconnected",
])

const notificationDestinationVersions = new Map<string, number>()
let outOfOfficeVersion: number | undefined
let slaPolicyVersion: number | undefined

function mapChannel(channel: ApiChannelRecord): ManagedChannel {
  const activity = channel.active ? "Aktywny" : "Nieaktywny"
  return {
    id: channel.id,
    platform: mapApiChannel(channel.provider),
    externalChannelId: channel.externalChannelId,
    channelName: `${channel.name} · ${activity}`,
    customerId: channel.customerId ?? undefined,
    customer: channel.customerName ?? "Nie przypisano",
    ignored: channel.ignored,
    groupingStrategy: channel.groupingStrategy,
    active: channel.active,
    lastMessageAt: channel.lastMessageAt ?? undefined,
  }
}

function mapIntegration(integration: ApiIntegration): ManagedIntegration {
  return {
    id: integration.id,
    platform: mapApiChannel(integration.provider),
    displayName: integration.displayName,
    status: integration.status.toLowerCase() as ManagedIntegration["status"],
    workspace: integration.workspaceName ?? integration.workspaceExternalId ?? "",
    workspaceId: integration.workspaceExternalId ?? "",
    hasSecretReference: integration["secretConfigured"],
    lastEventAt: integration.lastEventAt ?? undefined,
    lastErrorCode: integration.lastErrorCode ?? undefined,
    health: integration.health.toLowerCase() as ManagedIntegration["health"],
  }
}

function mapCustomer(customer: ApiCustomer): ManagedCustomer {
  return {
    id: customer.id,
    name: customer.name,
    externalRef: customer.externalRef ?? undefined,
    active: customer.active,
  }
}

function apiProvider(channel: Channel): ApiProvider {
  if (channel === "slack") return "SLACK"
  if (channel === "teams") return "TEAMS"
  return "TELEGRAM"
}

function wallClock(value: string): string {
  return value.slice(0, 5)
}

function coalesceAdjacentIntervals(intervals: ApiBusinessHoursInterval[]) {
  const result: ApiBusinessHoursInterval[] = []
  for (const interval of [...intervals].sort((left, right) => left.start.localeCompare(right.start))) {
    const normalized = { ...interval, start: wallClock(interval.start), end: wallClock(interval.end) }
    const previous = result.at(-1)
    if (previous?.end === normalized.start) {
      previous.end = normalized.end
    } else {
      result.push(normalized)
    }
  }
  return result
}

export function mapApiBusinessHours(
  schedule: ApiBusinessHoursSchedule,
  exceptions: ScheduleException[] = (schedule.exceptions ?? []).map((item) => ({
    id: item.date,
    date: item.date,
    name: item.note ?? (item.type === "CLOSED" ? "Dzień wolny" : "Wyjątek godzin pracy"),
    closed: item.type === "CLOSED",
    start: item.start?.slice(0, 5) ?? undefined,
    end: item.end?.slice(0, 5) ?? undefined,
  })),
): WorkScheduleSettings {
  const days = scheduleDays.map(({ dayOfWeek, key, label }) => {
    const intervals = coalesceAdjacentIntervals(
      schedule.intervals.filter((interval) => interval.dayOfWeek === dayOfWeek),
    )
    if (intervals.length > 2) {
      throw new Error(
        `${label}: bieżący edytor obsługuje maksymalnie dwa rozłączne przedziały czasu.`,
      )
    }
    if (intervals.length === 0) {
      return {
        key,
        label,
        enabled: false,
        start: "09:00",
        end: "17:00",
        breakEnabled: false,
        breakStart: "12:00",
        breakEnd: "13:00",
      }
    }

    const first = intervals[0]
    const second = intervals[1]
    return {
      key,
      label,
      enabled: true,
      start: first.start,
      end: second?.end ?? first.end,
      breakEnabled: Boolean(second),
      breakStart: second ? first.end : "12:00",
      breakEnd: second ? second.start : "13:00",
    }
  })

  return {
    timezone: schedule.timezone,
    days,
    exceptions: structuredClone(exceptions),
  }
}

export function mapWorkScheduleToApiIntervals(
  schedule: WorkScheduleSettings,
): ApiBusinessHoursInterval[] {
  return schedule.days.flatMap((day, index) => {
    if (!day.enabled) return []
    const dayOfWeek = index + 1
    if (!day.breakEnabled) {
      return [{ dayOfWeek, start: day.start, end: day.end }]
    }
    return [
      { dayOfWeek, start: day.start, end: day.breakStart },
      { dayOfWeek, start: day.breakEnd, end: day.end },
    ]
  })
}

async function listChannels(): Promise<ManagedChannel[]> {
  const channels = await browserApiTransport.request<ApiChannelRecord[]>({
    method: "GET",
    path: "/api/v1/admin/channels",
  })
  return channels.map(mapChannel)
}

async function listIntegrations(): Promise<ManagedIntegration[]> {
  const integrations = await browserApiTransport.request<ApiIntegration[]>({
    method: "GET",
    path: "/api/v1/admin/integrations",
  })
  return integrations.map(mapIntegration)
}

async function listCustomers(): Promise<ManagedCustomer[]> {
  const customers = await browserApiTransport.request<ApiCustomer[]>({
    method: "GET",
    path: "/api/v1/admin/customers",
  })
  return customers.map(mapCustomer)
}

async function getBusinessHours(): Promise<ApiBusinessHoursSchedule> {
  return browserApiTransport.request<ApiBusinessHoursSchedule>({
    method: "GET",
    path: "/api/v1/admin/business-hours",
  })
}

async function saveBusinessHours(
  schedule: WorkScheduleSettings,
): Promise<ApiBusinessHoursSchedule> {
  return browserApiTransport.request<ApiBusinessHoursSchedule>({
    method: "PUT",
    path: "/api/v1/admin/business-hours",
    body: {
      timezone: schedule.timezone,
      intervals: mapWorkScheduleToApiIntervals(schedule),
    },
  })
}

async function saveBusinessHoursExceptions(schedule: WorkScheduleSettings) {
  return browserApiTransport.request<ApiBusinessHoursSchedule>({
    method: "PUT",
    path: "/api/v1/admin/business-hours/exceptions",
    body: schedule.exceptions.map((item) => ({
      date: item.date,
      type: item.closed ? "CLOSED" : "OVERRIDE",
      start: item.closed ? null : item.start,
      end: item.closed ? null : item.end,
      note: item.name,
    })),
  })
}

async function getOrganizationSettings() {
  return browserApiTransport.request<ApiOrganizationSettings>({ method: "GET", path: "/api/v1/admin/settings" })
}

async function getOutOfOffice(): Promise<ApiOutOfOfficePolicy> {
  return browserApiTransport.request<ApiOutOfOfficePolicy>({
    method: "GET",
    path: "/api/v1/admin/out-of-office",
  })
}

async function getOutOfOfficePreview(): Promise<ApiOutOfOfficePreview> {
  return browserApiTransport.request<ApiOutOfOfficePreview>({
    method: "GET",
    path: "/api/v1/admin/out-of-office/preview?customerName=Joanna",
  })
}

async function saveOutOfOffice(
  policy: AdministrationSettings["outOfOffice"],
): Promise<ApiOutOfOfficePolicy> {
  if (outOfOfficeVersion === undefined) {
    outOfOfficeVersion = (await getOutOfOffice()).version
  }
  const saved = await browserApiTransport.request<ApiOutOfOfficePolicy>({
    method: "PUT",
    path: "/api/v1/admin/out-of-office",
    body: {
      enabled: policy.enabled,
      template: policy.template,
      sendOncePerClosure: policy.sendOncePerClosure,
      version: outOfOfficeVersion,
    },
  })
  outOfOfficeVersion = saved.version
  return saved
}

async function getSlaPolicy(): Promise<ApiSlaPolicy> {
  return browserApiTransport.request<ApiSlaPolicy>({ method: "GET", path: "/api/v1/admin/sla-policy" })
}

async function saveSlaPolicy(settings: AdministrationSettings["sla"]): Promise<ApiSlaPolicy> {
  if (slaPolicyVersion === undefined) slaPolicyVersion = (await getSlaPolicy()).version
  const current = await getSlaPolicy()
  const saved = await browserApiTransport.request<ApiSlaPolicy>({
    method: "PUT", path: "/api/v1/admin/sla-policy", body: {
      name: current.name,
      firstResponseMinutes: settings.firstResponseMinutes,
      unclaimedWarningMinutes: settings.unclaimedReminderMinutes,
      unclaimedBreachMinutes: Math.max(settings.unclaimedReminderMinutes, settings.firstResponseMinutes),
      inProgressWarningMinutes: settings.inProgressReminderMinutes,
      inProgressBreachMinutes: Math.max(settings.inProgressReminderMinutes, settings.repeatedBreachMinutes),
      pauseWaiting: settings.pauseWhileWaiting,
      version: slaPolicyVersion,
    },
  })
  slaPolicyVersion = saved.version
  return saved
}

async function readNotificationConfiguration() {
  const [destinations, rules] = await Promise.all([
    browserApiTransport.request<ApiNotificationDestination[]>({
      method: "GET",
      path: "/api/v1/admin/notifications/destinations",
    }),
    browserApiTransport.request<ApiNotificationRule[]>({
      method: "GET",
      path: "/api/v1/admin/notifications/rules",
    }),
  ])
  notificationDestinationVersions.clear()
  for (const destination of destinations) {
    notificationDestinationVersions.set(destination.id, destination.version)
  }
  return { destinations, rules }
}

function mapNotifications(
  destinations: ApiNotificationDestination[],
  rules: ApiNotificationRule[],
): NotificationDestination[] {
  return destinations.map((destination) => {
    const types = [...new Set(
      rules
        .filter((rule) => rule.destinationId === destination.id && rule.enabled)
        .flatMap((rule) => rule.eventTypes)
        .filter((type): type is NotificationType =>
          supportedNotificationTypes.has(type as NotificationType),
        ),
    )]
    return {
      id: destination.id,
      name: destination.name,
      provider: mapApiChannel(destination.provider),
      integrationId: destination.integrationId,
      channelName: destination.targetRef,
      types,
      enabled: destination.enabled,
    }
  })
}

async function listNotifications(): Promise<NotificationDestination[]> {
  const configuration = await readNotificationConfiguration()
  return mapNotifications(configuration.destinations, configuration.rules)
}

async function listNotificationDeliveries(): Promise<NotificationDelivery[]> {
  const deliveries = await browserApiTransport.request<ApiNotificationDelivery[]>({
    method: "GET", path: "/api/v1/admin/notifications/deliveries",
  })
  return deliveries.map((item) => ({
    ...item,
    lastErrorCode: item.lastErrorCode ?? undefined,
    terminalReason: item.terminalReason ?? undefined,
    sentAt: item.sentAt ?? undefined,
  }))
}

function destinationBody(item: NotificationDestination) {
  return {
    name: item.name,
    provider: apiProvider(item.provider),
    integrationId: item.integrationId,
    targetRef: item.channelName,
    enabled: item.enabled,
  }
}

async function saveNotifications(
  desired: NotificationDestination[],
): Promise<NotificationDestination[]> {
  const current = await readNotificationConfiguration()
  const currentById = new Map(current.destinations.map((item) => [item.id, item]))
  const desiredExistingIds = new Set(
    desired.filter((item) => currentById.has(item.id)).map((item) => item.id),
  )

  for (const destination of current.destinations) {
    if (!desiredExistingIds.has(destination.id)) {
      await browserApiTransport.request<void>({
        method: "DELETE",
        path: `/api/v1/admin/notifications/destinations/${encodeURIComponent(destination.id)}?version=${destination.version}`,
      })
    }
  }

  for (const item of desired) {
    const existing = currentById.get(item.id)
    let savedDestination: ApiNotificationDestination
    if (existing) {
      savedDestination = await browserApiTransport.request<ApiNotificationDestination>({
        method: "PUT",
        path: `/api/v1/admin/notifications/destinations/${encodeURIComponent(existing.id)}`,
        body: { version: existing.version, destination: destinationBody(item) },
      })
    } else {
      savedDestination = await browserApiTransport.request<ApiNotificationDestination>({
        method: "POST",
        path: "/api/v1/admin/notifications/destinations",
        body: destinationBody(item),
      })
    }

    const existingRules = current.rules.filter(
      (rule) => rule.destinationId === existing?.id,
    )
    if (existingRules.length > 1) {
      throw new Error(
        "Ten cel ma wiele zaawansowanych reguł i nie może być edytowany w uproszczonym widoku Settings.",
      )
    }
    const existingRule = existingRules[0]
    const ruleBody = {
      destinationId: savedDestination.id,
      name: existingRule?.name ?? "Settings routing",
      enabled: existingRule?.enabled ?? true,
      eventTypes: item.types,
      severityFilters: existingRule?.severityFilters ?? [],
    }
    if (existingRule) {
      await browserApiTransport.request<ApiNotificationRule>({
        method: "PUT",
        path: `/api/v1/admin/notifications/rules/${encodeURIComponent(existingRule.id)}`,
        body: { version: existingRule.version, rule: ruleBody },
      })
    } else {
      await browserApiTransport.request<ApiNotificationRule>({
        method: "POST",
        path: "/api/v1/admin/notifications/rules",
        body: ruleBody,
      })
    }
  }

  return listNotifications()
}

export const apiAdministrationSettingsRepository: AdministrationSettingsRepository = {
  async get() {
    const [organization, integrations, customers, channels, businessHours, notifications, notificationDeliveries, outOfOffice, outOfOfficePreview, sla] = await Promise.all([
      getOrganizationSettings(),
      listIntegrations(),
      listCustomers(),
      listChannels(),
      getBusinessHours(),
      listNotifications(),
      listNotificationDeliveries(),
      getOutOfOffice(),
      getOutOfOfficePreview(),
      getSlaPolicy(),
    ])
    return {
      general: organization.general,
      rolePermissions: organization.rolePermissions,
      integrations,
      customers,
      schedule: mapApiBusinessHours(businessHours),
      channels,
      notifications,
      notificationDeliveries,
      outOfOffice: {
        enabled: outOfOffice.enabled,
        template: outOfOffice.template,
        sendOncePerClosure: outOfOffice.sendOncePerClosure,
        previewMessage: outOfOfficePreview.message,
      },
      sla: {
        firstResponseMinutes: sla.firstResponseMinutes,
        unclaimedReminderMinutes: sla.unclaimedWarningMinutes,
        inProgressReminderMinutes: sla.inProgressWarningMinutes,
        warningBeforeDeadlineMinutes: organization.warningBeforeDeadlineMinutes,
        repeatedBreachMinutes: sla.inProgressBreachMinutes,
        businessHoursOnly: organization.businessHoursOnly,
        pauseWhileWaiting: sla.pauseWaiting,
      },
    }
  },

  async saveSection<K extends keyof AdministrationSettings>(
    key: K,
    value: AdministrationSettings[K],
  ) {
    if (key === "schedule") {
      const schedule = value as WorkScheduleSettings
      await saveBusinessHours(schedule)
      return mapApiBusinessHours(await saveBusinessHoursExceptions(schedule)) as AdministrationSettings[K]
    }
    if (key === "notifications") {
      return (await saveNotifications(value as NotificationDestination[])) as AdministrationSettings[K]
    }
    if (key === "outOfOffice") {
      const saved = await saveOutOfOffice(value as AdministrationSettings["outOfOffice"])
      return {
        enabled: saved.enabled,
        template: saved.template,
        sendOncePerClosure: saved.sendOncePerClosure,
      } as AdministrationSettings[K]
    }
    if (key === "sla") {
      const saved = await saveSlaPolicy(value as AdministrationSettings["sla"])
      const existing = value as AdministrationSettings["sla"]
      await browserApiTransport.request<ApiOrganizationSettings>({
        method: "PUT", path: "/api/v1/admin/settings/sla-display", body: {
          warningBeforeDeadlineMinutes: existing.warningBeforeDeadlineMinutes,
          businessHoursOnly: existing.businessHoursOnly,
        },
      })
      return { ...existing, firstResponseMinutes: saved.firstResponseMinutes,
        unclaimedReminderMinutes: saved.unclaimedWarningMinutes,
        inProgressReminderMinutes: saved.inProgressWarningMinutes,
        repeatedBreachMinutes: saved.inProgressBreachMinutes,
        pauseWhileWaiting: saved.pauseWaiting } as AdministrationSettings[K]
    }
    if (key === "general") {
      const saved = await browserApiTransport.request<ApiOrganizationSettings>({
        method: "PUT", path: "/api/v1/admin/settings/general", body: value,
      })
      return saved.general as AdministrationSettings[K]
    }
    if (key === "rolePermissions") {
      const roles = value as AdministrationSettings["rolePermissions"]
      const saved = await browserApiTransport.request<ApiOrganizationSettings>({
        method: "PUT", path: "/api/v1/admin/settings/role-permissions", body: {
          adminPermissions: roles.find((item) => item.role === "ADMIN")?.permissions ?? [],
        },
      })
      return saved.rolePermissions as AdministrationSettings[K]
    }
    throw new Error(`Sekcja ${String(key)} nie obsługuje bezpośredniego zapisu.`)
  },

  async configureIntegration(input) {
    const body = {
      provider: apiProvider(input.platform),
      displayName: input.displayName,
      workspaceExternalId: input.workspaceId,
      workspaceName: input.workspaceName,
      secretRef: input.secretRef,
    }
    const integration = await browserApiTransport.request<ApiIntegration>({
      method: input.id ? "PUT" : "POST",
      path: input.id
        ? `/api/v1/admin/integrations/${encodeURIComponent(input.id)}`
        : "/api/v1/admin/integrations",
      body,
    })
    return mapIntegration(integration)
  },

  async setIntegrationStatus(id, status) {
    const integration = await browserApiTransport.request<ApiIntegration>({
      method: "PATCH",
      path: `/api/v1/admin/integrations/${encodeURIComponent(id)}/status`,
      body: { status: status.toUpperCase() },
    })
    return mapIntegration(integration)
  },

  async testIntegration(id) {
    const result = await browserApiTransport.request<ApiIntegrationTest>({
      method: "POST",
      path: `/api/v1/admin/integrations/${encodeURIComponent(id)}/test`,
    })
    if (result.outcome !== "SUCCESS") {
      throw new Error(result.errorCode ?? "Test połączenia nie powiódł się.")
    }
    return mapIntegration(result.integration)
  },

  async discoverChannels(id) {
    const channels = await browserApiTransport.request<ApiChannelRecord[]>({
      method: "POST",
      path: `/api/v1/admin/integrations/${encodeURIComponent(id)}/slack/discover-channels`,
    })
    return channels.map(mapChannel)
  },

  async listCustomers() {
    return (await listCustomers()).filter((customer) => customer.active).map(({ id, name }) => ({ id, name }))
  },

  async configureChannel(id, customerId, ignored, groupingStrategy) {
    const strategy = customerId && !groupingStrategy
      ? (await browserApiTransport.request<ApiChannelRecord[]>({ method: "GET", path: "/api/v1/admin/channels" })).find((channel) => channel.id === id)?.groupingStrategy
      : groupingStrategy
    await browserApiTransport.request<ApiChannelRecord>({
      method: "PATCH",
      path: `/api/v1/admin/channels/${encodeURIComponent(id)}`,
      body: customerId
        ? { ignored, customerId, groupingStrategy: strategy }
        : { ignored, clearCustomer: true },
    })
  },

  async saveCustomer(input, id) {
    const customer = await browserApiTransport.request<ApiCustomer>({
      method: id ? "PUT" : "POST",
      path: id
        ? `/api/v1/admin/customers/${encodeURIComponent(id)}`
        : "/api/v1/admin/customers",
      body: { name: input.name, externalRef: input.externalRef ?? null },
    })
    return mapCustomer(customer)
  },

  async deactivateCustomer(id) {
    const customer = await browserApiTransport.request<ApiCustomer>({
      method: "POST",
      path: `/api/v1/admin/customers/${encodeURIComponent(id)}/deactivate`,
    })
    return mapCustomer(customer)
  },

  async toggleNotification(id, enabled) {
    let version = notificationDestinationVersions.get(id)
    if (version === undefined) {
      await readNotificationConfiguration()
      version = notificationDestinationVersions.get(id)
    }
    if (version === undefined) throw new Error("Nie znaleziono celu powiadomień.")
    const saved = await browserApiTransport.request<ApiNotificationDestination>({
      method: "PATCH",
      path: `/api/v1/admin/notifications/destinations/${encodeURIComponent(id)}/enabled`,
      body: { version, enabled },
    })
    notificationDestinationVersions.set(id, saved.version)
  },

  async replayNotificationDelivery(id) {
    await browserApiTransport.request<void>({
      method: "POST", path: `/api/v1/admin/notifications/deliveries/${encodeURIComponent(id)}/replay`,
    })
  },
}

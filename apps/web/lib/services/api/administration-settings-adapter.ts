import type {
  AdministrationSettings,
  ChannelGroupingStrategy,
  ManagedChannel,
  NotificationDestination,
  NotificationType,
  ScheduleException,
  WorkScheduleSettings,
} from "@/lib/domain/administration"
import type { Channel } from "@/lib/domain/shared"
import type { AdministrationSettingsRepository } from "@/lib/services/administration"
import { mockAdministrationSettingsRepository } from "@/lib/services/administration"
import { mapApiChannel } from "./channel-adapter"
import { browserApiTransport } from "./http-transport"

type ApiProvider = "SLACK" | "TEAMS" | "TELEGRAM"

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

function mapChannel(channel: ApiChannelRecord): ManagedChannel {
  const activity = channel.active ? "Aktywny" : "Nieaktywny"
  return {
    id: channel.id,
    platform: mapApiChannel(channel.provider),
    externalChannelId: channel.externalChannelId,
    channelName: `${channel.name} · ${activity}`,
    customer: channel.customerName ?? "Nie przypisano",
    ignored: channel.ignored,
    groupingStrategy: channel.groupingStrategy,
    active: channel.active,
    lastMessageAt: channel.lastMessageAt ?? undefined,
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
  exceptions: ScheduleException[] = [],
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
    const [settings, channels, businessHours, notifications] = await Promise.all([
      mockAdministrationSettingsRepository.get(),
      listChannels(),
      getBusinessHours(),
      listNotifications(),
    ])
    return {
      ...settings,
      schedule: mapApiBusinessHours(businessHours, settings.schedule.exceptions),
      channels,
      notifications,
    }
  },

  async saveSection<K extends keyof AdministrationSettings>(
    key: K,
    value: AdministrationSettings[K],
  ) {
    if (key === "schedule") {
      const schedule = value as WorkScheduleSettings
      const saved = await saveBusinessHours(schedule)
      const mapped = mapApiBusinessHours(saved, schedule.exceptions)
      await mockAdministrationSettingsRepository.saveSection("schedule", mapped)
      return mapped as AdministrationSettings[K]
    }
    if (key === "notifications") {
      return (await saveNotifications(value as NotificationDestination[])) as AdministrationSettings[K]
    }
    return mockAdministrationSettingsRepository.saveSection(key, value)
  },

  configureIntegration(id, workspace) {
    return mockAdministrationSettingsRepository.configureIntegration(id, workspace)
  },

  setIntegrationStatus(id, status) {
    return mockAdministrationSettingsRepository.setIntegrationStatus(id, status)
  },

  testIntegration(id) {
    return mockAdministrationSettingsRepository.testIntegration(id)
  },

  async setChannelIgnored(id, ignored) {
    await browserApiTransport.request<ApiChannelRecord>({
      method: "PATCH",
      path: `/api/v1/admin/channels/${encodeURIComponent(id)}`,
      body: { ignored },
    })
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
}

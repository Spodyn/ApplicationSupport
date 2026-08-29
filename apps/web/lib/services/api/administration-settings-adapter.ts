import type {
  AdministrationSettings,
  ChannelGroupingStrategy,
  ManagedChannel,
  ScheduleException,
  WorkScheduleSettings,
} from "@/lib/domain/administration"
import type { AdministrationSettingsRepository } from "@/lib/services/administration"
import { mockAdministrationSettingsRepository } from "@/lib/services/administration"
import { mapApiChannel } from "./channel-adapter"
import { browserApiTransport } from "./http-transport"

type ApiChannelRecord = {
  id: string
  integrationId: string
  provider: "SLACK" | "TEAMS" | "TELEGRAM"
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

const scheduleDays = [
  { dayOfWeek: 1, key: "mon", label: "Poniedziałek" },
  { dayOfWeek: 2, key: "tue", label: "Wtorek" },
  { dayOfWeek: 3, key: "wed", label: "Środa" },
  { dayOfWeek: 4, key: "thu", label: "Czwartek" },
  { dayOfWeek: 5, key: "fri", label: "Piątek" },
  { dayOfWeek: 6, key: "sat", label: "Sobota" },
  { dayOfWeek: 7, key: "sun", label: "Niedziela" },
] as const

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

export const apiAdministrationSettingsRepository: AdministrationSettingsRepository = {
  async get() {
    const [settings, channels, businessHours] = await Promise.all([
      mockAdministrationSettingsRepository.get(),
      listChannels(),
      getBusinessHours(),
    ])
    return {
      ...settings,
      schedule: mapApiBusinessHours(businessHours, settings.schedule.exceptions),
      channels,
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

  toggleNotification(id, enabled) {
    return mockAdministrationSettingsRepository.toggleNotification(id, enabled)
  },
}

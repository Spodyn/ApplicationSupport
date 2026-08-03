import type {
  AnalyticsFilters,
  AnalyticsRecord,
  AnalyticsResult,
  UserPerformance,
} from "@/lib/domain/analytics"
import { mockAnalyticsRecords } from "@/mocks/analytics"
import { mockUsers } from "@/mocks/users"
import { requireCurrentAdministrationPermission } from "./administration"

export interface AnalyticsRepository {
  calculate(filters: AnalyticsFilters): Promise<AnalyticsResult>
}

function delay<T>(value: T, ms = 420) {
  return new Promise<T>((resolve) => setTimeout(() => resolve(structuredClone(value)), ms))
}

function minutesBetween(from: string, to?: string) {
  if (!to) return undefined
  return Math.max(0, (new Date(to).getTime() - new Date(from).getTime()) / 60_000)
}

function average(values: number[]) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : 0
}

function toDateKey(value: string) {
  return value.slice(0, 10)
}

function enumerateDates(from: string, to: string) {
  const dates: string[] = []
  const cursor = new Date(`${from}T12:00:00.000Z`)
  const end = new Date(`${to}T12:00:00.000Z`)
  while (cursor <= end) {
    dates.push(cursor.toISOString().slice(0, 10))
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return dates
}

function calculatePerformance(records: AnalyticsRecord[]): UserPerformance[] {
  const grouped = new Map<string, AnalyticsRecord[]>()
  for (const record of records) {
    if (!record.userId || !record.userName) continue
    grouped.set(record.userId, [...(grouped.get(record.userId) ?? []), record])
  }

  return [...grouped.entries()]
    .map(([userId, items]) => {
      const claimTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.claimedAt)
        return value === undefined ? [] : [value]
      })
      const responseTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.firstResponseAt)
        return value === undefined ? [] : [value]
      })
      return {
        userId,
        userName: items[0].userName ?? "Nieznany użytkownik",
        claimed: items.filter((item) => item.claimedAt).length,
        resolved: items.filter((item) => item.resolvedAt).length,
        askedForInformation: items.filter((item) => item.askedForInformation).length,
        ignoreVotes: items.reduce((sum, item) => sum + item.ignoreVotes, 0),
        averageClaimMinutes: average(claimTimes),
        averageResponseMinutes: average(responseTimes),
        slaPercentage: items.length ? items.filter((item) => item.slaMet).length / items.length : 0,
      }
    })
    .sort((a, b) => b.resolved - a.resolved || a.userName.localeCompare(b.userName, "pl"))
}

export const mockAnalyticsRepository: AnalyticsRepository = {
  async calculate(filters) {
    requireCurrentAdministrationPermission("view_global_statistics")
    const start = `${filters.dateFrom}T00:00:00.000Z`
    const end = `${filters.dateTo}T23:59:59.999Z`
    const allCustomers = [...new Map(mockAnalyticsRecords.map((record) => [record.customerId, { id: record.customerId, name: record.customerName }])).values()]
      .sort((a, b) => a.name.localeCompare(b.name, "pl"))
    const allChannels = [...new Map(mockAnalyticsRecords.map((record) => [record.sourceChannel, { value: record.sourceChannel, label: record.sourceChannel, platform: record.platform }])).values()]
      .sort((a, b) => a.label.localeCompare(b.label, "pl"))

    const records = mockAnalyticsRecords.filter((record) => {
      if (record.createdAt < start || record.createdAt > end) return false
      if (filters.userId && record.userId !== filters.userId) return false
      if (filters.customerId && record.customerId !== filters.customerId) return false
      if (filters.platform && record.platform !== filters.platform) return false
      if (filters.sourceChannel && record.sourceChannel !== filters.sourceChannel) return false
      return true
    })

    const claimTimes = records.flatMap((record) => {
      const value = minutesBetween(record.createdAt, record.claimedAt)
      return value === undefined ? [] : [value]
    })
    const responseTimes = records.flatMap((record) => {
      const value = minutesBetween(record.createdAt, record.firstResponseAt)
      return value === undefined ? [] : [value]
    })
    const performance = calculatePerformance(records)
    const timeSeries = enumerateDates(filters.dateFrom, filters.dateTo).map((date) => ({
      date,
      created: records.filter((record) => toDateKey(record.createdAt) === date).length,
      resolved: records.filter((record) => record.resolvedAt && toDateKey(record.resolvedAt) === date).length,
    }))

    const result: AnalyticsResult = {
      filters,
      kpis: {
        created: records.length,
        resolved: records.filter((record) => record.resolvedAt).length,
        active: records.filter((record) => !record.resolvedAt).length,
        slaMetPercentage: records.length ? records.filter((record) => record.slaMet).length / records.length : 0,
        averageClaimMinutes: average(claimTimes),
        averageFirstResponseMinutes: average(responseTimes),
      },
      timeSeries,
      sourceDistribution: (["slack", "teams", "telegram"] as const).map((platform) => ({ platform, value: records.filter((record) => record.platform === platform).length })),
      slaDistribution: [
        { state: "met", value: records.filter((record) => record.slaMet).length },
        { state: "breached", value: records.filter((record) => !record.slaMet).length },
      ],
      responseByUser: performance.map((item) => ({ userId: item.userId, userName: item.userName, minutes: item.averageResponseMinutes })),
      userPerformance: performance,
      options: {
        users: mockUsers.filter((user) => !user.fullName.startsWith("Klient")).map(({ id, fullName }) => ({ id, fullName })),
        customers: allCustomers,
        channels: allChannels,
      },
    }
    return delay(result)
  },
}

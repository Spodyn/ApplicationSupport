import type {
  AnalyticsFilters,
  AnalyticsKpis,
  AnalyticsRecord,
  AnalyticsResult,
  AnalyticsSlaState,
  BacklogAgePoint,
  ClientPerformance,
  DistributionPoint,
  HeatmapPoint,
  ResponseTimePoint,
  UserPerformance,
} from "@/lib/domain/analytics"
import type { Channel } from "@/lib/domain/shared"

export interface AnalyticsRepository {
  calculate(filters: AnalyticsFilters): Promise<AnalyticsResult>
}

const slaLabels: Record<AnalyticsSlaState, string> = {
  met: "Spełnione",
  warning: "Zagrożone",
  breached: "Przekroczone",
}

function minutesBetween(from: string, to?: string) {
  if (!to) return undefined
  return Math.max(0, (new Date(to).getTime() - new Date(from).getTime()) / 60_000)
}

function average(values: number[]) {
  return values.length ? values.reduce((sum, value) => sum + value, 0) / values.length : null
}

function percentile(values: number[], position: number) {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.max(0, Math.ceil(sorted.length * position) - 1)
  return sorted[index]
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

function matchesDimensions(record: AnalyticsRecord, filters: AnalyticsFilters) {
  if (filters.userId && record.userId !== filters.userId) return false
  if (filters.customerId && record.customerId !== filters.customerId) return false
  if (filters.platform && record.platform !== filters.platform) return false
  if (filters.sourceChannel && record.sourceChannel !== filters.sourceChannel) return false
  if (filters.status && record.status !== filters.status) return false
  if (filters.slaState && record.slaState !== filters.slaState) return false
  if (filters.priority && record.priority !== filters.priority) return false
  if (filters.tag && !record.tags.includes(filters.tag)) return false
  if (filters.team && record.team !== filters.team) return false
  return true
}

function distribution<TState extends string>(
  states: readonly TState[],
  label: (state: TState) => string,
  count: (state: TState) => number,
): DistributionPoint<TState>[] {
  const values = states.map((state) => ({ state, label: label(state), value: count(state) }))
  const total = values.reduce((sum, item) => sum + item.value, 0)
  return values.map((item) => ({ ...item, percentage: total ? item.value / total : 0 }))
}

function calculateKpis(
  createdRecords: AnalyticsRecord[],
  resolvedRecords: AnalyticsRecord[],
  ignoredRecords: AnalyticsRecord[],
  claimedRecords: AnalyticsRecord[],
  backlogRecords: AnalyticsRecord[],
): AnalyticsKpis {
  const claimTimes = createdRecords.flatMap((record) => {
    const value = minutesBetween(record.createdAt, record.claimedAt)
    return value === undefined ? [] : [value]
  })
  const responseTimes = createdRecords.flatMap((record) => {
    const value = minutesBetween(record.createdAt, record.firstResponseAt)
    return value === undefined ? [] : [value]
  })
  const resolutionTimes = resolvedRecords.flatMap((record) => {
    const value = minutesBetween(record.createdAt, record.resolvedAt)
    return value === undefined ? [] : [value]
  })
  const firstResponseMet = (record: AnalyticsRecord) => Boolean(
    record.firstResponseAt
      && record.slaDeadlineAt
      && new Date(record.firstResponseAt).getTime() <= new Date(record.slaDeadlineAt).getTime(),
  )
  const firstResponseBreached = (record: AnalyticsRecord) => Boolean(
    record.slaDeadlineAt
      && (record.firstResponseAt
        ? new Date(record.firstResponseAt).getTime() > new Date(record.slaDeadlineAt).getTime()
        : Date.now() > new Date(record.slaDeadlineAt).getTime()),
  )
  const slaEligible = createdRecords.filter((record) => firstResponseMet(record) || firstResponseBreached(record))

  return {
    created: createdRecords.length,
    resolved: resolvedRecords.length,
    ignored: ignoredRecords.length,
    claimed: claimedRecords.length,
    backlog: backlogRecords.length,
    slaFirstResponsePercentage: slaEligible.length
      ? slaEligible.filter(firstResponseMet).length / slaEligible.length
      : null,
    breachedSla: createdRecords.filter(firstResponseBreached).length,
    warningSla: createdRecords.filter((record) => record.slaState === "warning").length,
    averageClaimMinutes: average(claimTimes),
    averageFirstResponseMinutes: average(responseTimes),
    averageResolutionMinutes: average(resolutionTimes),
    notApplicable: resolvedRecords.filter((record) => record.resolutionReason === "not_applicable").length,
    spam: resolvedRecords.filter((record) => record.resolutionReason === "spam").length,
  }
}

function calculateUserPerformance(
  records: AnalyticsRecord[],
  backlogRecords: AnalyticsRecord[],
): UserPerformance[] {
  const grouped = new Map<string, AnalyticsRecord[]>()
  for (const record of records.filter((item) => !item.excludedFromUserPerformance)) {
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
      const resolutionTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.resolvedAt)
        return value === undefined ? [] : [value]
      })
      return {
        userId,
        userName: items[0].userName ?? "Nieznany użytkownik",
        claimed: items.filter((item) => item.claimedAt).length,
        resolved: items.filter((item) => item.resolutionReason === "resolved").length,
        currentlyAssigned: backlogRecords.filter((item) => item.userId === userId).length,
        notApplicable: items.filter((item) => item.resolutionReason === "not_applicable").length,
        spam: items.filter((item) => item.resolutionReason === "spam").length,
        averageClaimMinutes: average(claimTimes),
        medianResponseMinutes: percentile(responseTimes, 0.5),
        p90ResponseMinutes: percentile(responseTimes, 0.9),
        averageResolutionMinutes: average(resolutionTimes),
        slaPercentage: items.length ? items.filter((item) => item.slaState === "met").length / items.length : null,
      }
    })
    .sort((a, b) => b.resolved - a.resolved || a.userName.localeCompare(b.userName, "pl"))
}

function calculateBacklogAge(records: AnalyticsRecord[], periodEnd: string): BacklogAgePoint[] {
  const endMs = new Date(`${periodEnd}T23:59:59.999Z`).getTime()
  const buckets: BacklogAgePoint[] = [
    { bucket: "0_1h", label: "0–1 godz.", value: 0 },
    { bucket: "1_4h", label: "1–4 godz.", value: 0 },
    { bucket: "4_8h", label: "4–8 godz.", value: 0 },
    { bucket: "8_24h", label: "8–24 godz.", value: 0 },
    { bucket: "1_3d", label: "1–3 dni", value: 0 },
    { bucket: "over_3d", label: "> 3 dni", value: 0 },
  ]
  for (const record of records) {
    const ageHours = Math.max(0, (endMs - new Date(record.createdAt).getTime()) / 3_600_000)
    const index = ageHours <= 1 ? 0 : ageHours <= 4 ? 1 : ageHours <= 8 ? 2 : ageHours <= 24 ? 3 : ageHours <= 72 ? 4 : 5
    buckets[index].value += 1
  }
  return buckets
}

function calculateClientPerformance(records: AnalyticsRecord[]): ClientPerformance[] {
  const grouped = new Map<string, AnalyticsRecord[]>()
  for (const record of records) {
    grouped.set(record.customerId, [...(grouped.get(record.customerId) ?? []), record])
  }
  return [...grouped.entries()]
    .map(([customerId, items]) => {
      const responseTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.firstResponseAt)
        return value === undefined ? [] : [value]
      })
      const resolutionTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.resolvedAt)
        return value === undefined ? [] : [value]
      })
      return {
        customerId,
        customerName: items[0].customerName,
        created: items.length,
        resolved: items.filter((item) => item.resolutionReason === "resolved").length,
        notApplicable: items.filter((item) => item.resolutionReason === "not_applicable").length,
        spam: items.filter((item) => item.resolutionReason === "spam").length,
      slaPercentage: items.length ? items.filter((item) => item.slaState === "met").length / items.length : null,
        averageResponseMinutes: average(responseTimes),
        averageResolutionMinutes: average(resolutionTimes),
      }
    })
    .sort((a, b) => b.created - a.created || a.customerName.localeCompare(b.customerName, "pl"))
}

function calculateHeatmap(records: AnalyticsRecord[]): HeatmapPoint[] {
  const dayLabels = ["Pon", "Wt", "Śr", "Czw", "Pt", "Sob", "Nd"]
  const hours = [0, 3, 6, 9, 12, 15, 18, 21]
  return dayLabels.flatMap((dayLabel, dayIndex) =>
    hours.map((hour) => ({
      dayIndex,
      dayLabel,
      hour,
      value: records.filter((record) => {
        const date = new Date(record.createdAt)
        const mondayIndex = record.createdDayIndex ?? (date.getUTCDay() + 6) % 7
        return mondayIndex === dayIndex && Math.floor((record.createdHour ?? date.getUTCHours()) / 3) * 3 === hour
      }).length,
    })),
  )
}

function calculateResponseTimeSeries(records: AnalyticsRecord[], dates: string[]): ResponseTimePoint[] {
  return dates.map((date) => {
    const values = records
      .filter((record) => (record.createdDate ?? toDateKey(record.createdAt)) === date)
      .flatMap((record) => {
        const value = minutesBetween(record.createdAt, record.firstResponseAt)
        return value === undefined ? [] : [value]
      })
    return {
      date,
      averageMinutes: average(values),
      medianMinutes: percentile(values, 0.5),
      p90Minutes: percentile(values, 0.9),
    }
  })
}

function calculatePeriod(records: AnalyticsRecord[], filters: AnalyticsFilters) {
  const dimensionRecords = records.filter((record) => matchesDimensions(record, filters))
  const createdDate = (record: AnalyticsRecord) => record.createdDate ?? toDateKey(record.createdAt)
  const resolvedDate = (record: AnalyticsRecord) => record.resolvedAt
    ? record.resolvedDate ?? toDateKey(record.resolvedAt)
    : undefined
  const createdRecords = dimensionRecords.filter((record) => createdDate(record) >= filters.dateFrom && createdDate(record) <= filters.dateTo)
  const completedInRange = dimensionRecords.filter((record) => {
    const date = resolvedDate(record)
    return date !== undefined && date >= filters.dateFrom && date <= filters.dateTo
  })
  const resolvedRecords = completedInRange.filter((record) => record.status === "resolved")
  const ignoredRecords = completedInRange.filter((record) => record.status === "ignored")
  const claimedRecords = dimensionRecords.filter((record) => {
    const date = record.claimedDate ?? (record.claimedAt ? toDateKey(record.claimedAt) : undefined)
    return date !== undefined && date >= filters.dateFrom && date <= filters.dateTo
  })
  const backlogRecords = dimensionRecords.filter(
    (record) => createdDate(record) <= filters.dateTo && (!record.resolvedAt || (resolvedDate(record) ?? "") > filters.dateTo),
  )
  const dates = enumerateDates(filters.dateFrom, filters.dateTo)
  const timeSeries = dates.map((date) => {
    return {
      date,
      created: dimensionRecords.filter((record) => createdDate(record) === date).length,
      resolved: dimensionRecords.filter((record) => record.status === "resolved" && resolvedDate(record) === date).length,
      backlog: dimensionRecords.filter((record) => createdDate(record) <= date && (!record.resolvedAt || (resolvedDate(record) ?? "") > date)).length,
    }
  })
  const slaTimeSeries = dates.map((date) => ({
    date,
    met: createdRecords.filter((record) => createdDate(record) === date && record.slaState === "met").length,
    warning: createdRecords.filter((record) => createdDate(record) === date && record.slaState === "warning").length,
    breached: createdRecords.filter((record) => createdDate(record) === date && record.slaState === "breached").length,
  }))
  return {
    createdRecords,
    backlogRecords,
    kpis: calculateKpis(createdRecords, resolvedRecords, ignoredRecords, claimedRecords, backlogRecords),
    timeSeries,
    slaTimeSeries,
    responseTimeSeries: calculateResponseTimeSeries(createdRecords, dates),
  }
}

function previousPeriod(filters: AnalyticsFilters): AnalyticsFilters {
  const from = new Date(`${filters.dateFrom}T12:00:00.000Z`)
  const to = new Date(`${filters.dateTo}T12:00:00.000Z`)
  const days = Math.round((to.getTime() - from.getTime()) / 86_400_000) + 1
  const previousTo = new Date(from)
  previousTo.setUTCDate(previousTo.getUTCDate() - 1)
  const previousFrom = new Date(previousTo)
  previousFrom.setUTCDate(previousFrom.getUTCDate() - (days - 1))
  return {
    ...filters,
    dateFrom: previousFrom.toISOString().slice(0, 10),
    dateTo: previousTo.toISOString().slice(0, 10),
    comparePrevious: false,
  }
}

export function buildAnalyticsResult(
  records: AnalyticsRecord[],
  users: { id: string; fullName: string }[],
  filters: AnalyticsFilters,
  generatedAt: string,
  includeCurrentWork = false,
): AnalyticsResult {
    const current = calculatePeriod(records, filters)
    const allCustomers = [...new Map(records.map((record) => [record.customerId, { id: record.customerId, name: record.customerName }])).values()]
      .sort((a, b) => a.name.localeCompare(b.name, "pl"))
    const allChannels = [...new Map(records.map((record) => [record.sourceChannel, { value: record.sourceChannel, label: record.sourceChannel, platform: record.platform }])).values()]
      .sort((a, b) => a.label.localeCompare(b.label, "pl"))
    const allStatuses = [...new Map(records.map((record) => [record.status, { value: record.status, label: record.statusLabel }])).values()]
      .sort((a, b) => a.label.localeCompare(b.label, "pl"))
    const statusStates = [...new Set(current.createdRecords.map((record) => record.status))]
    const statusLabels = new Map(current.createdRecords.map((record) => [record.status, record.statusLabel]))

    const sourceDistribution = distribution<Channel>(
      ["slack", "teams", "telegram"],
      (platform) => ({ slack: "Slack", teams: "Microsoft Teams", telegram: "Telegram" })[platform],
      (platform) => current.createdRecords.filter((record) => record.platform === platform).length,
    )
    const statusDistribution = distribution(
      statusStates,
      (status) => statusLabels.get(status) ?? status,
      (status) => current.createdRecords.filter((record) => record.status === status).length,
    )
    const slaDistribution = distribution<AnalyticsSlaState>(
      ["met", "warning", "breached"],
      (state) => slaLabels[state],
      (state) => current.createdRecords.filter((record) => record.slaState === state).length,
    )

    const comparisonFilters = filters.comparePrevious ? previousPeriod(filters) : undefined
    const comparisonPeriod = comparisonFilters ? calculatePeriod(records, comparisonFilters) : undefined
    const result: AnalyticsResult = {
      filters,
      lastUpdatedAt: generatedAt,
      comparison: comparisonFilters && comparisonPeriod
        ? {
            filters: comparisonFilters,
            label: `${comparisonFilters.dateFrom}–${comparisonFilters.dateTo}`,
            kpis: comparisonPeriod.kpis,
          }
        : undefined,
      kpis: current.kpis,
      timeSeries: current.timeSeries,
      slaTimeSeries: current.slaTimeSeries,
      sourceDistribution,
      statusDistribution,
      slaDistribution,
      backlogAgeDistribution: calculateBacklogAge(current.backlogRecords, filters.dateTo),
      clientPerformance: calculateClientPerformance(current.createdRecords),
      heatmap: calculateHeatmap(current.createdRecords),
      responseTimeSeries: current.responseTimeSeries,
      userPerformance: calculateUserPerformance(current.createdRecords, current.backlogRecords),
      currentWork: includeCurrentWork ? current.backlogRecords.map((record) => ({
        id: record.id,
        reference: record.reference ?? record.id,
        customerName: record.customerName,
        platform: record.platform,
        sourceChannel: record.sourceChannel,
        status: record.status,
        statusLabel: record.statusLabel,
        userName: record.userName,
        createdAt: record.createdAt,
        slaState: record.slaState,
      })) : undefined,
      options: {
        users,
        customers: allCustomers,
        channels: allChannels,
        statuses: allStatuses,
        slaStates: (["met", "warning", "breached"] as const).map((value) => ({ value, label: slaLabels[value] })),
      },
    }
    return result
}

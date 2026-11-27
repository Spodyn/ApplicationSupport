import { inboxWorkflowStatusLabels } from "@/lib/domain/inbox"
import type {
  AnalyticsFilters,
  AnalyticsDatePreset,
  AnalyticsKpis,
  AnalyticsRecord,
  AnalyticsResult,
  FirstResponseOutcome,
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

const firstResponseLabels: Record<FirstResponseOutcome, string> = {
  met: "Pierwsza odpowiedź w terminie",
  breached: "Termin pierwszej odpowiedzi przekroczony",
  pending: "Oczekiwanie na pierwszą odpowiedź",
  unmeasured: "Brak wyniku SLA pierwszej odpowiedzi",
}

export function reportingDate(at: string | number, timezone: string) {
  return new Intl.DateTimeFormat("sv-SE", { timeZone: timezone, year: "numeric", month: "2-digit", day: "2-digit" })
    .format(new Date(at))
}

export function analyticsPresetRange(preset: AnalyticsDatePreset, today: string) {
  const end = new Date(`${today}T12:00:00Z`)
  if (preset === "yesterday") {
    end.setUTCDate(end.getUTCDate() - 1)
    const date = end.toISOString().slice(0, 10)
    return { from: date, to: date }
  }
  if (preset === "this_month") return { from: `${today.slice(0, 8)}01`, to: today }
  if (preset === "previous_month") {
    end.setUTCDate(0)
    const to = end.toISOString().slice(0, 10)
    return { from: `${to.slice(0, 8)}01`, to }
  }
  const days = preset === "today" ? 1 : preset === "7days" ? 7 : preset === "30days" ? 30 : 90
  end.setUTCDate(end.getUTCDate() - days + 1)
  return { from: end.toISOString().slice(0, 10), to: today }
}

function firstResponseOutcome(record: AnalyticsRecord, observedAt: number): FirstResponseOutcome {
  if (record.firstResponseOutcome) return record.firstResponseOutcome
  if (!record.slaDeadlineAt) return "unmeasured"
  const due = Date.parse(record.slaDeadlineAt)
  if (record.firstResponseAt) return Date.parse(record.firstResponseAt) <= due ? "met" : "breached"
  if (record.firstResponseStoppedAt) return Date.parse(record.firstResponseStoppedAt) >= due ? "breached" : "unmeasured"
  if (record.status === "resolved" || record.status === "ignored") return "unmeasured"
  return observedAt >= due ? "breached" : "pending"
}

function firstResponseTotals(records: AnalyticsRecord[], outcomes: ReadonlyMap<string, FirstResponseOutcome>) {
  const totals = { met: 0, breached: 0, pending: 0, unmeasured: 0 }
  for (const record of records) totals[outcomes.get(record.id) ?? "unmeasured"] += 1
  return totals
}

function firstResponsePercentage({ met, breached }: { met: number; breached: number }) {
  return met + breached ? met / (met + breached) : null
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

function statusDimension(record: AnalyticsRecord): string {
  return record.workflowStatus ?? record.status
}

function dimensionLabel(record: AnalyticsRecord): string {
  return record.workflowStatus ? inboxWorkflowStatusLabels[record.workflowStatus] : record.statusLabel
}

function matchesDimensions(record: AnalyticsRecord, filters: AnalyticsFilters, outcome?: FirstResponseOutcome) {
  if (filters.userId && record.userId !== filters.userId) return false
  if (filters.customerId && record.customerId !== filters.customerId) return false
  if (filters.platform && record.platform !== filters.platform) return false
  if (filters.sourceChannel && record.sourceChannel !== filters.sourceChannel) return false
  if (filters.status && statusDimension(record) !== filters.status) return false
  if (filters.firstResponseOutcome && outcome !== filters.firstResponseOutcome) return false
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
  outcomes: ReadonlyMap<string, FirstResponseOutcome>,
): AnalyticsKpis {
  const claimTimes = createdRecords.flatMap((record) => {
    const value = minutesBetween(record.createdAt, record.claimedAt)
    return value === undefined ? [] : [value]
  })
  const responseTimes = createdRecords.flatMap((record) => {
    const value = minutesBetween(record.firstResponseStartedAt ?? record.createdAt, record.firstResponseAt)
    return value === undefined ? [] : [value]
  })
  const resolutionTimes = resolvedRecords.flatMap((record) => {
    const value = record.resolutionWallSeconds == null
      ? minutesBetween(record.createdAt, record.resolvedAt) : record.resolutionWallSeconds / 60
    return value === undefined ? [] : [value]
  })
  const firstResponse = firstResponseTotals(createdRecords, outcomes)

  return {
    created: createdRecords.length,
    resolved: resolvedRecords.length,
    ignored: ignoredRecords.length,
    claimed: claimedRecords.length,
    backlog: backlogRecords.length,
    slaFirstResponsePercentage: firstResponsePercentage(firstResponse),
    breachedSla: firstResponse.breached,
    historicalSupportBreachedCases: createdRecords.filter((record) => record.historicalSupportBreach).length,
    nextResponseBreachedCases: createdRecords.filter((record) => record.historicalNextResponseBreach).length,
    averageClaimMinutes: average(claimTimes),
    averageFirstResponseMinutes: average(responseTimes),
    averageResolutionMinutes: average(resolutionTimes),
    averageWorkingResolutionMinutes: average(resolvedRecords.flatMap((record) =>
      record.resolutionWorkingSeconds == null ? [] : [record.resolutionWorkingSeconds / 60],
    )),
    notApplicable: resolvedRecords.filter((record) => record.resolutionReason === "not_applicable").length,
    spam: resolvedRecords.filter((record) => record.resolutionReason === "spam").length,
  }
}

function calculateUserPerformance(
  records: AnalyticsRecord[],
  backlogRecords: AnalyticsRecord[],
  outcomes: ReadonlyMap<string, FirstResponseOutcome>,
): UserPerformance[] {
  const owners = new Map<string, AnalyticsRecord[]>()
  const responders = new Map<string, AnalyticsRecord[]>()
  const names = new Map<string, string>()
  for (const record of records) {
    if (record.userId && record.userName && !record.excludedFromUserPerformance) {
      names.set(record.userId, record.userName)
      owners.set(record.userId, [...(owners.get(record.userId) ?? []), record])
    }
    if (record.firstResponderId && record.firstResponderName && record.firstResponseAt) {
      names.set(record.firstResponderId, record.firstResponderName)
      responders.set(record.firstResponderId, [...(responders.get(record.firstResponderId) ?? []), record])
    }
  }

  return [...names.entries()]
    .map(([userId, userName]) => {
      const items = owners.get(userId) ?? []
      const replies = responders.get(userId) ?? []
      const claimTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.claimedAt)
        return value === undefined ? [] : [value]
      })
      const responseTimes = replies.flatMap((item) => {
        const value = minutesBetween(item.firstResponseStartedAt ?? item.createdAt, item.firstResponseAt)
        return value === undefined ? [] : [value]
      })
      const resolutionTimes = items.flatMap((item) => {
        const value = minutesBetween(item.createdAt, item.resolvedAt)
        return value === undefined ? [] : [value]
      })
      return {
        userId,
        userName,
        claimed: items.filter((item) => item.claimedAt).length,
        resolved: items.filter((item) => item.resolutionReason === "resolved").length,
        currentlyAssigned: backlogRecords.filter((item) => item.userId === userId).length,
        notApplicable: items.filter((item) => item.resolutionReason === "not_applicable").length,
        spam: items.filter((item) => item.resolutionReason === "spam").length,
        averageClaimMinutes: average(claimTimes),
        medianResponseMinutes: percentile(responseTimes, 0.5),
        p90ResponseMinutes: percentile(responseTimes, 0.9),
        averageResolutionMinutes: average(resolutionTimes),
        slaPercentage: firstResponsePercentage(firstResponseTotals(replies, outcomes)),
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

function calculateClientPerformance(records: AnalyticsRecord[], outcomes: ReadonlyMap<string, FirstResponseOutcome>): ClientPerformance[] {
  const grouped = new Map<string, AnalyticsRecord[]>()
  for (const record of records) {
    grouped.set(record.customerId, [...(grouped.get(record.customerId) ?? []), record])
  }
  return [...grouped.entries()]
    .map(([customerId, items]) => {
      const responseTimes = items.flatMap((item) => {
        const value = minutesBetween(item.firstResponseStartedAt ?? item.createdAt, item.firstResponseAt)
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
        slaPercentage: firstResponsePercentage(firstResponseTotals(items, outcomes)),
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
        const value = minutesBetween(record.firstResponseStartedAt ?? record.createdAt, record.firstResponseAt)
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

function calculatePeriod(records: AnalyticsRecord[], filters: AnalyticsFilters, outcomes: ReadonlyMap<string, FirstResponseOutcome>) {
  const dimensionRecords = records.filter((record) => matchesDimensions(record, filters, outcomes.get(record.id)))
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
    ...firstResponseTotals(createdRecords.filter((record) => createdDate(record) === date), outcomes),
  }))
  return {
    createdRecords,
    backlogRecords,
    kpis: calculateKpis(createdRecords, resolvedRecords, ignoredRecords, claimedRecords, backlogRecords, outcomes),
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
  timezone = "UTC",
): AnalyticsResult {
    const today = reportingDate(generatedAt, timezone)
    if (filters.datePreset) {
      const range = analyticsPresetRange(filters.datePreset, today)
      filters = { ...filters, dateFrom: range.from, dateTo: range.to }
    }
    const observedAt = Date.parse(generatedAt)
    const outcomes = new Map(records.map(record => [record.id, firstResponseOutcome(record, observedAt)]))
    const current = calculatePeriod(records, filters, outcomes)
    const allCustomers = [...new Map(records.map((record) => [record.customerId, { id: record.customerId, name: record.customerName }])).values()]
      .sort((a, b) => a.name.localeCompare(b.name, "pl"))
    const allChannels = [...new Map(records.map((record) => [record.sourceChannel, { value: record.sourceChannel, label: record.sourceChannel, platform: record.platform }])).values()]
      .sort((a, b) => a.label.localeCompare(b.label, "pl"))
    const allStatuses = [...new Map(records.map((record) => [statusDimension(record), { value: statusDimension(record), label: dimensionLabel(record) }])).values()]
      .sort((a, b) => a.label.localeCompare(b.label, "pl"))
    const statusStates = [...new Set(current.createdRecords.map(statusDimension))]
    const statusLabels = new Map(current.createdRecords.map((record) => [statusDimension(record), dimensionLabel(record)]))

    const sourceDistribution = distribution<Channel>(
      ["slack", "teams", "telegram"],
      (platform) => ({ slack: "Slack", teams: "Microsoft Teams", telegram: "Telegram" })[platform],
      (platform) => current.createdRecords.filter((record) => record.platform === platform).length,
    )
    const statusDistribution = distribution(
      statusStates,
      (status) => statusLabels.get(status) ?? status,
      (status) => current.createdRecords.filter((record) => statusDimension(record) === status).length,
    )
    const comparisonFilters = filters.comparePrevious ? previousPeriod(filters) : undefined
    const comparisonPeriod = comparisonFilters ? calculatePeriod(records, comparisonFilters, outcomes) : undefined
    const result: AnalyticsResult = {
      filters,
      lastUpdatedAt: generatedAt,
      reportingTimezone: timezone,
      reportingDate: today,
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
      slaAttention: {
        overdueSupportResponses: current.createdRecords.filter(record =>
          (record.operationalDeadlineKind === "FRT" || record.operationalDeadlineKind === "NRT")
            && record.operationalDeadlineState === "BREACHED",
        ).length,
        dueCustomerFollowUps: current.createdRecords.filter(record =>
          record.operationalDeadlineKind === "FOLLOW_UP" && record.operationalDeadlineState === "FOLLOW_UP_DUE",
        ).length,
      },
      backlogAgeDistribution: calculateBacklogAge(current.backlogRecords, filters.dateTo),
      clientPerformance: calculateClientPerformance(current.createdRecords, outcomes),
      heatmap: calculateHeatmap(current.createdRecords),
      responseTimeSeries: current.responseTimeSeries,
      userPerformance: calculateUserPerformance(current.createdRecords, current.backlogRecords, outcomes),
      currentWork: includeCurrentWork ? current.backlogRecords.map((record) => ({
        id: record.id,
        reference: record.reference ?? record.id,
        customerName: record.customerName,
        platform: record.platform,
        sourceChannel: record.sourceChannel,
        status: statusDimension(record),
        statusLabel: dimensionLabel(record),
        userName: record.userName,
        createdAt: record.createdAt,
        operationalDeadlineKind: record.operationalDeadlineKind,
        operationalDeadlineState: record.operationalDeadlineState,
      })) : undefined,
      options: {
        users,
        customers: allCustomers,
        channels: allChannels,
        statuses: allStatuses,
        firstResponseOutcomes: (Object.keys(firstResponseLabels) as FirstResponseOutcome[])
          .map(value => ({ value, label: firstResponseLabels[value] })),
      },
    }
    return result
}

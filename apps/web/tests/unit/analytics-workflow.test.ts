import { expect, it } from "vitest"
import type { AnalyticsFilters, AnalyticsRecord } from "@/lib/domain/analytics"
import { buildAnalyticsResult } from "@/lib/services/analytics"

it("retains stable customer identities in current work and filters despite equal display names", () => {
  const base: AnalyticsRecord = { id: "unassigned", createdAt: "2026-11-28T10:00:00Z",
    customerId: "00000000-0000-0000-0000-000000000000", customerName: "Nie przypisano", platform: "slack",
    sourceChannel: "sandbox", status: "verification", statusLabel: "OPEN", workflowStatus: "OPEN",
    priority: "medium", tags: [], slaState: "met" }
  const records = [base, { ...base, id: "mapped", customerId: "real-customer" }]
  const result = buildAnalyticsResult(records, [], { dateFrom: "2026-11-28", dateTo: "2026-11-28" }, "2026-11-28T11:00:00Z", true)
  expect(result.currentWork?.map(item => item.customerId)).toEqual(records.map(item => item.customerId))
  expect(result.options.customers).toHaveLength(2)
  const filtered = buildAnalyticsResult(records, [], { dateFrom: "2026-11-28", dateTo: "2026-11-28", customerId: base.customerId }, "2026-11-28T11:00:00Z", true)
  expect(filtered.currentWork?.map(item => item.id)).toEqual(["unassigned"])
  expect(filtered.kpis.created).toBe(1)
})

it("groups simplified statuses while retaining distinct resolved and ignored totals", () => {
  const createdAt = "2026-11-15T09:00:00Z"
  const base: AnalyticsRecord = {
    id: "closed", createdAt, resolvedAt: "2026-11-15T10:00:00Z", customerId: "customer",
    customerName: "Customer", platform: "slack", sourceChannel: "support", status: "resolved",
    workflowStatus: "RESOLVED", statusLabel: "Resolved", priority: "medium", tags: [], slaState: "met",
  }
  const records: AnalyticsRecord[] = [
    base,
    { ...base, id: "ignored", status: "ignored", resolutionReason: "spam" },
    { ...base, id: "open", status: "verification", workflowStatus: "OPEN", resolvedAt: undefined },
    { ...base, id: "partial", status: "partially_ignored", workflowStatus: "NEW", resolvedAt: undefined },
  ]
  const filters = { dateFrom: "2026-11-15", dateTo: "2026-11-15", status: "RESOLVED" }
  const result = buildAnalyticsResult(records, [], filters, createdAt)
  expect(result.kpis).toMatchObject({ created: 2, resolved: 1, ignored: 1 })
  expect(result.statusDistribution).toEqual([expect.objectContaining({ state: "RESOLVED", value: 2 })])
  expect(result.options.statuses.map(({ value }) => value).sort()).toEqual(["NEW", "OPEN", "RESOLVED"])
})

it("reports both TTR clocks and measures FRT from provider time", () => {
  const base: AnalyticsRecord = {
    id: "with-snapshot", createdAt: "2026-11-15T09:00:00Z", resolvedAt: "2026-11-15T10:00:00Z",
    firstResponseAt: "2026-11-15T09:05:00Z", firstResponseStartedAt: "2026-11-15T08:55:00Z",
    slaDeadlineAt: "2026-11-15T09:10:00Z", resolutionWallSeconds: 3600, resolutionWorkingSeconds: 1200,
    customerId: "customer", customerName: "Customer", platform: "slack", sourceChannel: "support",
    status: "resolved", workflowStatus: "RESOLVED", statusLabel: "Resolved", priority: "medium", tags: [], slaState: "met",
  }
  const legacy = {...base, id: "legacy", resolutionWallSeconds: 7200, resolutionWorkingSeconds: null}
  const result = buildAnalyticsResult([base, legacy], [], {
    dateFrom: "2026-11-15", dateTo: "2026-11-15",
  }, "2026-11-15T12:00:00Z")
  expect(result.kpis).toMatchObject({
    averageFirstResponseMinutes: 10,
    averageResolutionMinutes: 90,
    averageWorkingResolutionMinutes: 20,
    slaFirstResponsePercentage: 1,
  })
})

it("does not classify a timely first reply as breached because legacy case SLA is breached", () => {
  const record: AnalyticsRecord = {
    id: "legacy-work-breach", createdAt: "2026-11-24T09:00:00Z",
    firstResponseAt: "2026-11-24T09:05:00Z", slaDeadlineAt: "2026-11-24T09:15:00Z",
    customerId: "customer", customerName: "Customer", platform: "slack", sourceChannel: "support",
    status: "verification", workflowStatus: "OPEN", statusLabel: "Open", priority: "medium",
    tags: [], slaState: "breached",
  }
  const result = buildAnalyticsResult([record], [], {
    dateFrom: "2026-11-24", dateTo: "2026-11-24",
  }, "2026-11-24T12:00:00Z")
  expect(result.kpis).toMatchObject({ breachedSla: 0, slaFirstResponsePercentage: 1 })
  expect(result.slaTimeSeries[0]).toMatchObject({ met: 1, breached: 0 })
  expect(result.clientPerformance[0].slaPercentage).toBe(1)
})

it("uses reporting dates, one measured FRT denominator, and independent breach and attention counts", () => {
  const base: AnalyticsRecord = {
    id: "original", createdAt: "2026-11-26T23:00:00Z", createdDate: "2026-11-27",
    customerId: "customer", customerName: "Customer", platform: "slack", sourceChannel: "support",
    status: "verification", statusLabel: "Open", workflowStatus: "OPEN", priority: "medium",
    tags: [], slaState: "met", userId: "current-owner", userName: "Current owner",
    slaDeadlineAt: "2026-11-26T23:15:00Z",
  }
  const at = "2026-11-26T23:10:00Z"
  const filters = { dateFrom: "", dateTo: "", datePreset: "today", comparePrevious: true } as const
  const records: AnalyticsRecord[] = [
    { ...base, id: "on-time-with-nrt-history", firstResponseAt: "2026-11-26T23:04:00Z",
      firstResponderId: "author", firstResponderName: "Original author",
      historicalSupportBreach: true, historicalNextResponseBreach: true,
      operationalDeadlineKind: "NRT", operationalDeadlineState: "BREACHED" },
    { ...base, id: "operator-follow-up", firstResponseAt: "2026-11-26T23:04:00Z",
      firstResponderId: "author", firstResponderName: "Original author", workflowStatus: "WAITING_ON_CUSTOMER",
      operationalDeadlineKind: "FOLLOW_UP", operationalDeadlineState: "FOLLOW_UP_DUE" },
    { ...base, id: "late", firstResponseAt: "2026-11-26T23:09:00Z", slaDeadlineAt: "2026-11-26T23:05:00Z",
      firstResponderId: "author", firstResponderName: "Original author", historicalSupportBreach: true },
    { ...base, id: "pending" },
    { ...base, id: "stopped-before-due", status: "resolved", workflowStatus: "RESOLVED",
      firstResponseStoppedAt: "2026-11-26T23:05:00Z", slaState: "breached" },
    { ...base, id: "previous", createdAt: "2026-11-26T22:00:00Z", createdDate: "2026-11-26",
      firstResponseAt: "2026-11-26T22:30:00Z", slaDeadlineAt: "2026-11-26T22:15:00Z", historicalSupportBreach: true },
    { ...base, id: "another-customer", customerId: "elsewhere" },
  ]
  const calculate = (rows: AnalyticsRecord[], firstResponseOutcome?: AnalyticsFilters["firstResponseOutcome"]) =>
    buildAnalyticsResult(rows, [], { ...filters, userId: "current-owner", customerId: "customer", platform: "slack",
      sourceChannel: "support", firstResponseOutcome }, at, true, "Europe/Warsaw")
  const result = calculate(records)
  expect(result.filters).toMatchObject({ dateFrom: "2026-11-27", dateTo: "2026-11-27" })
  expect(result.comparison).toMatchObject({ filters: { dateFrom: "2026-11-26", dateTo: "2026-11-26" },
    kpis: { created: 1, breachedSla: 1, historicalSupportBreachedCases: 1 } })
  expect(result.kpis).toMatchObject({ created: 5, breachedSla: 1, slaFirstResponsePercentage: 2 / 3,
    historicalSupportBreachedCases: 2, nextResponseBreachedCases: 1 })
  expect(result.slaTimeSeries).toEqual([{ date: "2026-11-27", met: 2, breached: 1, pending: 1, unmeasured: 1 }])
  expect(result.slaAttention).toEqual({ overdueSupportResponses: 1, dueCustomerFollowUps: 1 })
  expect(result.clientPerformance[0].slaPercentage).toBe(2 / 3)
  expect(result.userPerformance).toEqual(expect.arrayContaining([
    expect.objectContaining({ userId: "author", slaPercentage: 2 / 3 }),
    expect.objectContaining({ userId: "current-owner", slaPercentage: null }),
  ]))
  expect(calculate(records.map(record => record.id === "operator-follow-up"
    ? { ...record, operationalDeadlineState: "ON_TRACK" } : record)).kpis).toEqual(result.kpis)
  const breached = calculate(records, "breached")
  expect(breached.kpis).toMatchObject({ created: 1, breachedSla: 1, nextResponseBreachedCases: 0 })
  expect(breached.comparison?.kpis).toMatchObject({ created: 1, breachedSla: 1 })
})

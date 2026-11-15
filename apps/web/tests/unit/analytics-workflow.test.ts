import { expect, it } from "vitest"
import type { AnalyticsRecord } from "@/lib/domain/analytics"
import { buildAnalyticsResult } from "@/lib/services/analytics"

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

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

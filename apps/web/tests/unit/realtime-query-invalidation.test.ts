import { afterEach, expect, it, vi } from "vitest"
import { QueryClient } from "@tanstack/react-query"
import { coalescedInvalidation, realtimeQueryScopes, reconciliationScopes } from "@/lib/realtime/query-invalidation"

const caseId = "018f0000-0000-7000-8000-000000000201"
const hint = (eventType: string) => ({
  eventType, version: 1, entityId: caseId, occurredAt: "2026-11-27T10:00:00Z",
  correlationId: "test", payload: { caseId },
})
afterEach(() => vi.useRealTimers())

it("maps administrative, conversation, personal, statistics and workload dependencies", () => {
  expect(realtimeQueryScopes("/user/queue/application", hint("application.settings_changed"), "owner"))
    .toContainEqual(["administration", "resync"])
  expect(realtimeQueryScopes("/topic/cases", hint("case.claimed"), "owner"))
    .toEqual(expect.arrayContaining([["analytics"], ["administration", "users"]]))
  expect(realtimeQueryScopes("/topic/cases/" + caseId, hint("message.updated"), "owner"))
    .toContainEqual(["support-inbox", "messages", caseId])
  expect(realtimeQueryScopes("/user/queue/personal", hint("case.snooze_due"), "owner"))
    .not.toContainEqual(["analytics"])
  expect(realtimeQueryScopes("/user/queue/direct-messages", hint("direct_message.changed"), "owner"))
    .toEqual([["direct-messages", "owner"]])
  expect(realtimeQueryScopes("/user/queue/application", {}, "owner")).toBe(reconciliationScopes)
  expect(realtimeQueryScopes("/user/queue/application", {...hint("case.created"), version: 9}, "owner"))
    .toBe(reconciliationScopes)
})

it("coalesces duplicate and out-of-order bursts and disposes pending work", async () => {
  vi.useFakeTimers()
  const client = new QueryClient()
  const invalidate = vi.spyOn(client, "invalidateQueries").mockResolvedValue()
  const batch = coalescedInvalidation(client)
  for (let i = 0; i < 100; i++) {
    batch.enqueue(realtimeQueryScopes("/topic/cases", hint(i % 2 ? "case.updated" : "case.created"), "owner"))
  }
  await vi.advanceTimersByTimeAsync(100)
  expect(invalidate).toHaveBeenCalledTimes(5)
  batch.enqueue([["analytics"]])
  batch.dispose()
  await vi.advanceTimersByTimeAsync(100)
  expect(invalidate).toHaveBeenCalledTimes(5)
})

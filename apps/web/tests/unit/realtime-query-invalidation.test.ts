import { afterEach, expect, it, vi } from "vitest"
import { QueryClient, QueryObserver } from "@tanstack/react-query"
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
  expect(realtimeQueryScopes("/user/queue/application", hint("application.reminders_changed"), "owner"))
    .toEqual([["support-inbox", "sla-reminders", "owner"]])
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

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

it("refetches a snapshot dirtied by an event during its initial request", async () => {
  vi.useFakeTimers()
  const initial = deferred<number>()
  const queryFn = vi.fn().mockImplementationOnce(() => initial.promise).mockResolvedValue(2)
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}})
  const observer = new QueryObserver(client, {queryKey: ["analytics", "initial"], queryFn})
  const unsubscribe = observer.subscribe(() => {})
  const batch = coalescedInvalidation(client)
  try {
    batch.enqueue([["analytics"]])
    await vi.advanceTimersByTimeAsync(100)
    initial.resolve(1)
    await vi.advanceTimersByTimeAsync(100)
    expect(queryFn).toHaveBeenCalledTimes(2)
    expect(observer.getCurrentResult().data).toBe(2)
  } finally { batch.dispose(); unsubscribe(); client.clear() }
})

it("lets a slow refetch finish and performs one follow-up for overlapping event bursts", async () => {
  vi.useFakeTimers()
  const first = deferred<number>()
  const queryFn = vi.fn().mockImplementationOnce(() => first.promise).mockResolvedValue(2)
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}})
  client.setQueryData(["analytics", "slow"], 0)
  const observer = new QueryObserver(client, {queryKey: ["analytics", "slow"], queryFn, staleTime: Infinity})
  const unsubscribe = observer.subscribe(() => {})
  const batch = coalescedInvalidation(client)
  try {
    batch.enqueue([["analytics"]])
    await vi.advanceTimersByTimeAsync(100)
    for (let i = 0; i < 5; i++) {
      batch.enqueue([["analytics"], ["analytics", "slow"]])
      await vi.advanceTimersByTimeAsync(100)
    }
    expect(queryFn).toHaveBeenCalledTimes(1)
    first.resolve(1)
    await vi.advanceTimersByTimeAsync(100)
    expect(queryFn).toHaveBeenCalledTimes(2)
    expect(observer.getCurrentResult().data).toBe(2)
  } finally { batch.dispose(); unsubscribe(); client.clear() }
})

it("does not launch a dirty follow-up after the provider is disposed", async () => {
  vi.useFakeTimers()
  const initial = deferred<number>()
  const queryFn = vi.fn(() => initial.promise)
  const client = new QueryClient({defaultOptions: {queries: {retry: false}}})
  const observer = new QueryObserver(client, {queryKey: ["analytics", "disposed"], queryFn})
  const unsubscribe = observer.subscribe(() => {})
  const batch = coalescedInvalidation(client)
  batch.enqueue([["analytics"]])
  await vi.advanceTimersByTimeAsync(100)
  batch.dispose()
  initial.resolve(1)
  await vi.advanceTimersByTimeAsync(100)
  expect(queryFn).toHaveBeenCalledTimes(1)
  unsubscribe()
  client.clear()
})

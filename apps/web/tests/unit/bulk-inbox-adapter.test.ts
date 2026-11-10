import { describe, expect, it } from "vitest"
import { createApiInboxRepository } from "@/lib/services/api/inbox-adapter"
import type { ApiTransport, ApiTransportRequest } from "@usi/api-client/generated"

describe("bounded bulk API adapter", () => {
  it("executes bounded batches sequentially, preserving explicit IDs and stable retry keys", async () => {
    const requests: ApiTransportRequest[] = []
    let active = 0, maxActive = 0
    const transport: ApiTransport = { async request<T>(request: ApiTransportRequest) {
      active++; maxActive = Math.max(active, maxActive)
      requests.push(request)
      await Promise.resolve()
      active--
      const {caseIds} = request.body as {caseIds: string[]}
      return {items: caseIds.map((caseId, i) => ({caseId, status: i === 0 ? "SKIPPED" : "SUCCEEDED", code: i === 0 ? "ACTION_UNAVAILABLE" : "OK"}))} as T
    } }
    const repository = createApiInboxRepository(transport)
    const caseIds = Array.from({length: 205}, (_, i) => `case-${i}`)
    const input = {caseIds, action: "RESOLVE" as const, idempotencyKey: "immutable-selection"}
    const result = await repository.bulk(input)
    expect(result.map((item) => item.caseId)).toEqual(caseIds)
    expect(result.filter((item) => item.status === "SKIPPED")).toHaveLength(3)
    expect(maxActive).toBe(1)
    expect(requests.map((item) => (item.body as {caseIds: string[]}).caseIds.length)).toEqual([100,100,5])
    expect(requests.map((item) => item.headers?.["Idempotency-Key"])).toEqual(["immutable-selection:0","immutable-selection:1","immutable-selection:2"])
    await repository.bulk(input)
    expect(requests.slice(3)).toEqual(requests.slice(0,3))
  })
  it("rejects an unbounded or empty selection before issuing requests", async () => {
    const repository = createApiInboxRepository({request: async () => {throw new Error("must not call")}})
    for (const caseIds of [[], Array.from({length:1001}, (_, i) => String(i))]) {
      await expect(repository.bulk({caseIds,action:"CLAIM",idempotencyKey:"test"})).rejects.toThrow("1 to 1000")
    }
  })
})

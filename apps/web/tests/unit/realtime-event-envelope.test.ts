import { describe, expect, it } from "vitest"
import { parseRealtimeEventEnvelope } from "@/lib/realtime/event-envelope"

describe("parseRealtimeEventEnvelope", () => {
  const valid = { eventType: "case.changed", version: 1, entityId: "case-1", occurredAt: "2026-09-25T03:00:00Z", correlationId: "corr-1", payload: {} }
  it("accepts a versioned envelope", () => expect(parseRealtimeEventEnvelope(valid)).toEqual(valid))
  it("rejects malformed or incomplete envelopes", () => {
    expect(parseRealtimeEventEnvelope({ ...valid, version: 0 })).toBeNull()
    expect(parseRealtimeEventEnvelope({ ...valid, payload: [] })).toBeNull()
  })
})

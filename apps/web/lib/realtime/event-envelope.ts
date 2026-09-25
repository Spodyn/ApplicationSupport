export const REALTIME_EVENT_VERSION = 1 as const

export interface RealtimeEventEnvelope {
  eventType: string
  version: number
  entityId: string
  occurredAt: string
  correlationId: string
  payload: Record<string, unknown>
}

/** Never lets a socket payload become application truth; callers refetch on fallback. */
export function parseRealtimeEventEnvelope(value: unknown): RealtimeEventEnvelope | null {
  if (!isRecord(value)) return null
  const { eventType, version, entityId, occurredAt, correlationId, payload } = value
  if (
    typeof eventType !== "string" ||
    !eventType ||
    typeof version !== "number" ||
    !Number.isInteger(version) ||
    version < 1 ||
    typeof entityId !== "string" ||
    !entityId ||
    typeof occurredAt !== "string" ||
    Number.isNaN(Date.parse(occurredAt)) ||
    typeof correlationId !== "string" ||
    !correlationId ||
    !isRecord(payload)
  ) return null
  return { eventType, version, entityId, occurredAt, correlationId, payload }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

import { afterEach, describe, expect, it, vi } from "vitest"
import { apiAdministrationSettingsRepository as repository } from "@/lib/services/api/administration-settings-adapter"
import { mapCaseMessage } from "@/lib/services/api/inbox-adapter"
import type { CaseMessage } from "@usi/api-client/generated"

afterEach(() => vi.unstubAllGlobals())

describe("integration history recovery", () => {
  it("persists independent opt-in settings and sends both manual range modes", async () => {
    const settings = { enabled: true, automatic: false, intervalMinutes: 30, lookbackMinutes: 2880 }
    const fetch = vi.fn().mockImplementation(async (_input, init) => new Response(JSON.stringify(init.method === "GET" ? { settings, runs: [] } : settings), { headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetch)
    await repository.getResync("integration")
    await repository.saveResync("integration", { enabled: true, automatic: false, intervalMinutes: 30, lookbackMinutes: 2880 })
    await repository.startResync("integration", "from_date", "2026-11-20T08:15:00Z")
    await repository.startResync("integration", "all_history")
    expect(fetch.mock.calls.map((call) => call[1].method)).toEqual(["GET", "PUT", "POST", "POST"])
    expect(JSON.parse(fetch.mock.calls[1][1].body)).toMatchObject({ enabled: true, automatic: false, intervalMinutes: 30, lookbackMinutes: 2880 })
    expect(JSON.parse(fetch.mock.calls[2][1].body)).toEqual({ mode: "from_date", from: "2026-11-20T08:15:00Z" })
    expect(JSON.parse(fetch.mock.calls[3][1].body)).toEqual({ mode: "all_history" })
  })
  it.each(["manual", "automatic"] as const)("preserves %s provenance and both timestamps in the conversation model", (source) => {
    const message: CaseMessage = { id: "message", kind: "CUSTOMER", body: "Recovered", bodyFormat: "PLAIN_TEXT", inbound: true,
      providerCreatedAt: "2026-11-20T08:15:00Z", createdAt: "2026-11-20T10:30:00Z", recoverySource: source, deliveryStatus: null, editedAt: null, deletedAt: null, authorName: "Customer", attachments: [] }
    expect(mapCaseMessage(message)).toMatchObject({ recoverySource: source, createdAt: "2026-11-20T08:15:00Z", addedAt: "2026-11-20T10:30:00Z" })
    expect(mapCaseMessage({ ...message, recoverySource: null }).recoverySource).toBeUndefined()
  })
})

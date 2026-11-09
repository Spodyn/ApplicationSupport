import { afterEach, describe, expect, it, vi } from "vitest"
import { apiAdministrationSettingsRepository } from "@/lib/services/api/administration-settings-adapter"

describe("channel configuration transport", () => {
  afterEach(() => vi.unstubAllGlobals())

  it("sends the provider grouping together with a customer mapping", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response("{}", { headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetch)
    await apiAdministrationSettingsRepository.configureChannel("channel", "customer", true, "TELEGRAM_TOPIC")
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ customerId: "customer", ignored: true, groupingStrategy: "TELEGRAM_TOPIC" })
  })

  it("explicitly clears a customer without dropping the ignored setting", async () => {
    const fetch = vi.fn().mockResolvedValue(new Response("{}", { headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetch)
    await apiAdministrationSettingsRepository.configureChannel("channel", undefined, true)
    expect(JSON.parse(fetch.mock.calls[0][1].body)).toEqual({ clearCustomer: true, ignored: true })
  })
})

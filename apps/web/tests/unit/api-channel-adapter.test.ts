import { describe, expect, it } from "vitest"

import { mapApiChannel } from "@/lib/services/api/channel-adapter"

describe("mapApiChannel", () => {
  it.each([
    ["SLACK", "slack"],
    ["TEAMS", "teams"],
    ["TELEGRAM", "telegram"],
  ] as const)("maps %s transport DTO value to stable %s domain value", (transport, domain) => {
    expect(mapApiChannel(transport)).toBe(domain)
  })
})

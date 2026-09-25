import { describe, expect, test } from "vitest"

import { parseExternalMessage } from "../../lib/security/external-message"

describe("external message rendering policy", () => {
  test("preserves text, code, unicode and approved links without producing HTML", () => {
    expect(parseExternalMessage("Zażółć `status=503` [portal](https://example.invalid/a)")).toEqual([
      { type: "text", value: "Zażółć " },
      { type: "code", value: "status=503" },
      { type: "text", value: " " },
      { type: "link", label: "portal", href: "https://example.invalid/a" },
    ])
  })

  test("keeps executable markup and unsafe link schemes as inert text", () => {
    const parts = parseExternalMessage('<img src=x onerror=alert(1)> [x](javascript:alert(1))\u0000')

    expect(parts).toEqual([{ type: "text", value: '<img src=x onerror=alert(1)> [x](javascript:alert(1))' }])
  })
})

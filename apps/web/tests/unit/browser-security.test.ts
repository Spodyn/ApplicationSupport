import { describe, expect, it } from "vitest"
import { browserContentSecurityPolicy } from "../../config/browser-security.mjs"

describe("browser security policy", () => {
  it("permits only nonce-authorized scripts in production and blocks embedding", () => {
    const policy = browserContentSecurityPolicy("test-random-nonce", false)
    expect(policy).toContain("script-src 'self' 'nonce-test-random-nonce' 'strict-dynamic'")
    expect(policy).toContain("frame-ancestors 'none'")
    expect(policy).toContain("form-action 'self'")
    expect(policy).toContain("object-src 'none'")
    expect(policy).toContain("base-uri 'none'")
    expect(policy).not.toContain("unsafe-eval")
    expect(policy.split(";").find((item) => item.trim().startsWith("script-src"))).not.toContain("unsafe-inline")
  })

  it("limits eval and hot-reload websocket exceptions to development", () => {
    expect(browserContentSecurityPolicy("dev", true)).toContain("'unsafe-eval'")
    expect(browserContentSecurityPolicy("prod", false)).not.toContain(" ws: wss:")
  })
})

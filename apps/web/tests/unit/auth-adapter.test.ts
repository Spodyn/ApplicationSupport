import { beforeEach, describe, expect, it, vi } from "vitest"
import { apiCurrentUserRepository } from "@/lib/services/api/auth-adapter"
import { AuthenticationRequiredError } from "@/lib/services/current-user"

const session = {
  id: "018f0000-0000-7000-8000-000000000064",
  email: "agent@example.com",
  displayName: "Agent Testowy",
  role: "USER",
  createdAt: "2026-08-29T00:00:00Z",
  effectivePermissions: [],
}

describe("auth API adapter", () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    document.cookie = "XSRF-TOKEN=e2e-csrf; Path=/"
  })

  it("maps the canonical current session without browser credential storage", async () => {
    const storageWrite = vi.spyOn(Storage.prototype, "setItem")
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify(session), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      ),
    )

    await expect(apiCurrentUserRepository.get()).resolves.toMatchObject({
      id: session.id,
      fullName: "Agent Testowy",
      email: "agent@example.com",
      role: "agent",
      presence: "online",
      effectivePermissions: [],
    })
    expect(storageWrite).not.toHaveBeenCalled()
  })

  it("primes CSRF and sends the cookie token on login", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            code: "AUTHENTICATION_REQUIRED",
            title: "Authentication required",
            status: 401,
            detail: "Authentication is required to access this resource.",
            correlationId: "test-correlation",
          }),
          {
            status: 401,
            headers: { "content-type": "application/problem+json" },
          },
        ),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify(session), {
          status: 200,
          headers: { "content-type": "application/json" },
        }),
      )
    vi.stubGlobal("fetch", fetchMock)

    await apiCurrentUserRepository.login({
      email: "agent@example.com",
      password: "test-only-credential",
    })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    const loginInit = fetchMock.mock.calls[1]?.[1] as RequestInit
    expect(loginInit.method).toBe("POST")
    expect(new Headers(loginInit.headers).get("X-XSRF-TOKEN")).toBe("e2e-csrf")
  })

  it("maps a generic 401 to the service-level authentication error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            code: "AUTHENTICATION_REQUIRED",
            title: "Authentication required",
            status: 401,
            detail: "Authentication is required to access this resource.",
            correlationId: "test-correlation",
          }),
          {
            status: 401,
            headers: { "content-type": "application/problem+json" },
          },
        ),
      ),
    )

    await expect(apiCurrentUserRepository.get()).rejects.toBeInstanceOf(
      AuthenticationRequiredError,
    )
  })
})

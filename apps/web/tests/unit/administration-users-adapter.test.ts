import { beforeEach, describe, expect, it, vi } from "vitest"
import { apiAdministrationUserRepository } from "@/lib/services/api/administration-users-adapter"

const alice = {
  id: "018f0000-0000-7000-8000-000000000001",
  email: "alice@example.com",
  fullName: "Alice",
  role: "ADMIN",
  active: true,
  presence: "online",
  validFrom: null,
  validUntil: null,
  lastLoginAt: null,
  activeAssignedCases: 3,
  createdAt: "2026-10-29T08:00:00Z",
}
const bob = {
  id: "018f0000-0000-7000-8000-000000000002",
  email: "bob@example.com",
  fullName: "Bob",
  role: "USER",
  active: false,
  presence: "offline",
  validFrom: "2026-10-01T00:00:00Z",
  validUntil: null,
  lastLoginAt: null,
  activeAssignedCases: 0,
  createdAt: "2026-10-28T08:00:00Z",
}

describe("administration users API adapter", () => {
  beforeEach(() => vi.restoreAllMocks())

  it("uses live paginated directory data and maps nullable validity without mock fallbacks", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        items: [alice], nextCursor: "signed.cursor",
      }), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        items: [bob], nextCursor: null,
      }), { status: 200, headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetchMock)

    const result = await apiAdministrationUserRepository.list({ role: "USER", active: false })
    expect(result).toHaveLength(2)
    expect(result[0]).toMatchObject({
      fullName: "Alice", validFrom: "", activeAssignedCases: 3,
    })
    expect(result[1]).toMatchObject({
      email: "bob@example.com", validFrom: "2026-10-01", active: false,
    })
    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(fetchMock.mock.calls[0][0]).toContain("role=USER")
    expect(fetchMock.mock.calls[0][0]).toContain("active=false")
    expect(fetchMock.mock.calls[1][0]).toContain("cursor=signed.cursor")
  })

  it("reads current administrator permissions from the authenticated session", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(new Response(JSON.stringify({
        id: alice.id, email: alice.email, displayName: alice.fullName,
        role: "ADMIN", createdAt: alice.createdAt,
        effectivePermissions: ["manage_users", "view_audit", "unexpected_future_permission"],
      }), { status: 200, headers: { "content-type": "application/json" } }))
      .mockResolvedValueOnce(new Response(JSON.stringify({
        items: [alice], nextCursor: null,
      }), { status: 200, headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetchMock)

    const current = await apiAdministrationUserRepository.getCurrent()
    expect(current?.id).toBe(alice.id)
    expect(current?.permissions).toEqual(["manage_users", "view_audit"])
  })

  it("creates an account through the real endpoint with idempotency and no password echo", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      id: bob.id,
      email: "created@example.com",
      fullName: "Created Agent",
      role: "USER",
      active: true,
      validFrom: "2026-10-30T00:00:00Z",
    }), { status: 201, headers: { "content-type": "application/json" } }))
    vi.stubGlobal("fetch", fetchMock)

    expect(apiAdministrationUserRepository.supportsCreation).toBe(true)
    const result = await apiAdministrationUserRepository.save({
      email: "created@example.com",
      fullName: "Created Agent",
      role: "USER",
      active: true,
      validFrom: "2026-10-30",
      permissions: [],
      initialPassword: ["temporary", "test", "password", "123"].join("-"),
    }, undefined, "same-retry-key")

    expect(result).toMatchObject({
      id: bob.id, email: "created@example.com", validFrom: "2026-10-30",
    })
    expect(result).not.toHaveProperty("initialPassword")
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/admin/users")
    const options = fetchMock.mock.calls[0][1] as RequestInit
    expect(options.method).toBe("POST")
    expect(new Headers(options.headers).get("Idempotency-Key")).toBe("same-retry-key")
    expect(options.credentials).toBe("same-origin")
    expect(options.cache).toBe("no-store")
    const request = JSON.parse(String(options.body))
    expect(request.initialPassword).toBe("temporary-test-password-123")
    expect(request).not.toHaveProperty("passwordHash")
    expect(request).not.toHaveProperty("password_hash")
  })

  it("does not create a user without an initial password or stable retry key", async () => {
    const fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
    const input = {
      email: "created@example.com",
      fullName: "Created Agent",
      role: "USER" as const,
      active: true,
      validFrom: "2026-10-30",
      permissions: [],
    }
    await expect(apiAdministrationUserRepository.save(input, undefined, "retry"))
      .rejects.toThrow(/hasło początkowe/)
    await expect(apiAdministrationUserRepository.save({
      ...input, initialPassword: ["temporary", "test", "password", "123"].join("-"),
    })).rejects.toThrow(/identyfikatora/)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it("does not silently apply mock mutations to real user identifiers", async () => {
    expect(apiAdministrationUserRepository.supportsMutations).toBe(false)
    await expect(apiAdministrationUserRepository.deactivate(alice.id))
      .rejects.toThrow(/osobnych operacji administracyjnych/)
    await expect(apiAdministrationUserRepository.delete(alice.id))
      .rejects.toThrow(/osobnych operacji administracyjnych/)
  })
})

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

  it("does not silently apply mock mutations to real user identifiers", async () => {
    expect(apiAdministrationUserRepository.supportsMutations).toBe(false)
    await expect(apiAdministrationUserRepository.deactivate(alice.id))
      .rejects.toThrow(/osobnych operacji administracyjnych/)
    await expect(apiAdministrationUserRepository.delete(alice.id))
      .rejects.toThrow(/osobnych operacji administracyjnych/)
  })
})

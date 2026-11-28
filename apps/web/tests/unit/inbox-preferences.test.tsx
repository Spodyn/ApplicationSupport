import { act, renderHook } from "@testing-library/react"
import { expect, it, vi } from "vitest"
import type { ApiTransport, ApiTransportRequest } from "@usi/api-client/generated"
import { createInboxPreferencesRepository } from "@/lib/services/api/inbox-preferences-adapter"
import { InboxDraftProvider, useInboxDraft } from "@/components/cases/inbox-drafts"
import { createApiInboxRepository } from "@/lib/services/api/inbox-adapter"

it("maps account preferences without accepting a target account and propagates failures", async () => {
  const request = vi.fn().mockResolvedValue({ mode: "UNIFIED" })
  const repository = createInboxPreferencesRepository({ request } as ApiTransport)
  expect(await repository.get()).toBe("unified")
  request.mockResolvedValue({ mode: "SEPARATE_BY_PLATFORM" })
  expect(await repository.save("separate_by_platform")).toBe("separate_by_platform")
  expect(request).toHaveBeenLastCalledWith(expect.objectContaining({ method: "PUT", path: "/api/v1/users/me/inbox-preferences", body: { mode: "SEPARATE_BY_PLATFORM" } }))
  request.mockResolvedValue({ mode: "UNIFIED" })
  expect(await repository.save("unified")).toBe("unified")
  request.mockRejectedValue(new Error("offline"))
  await expect(repository.get()).rejects.toThrow("offline")
  await expect(repository.save("unified")).rejects.toThrow("offline")
})

it("retains independent text, note, File and retry drafts across scope switches within one account", () => {
  const { result, rerender, unmount } = renderHook(({ id }) => useInboxDraft(id), {
    initialProps: { id: "slack-case" }, wrapper: InboxDraftProvider,
  })
  const file = new File(["draft"], "draft.txt", { type: "text/plain" })
  act(() => result.current[1]({ body: "Slack draft", note: "Private note", files: [file], noteKey: "note-id",
    submission: { body: "Slack draft", idempotencyKey: "retry-id" }, sending: true }))
  rerender({ id: "teams-case" })
  expect(result.current[0]).toMatchObject({ body: "", note: "", files: [], sending: false })
  act(() => result.current[1]({ body: "Teams draft" }))
  rerender({ id: "slack-case" })
  expect(result.current[0]).toMatchObject({ body: "Slack draft", note: "Private note", noteKey: "note-id", sending: true, submission: { idempotencyKey: "retry-id" } })
  expect(result.current[0].files[0]).toBe(file)
  unmount()
  const nextAccount = renderHook(() => useInboxDraft("slack-case"), { wrapper: InboxDraftProvider })
  expect(nextAccount.result.current[0]).toMatchObject({ body: "", note: "", files: [], sending: false })
  nextAccount.unmount()
})

it("uses the same authoritative provider filters for paginated lists and bounded counter requests", async () => {
  const requests: ApiTransportRequest[] = []
  const counts = { all: 81, mine: 12, unassigned: 60, sla: 9 }
  const repository = createApiInboxRepository({ request: vi.fn(async (request: ApiTransportRequest) => {
    requests.push(request)
    return { items: [], quickViewCounts: counts, nextCursor: "scope-bound-cursor" }
  }) } as ApiTransport)
  const filters = { provider: "teams", search: "needle", quickView: "sla", unread: true, ownerId: "owner", actionableQueue: true } as const
  expect((await repository.list(undefined, filters, 1)).quickViewCounts).toEqual(counts)
  await repository.list("scope-bound-cursor", filters)
  expect(requests[0].query).toMatchObject({ provider: "TEAMS", search: "needle", quickView: "SLA", unread: true, ownerId: "owner", limit: 1 })
  expect(requests[1].query).toMatchObject({ ...requests[0].query, limit: 50, cursor: "scope-bound-cursor" })
})

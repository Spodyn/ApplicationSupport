import { describe, expect, it, vi } from "vitest"
import type { ApiTransport, ApiTransportRequest, CaseMessage } from "@usi/api-client/generated"
import { ApiHttpError } from "@/lib/services/api/http-transport"
import { InboxConflictError } from "@/lib/services/inbox"
import { createApiInboxRepository, InboxActionUnavailableError, mapCaseMessage } from "@/lib/services/api/inbox-adapter"

const caseId = "01a10d64-2d99-7627-813d-2bad1ab9ab55"
const messageId = "01a10d64-2da1-7555-a8cf-a27e56abbc35"
const listItem = {
  id: caseId, reference: "CASE-00000002", status: "NEW", customerId: "customer",
  customerName: "Slack Test Customer", channelId: "channel", channelName: "new-channel",
  externalChannelId: "C0C6N61M4HZ", provider: "SLACK", ownerUserId: null,
  ownerDisplayName: null, lastMessageId: messageId, lastMessagePreview: "Hello from Slack - USI test 1",
  unreadForCurrentUser: true, snoozedUntil: null, slaState: "ON_TRACK", slaDueAt: null,
  ignoreScore: 0, waitingUntil: null, createdAt: "2026-10-05T10:00:00Z",
  updatedAt: "2026-10-05T10:00:00Z", lastActivityAt: "2026-10-05T10:00:00Z",
}
const detail = {
  id: caseId, reference: listItem.reference, status: "NEW",
  customer: { id: "customer", name: listItem.customerName, externalRef: "slack-test" },
  owner: { id: null, displayName: null },
  channel: { id: "channel", name: "new-channel", externalChannelId: "C0C6N61M4HZ", groupingStrategy: "SLACK_ROOT_THREAD" },
  integration: { id: "integration", provider: "SLACK", displayName: "TestApp", workspaceExternalId: "team", workspaceName: "TestApp" },
  relatedCase: { id: null, reference: null }, personalState: { lastReadMessageId: null, lastReadAt: null, snoozedUntil: null },
  sla: null, ignoreScore: 0, availableActions: ["CLAIM", "MARK_READ"],
  claimedAt: null, waitingUntil: null, resolvedAt: null, ignoredAt: null, resolutionCategory: null,
  createdAt: listItem.createdAt, updatedAt: listItem.updatedAt, lastActivityAt: listItem.lastActivityAt, version: 1,
}
const message: CaseMessage = {
  id: messageId, kind: "CUSTOMER", body: "Hello from Slack - USI test 1", bodyFormat: "PLAIN_TEXT",
  inbound: true, deliveryStatus: null, providerCreatedAt: listItem.createdAt,
  createdAt: listItem.createdAt, editedAt: null, deletedAt: null, authorName: "Customer", attachments: [],
}

function fixture() {
  const requests: ApiTransportRequest[] = []
  const request = vi.fn(async (input: ApiTransportRequest) => {
    requests.push(input)
    if (input.path === "/api/v1/cases") return { items: [listItem], nextCursor: "cursor-2" }
    if (input.path === `/api/v1/cases/${caseId}`) return detail
    if (input.path === `/api/v1/cases/${caseId}/messages` && input.method === "GET") return { items: [message], nextCursor: null }
    if (input.path === `/api/v1/cases/${caseId}/messages` && input.method === "POST") return { messageId, deliveryStatus: "QUEUED" }
    if (input.path === `/api/v1/cases/${caseId}/ask-customer` && input.method === "POST") return { messageId, deliveryStatus: "QUEUED" }
    return {}
  })
  const repository = createApiInboxRepository({ request } as ApiTransport)
  return { requests, repository }
}

describe("real inbox API adapter", () => {
  it("maps the persisted Slack Case, detail, and inbound message through the generated API", async () => {
    const { repository, requests } = fixture()
    const page = await repository.list()
    expect(page.items).toMatchObject([{ id: caseId, reference: "CASE-00000002", platform: "slack", sourceChannel: "#new-channel", unreadForCurrentUser: true }])
    expect(page.nextCursor).toBe("cursor-2")
    expect((await repository.getCase(caseId)).availableActions).toContain("CLAIM")
    expect((await repository.getMessages(caseId)).items).toMatchObject([{ id: messageId, kind: "customer", body: message.body }])
    expect(requests.map((item) => item.method)).toEqual(["GET", "GET", "GET"])
  })

  it("sends Claim and reply with unique idempotency keys and never creates a mock message", async () => {
    const { repository, requests } = fixture()
    await repository.claim(caseId)
    await repository.sendMessage(caseId, { body: "USI test reply", idempotencyKey: "fixed-retry-key" })
    const claim = requests.find((item) => item.path.endsWith("/claim"))
    const send = requests.find((item) => item.path.endsWith("/messages") && item.method === "POST")
    expect(claim?.headers?.["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/)
    expect(send?.headers?.["Idempotency-Key"]).toBe("fixed-retry-key")
    expect(send?.body).toEqual({ body: "USI test reply", bodyFormat: "PLAIN_TEXT" })
  })

  it("queues Ask Customer with stable idempotency and optional waiting duration", async () => {
    const { repository, requests } = fixture()
    await repository.askCustomer(caseId, {
      message: "  Could you confirm the transaction?  ",
      waitingMinutes: 180,
      idempotencyKey: "ask-retry-key",
    })
    const ask = requests.find((item) => item.path.endsWith("/ask-customer"))
    expect(ask).toMatchObject({
      method: "POST",
      headers: { "Idempotency-Key": "ask-retry-key" },
      body: {
        message: "Could you confirm the transaction?",
        bodyFormat: "PLAIN_TEXT",
        waitingMinutes: 180,
      },
    })
  })

  it("maps persisted support messages as agent messages and accepts an empty real history page", async () => {
    expect(mapCaseMessage({
      ...message, kind: "SUPPORT", inbound: false, providerCreatedAt: null,
      deliveryStatus: "SENT", authorName: "Agent", body: "Support reply",
    })).toMatchObject({ kind: "support", sender: "Agent", body: "Support reply", deliveryStatus: "sent" })
    const repository = createApiInboxRepository({
      request: vi.fn().mockResolvedValue({ items: [], nextCursor: null }),
    } as ApiTransport)
    await expect(repository.getMessages(caseId)).resolves.toEqual({ items: [], nextCursor: undefined })
  })

  it("marks a rendered message read and rejects unavailable actions", async () => {
    const { repository, requests } = fixture()
    await repository.markRead(caseId, messageId)
    expect(requests[0]).toMatchObject({ method: "PUT", body: { messageId } })
    await expect(repository.snooze(caseId, "2026-10-06T00:00:00Z")).rejects.toBeInstanceOf(InboxActionUnavailableError)
    await expect(repository.sendMessage(caseId, { body: "test", attachments: [{ fileName: "file", size: "1" }] })).rejects.toBeInstanceOf(InboxActionUnavailableError)
  })
  it("propagates API list errors without falling back to mock cases", async () => {
    const failure = new ApiHttpError(503)
    const repository = createApiInboxRepository({ request: vi.fn().mockRejectedValue(failure) } as ApiTransport)
    await expect(repository.list()).rejects.toBe(failure)
  })

  it("maps only the stable already-claimed conflict", async () => {
    const conflict = new ApiHttpError(409, { code: "CASE_ALREADY_CLAIMED", title: "Claimed", status: 409, detail: "Claimed", correlationId: "test" })
    const repository = createApiInboxRepository({ request: vi.fn().mockRejectedValue(conflict) } as ApiTransport)
    await expect(repository.claim(caseId)).rejects.toBeInstanceOf(InboxConflictError)
  })

})

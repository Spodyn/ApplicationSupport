import { createApiClient } from "@usi/api-client/generated"
import type {
  ApiTransport,
  CaseDetail,
  CaseListItem,
  CaseMessage,
} from "@usi/api-client/generated"
import type { InboxCase, InboxMessage } from "@/lib/domain/inbox"
import type { SlaState } from "@/lib/domain/shared"
import {
  InboxConflictError,
  type InboxRepository,
} from "@/lib/services/inbox"
import { mapApiChannel } from "./channel-adapter"
import { ApiHttpError, browserApiTransport } from "./http-transport"

export class InboxActionUnavailableError extends Error {
  constructor() {
    super("Ta akcja nie jest jeszcze dostępna dla prawdziwych case’ów.")
    this.name = "InboxActionUnavailableError"
  }
}

const unavailable = async (): Promise<never> => {
  throw new InboxActionUnavailableError()
}

function mapSlaState(value: string | null): SlaState | "unknown" {
  switch (value) {
    case "ON_TRACK": return "on_track"
    case "WARNING": return "at_risk"
    case "BREACHED": return "breached"
    case "PAUSED": return "paused"
    default: return "unknown"
  }
}

function commonCase(input: {
  id: string
  reference: string
  status: CaseListItem["status"]
  customerId: string
  customerName: string
  channelName: string
  provider: CaseListItem["provider"]
  ownerUserId: string | null
  ownerDisplayName: string | null
  slaState: string | null
  slaDueAt: string | null
  ignoreScore: number
  waitingUntil: string | null
  createdAt: string
  updatedAt: string
  subject: string
  preview: string
  unread: boolean
  snoozedUntil: string | null
  lastMessageId?: string | null
  availableActions?: string[]
}): InboxCase {
  return {
    id: input.id,
    reference: input.reference,
    subject: input.subject,
    platform: mapApiChannel(input.provider),
    sourceChannel: input.provider === "SLACK" ? `#${input.channelName}` : input.channelName,
    customer: { id: input.customerId, name: input.customerName, contactName: input.customerName },
    status: input.status.toLowerCase() as InboxCase["status"],
    owner: input.ownerUserId && input.ownerDisplayName
      ? { id: input.ownerUserId, fullName: input.ownerDisplayName }
      : undefined,
    lastMessageId: input.lastMessageId ?? undefined,
    availableActions: input.availableActions,
    unreadForCurrentUser: input.unread,
    snoozedForCurrentUserUntil: input.snoozedUntil ?? undefined,
    currentUserRestrictedByIgnore: false,
    lastMessagePreview: input.preview,
    createdAt: input.createdAt,
    updatedAt: input.updatedAt,
    sla: { state: mapSlaState(input.slaState), dueAt: input.slaDueAt ?? undefined },
    ignoreVotes: { current: input.ignoreScore, required: 2, voters: [] },
    metadata: { priority: "Nieustalony", category: "", product: "", environment: "", tags: [] },
    waitingUntil: input.waitingUntil ?? undefined,
    activity: [],
  }
}

export function mapCaseListItem(item: CaseListItem): InboxCase {
  const preview = item.lastMessagePreview ?? ""
  return commonCase({
    ...item,
    subject: preview || item.reference,
    preview,
    unread: item.unreadForCurrentUser,
    lastMessageId: item.lastMessageId,
  })
}

export function mapCaseDetail(detail: CaseDetail): InboxCase {
  return {
    ...commonCase({
      id: detail.id,
      reference: detail.reference,
      status: detail.status,
      customerId: detail.customer.id,
      customerName: detail.customer.name,
      channelName: detail.channel.name,
      provider: detail.integration.provider,
      ownerUserId: detail.owner.id,
      ownerDisplayName: detail.owner.displayName,
      slaState: detail.sla?.state ?? null,
      slaDueAt: detail.sla?.firstResponseDueAt ?? null,
      ignoreScore: detail.ignoreScore,
      waitingUntil: detail.waitingUntil,
      createdAt: detail.createdAt,
      updatedAt: detail.updatedAt,
      subject: detail.reference,
      preview: "",
      unread: false,
      snoozedUntil: detail.personalState.snoozedUntil,
      availableActions: detail.availableActions,
    }),
    relatedCase: detail.relatedCase.reference
      ? { reference: detail.relatedCase.reference, subject: detail.relatedCase.reference }
      : undefined,
    resolutionCategory: detail.resolutionCategory ?? undefined,
  }
}

export function mapCaseMessage(message: CaseMessage): InboxMessage {
  return {
    id: message.id,
    kind: message.kind.toLowerCase() as InboxMessage["kind"],
    sender: message.authorName ?? undefined,
    body: message.body,
    createdAt: message.providerCreatedAt ?? message.createdAt,
    edited: Boolean(message.editedAt),
    deliveryStatus: message.deliveryStatus?.toLowerCase() as InboxMessage["deliveryStatus"],
    attachments: message.attachments.map((item) => ({
      id: item.id,
      fileName: item.fileName,
      size: `${item.sizeBytes} B`,
      type: item.contentType?.startsWith("image/") ? "image" : "document",
    })),
  }
}

export function createApiInboxRepository(transport: ApiTransport): InboxRepository {
  const client = createApiClient(transport)
  return {
    async list(cursor) {
      const page = await client.listCases({ cursor, limit: 50 })
      return { items: page.items.map(mapCaseListItem), nextCursor: page.nextCursor ?? undefined }
    },
    async getCase(caseId) {
      return mapCaseDetail(await client.getCaseDetail({ caseId }))
    },
    async getMessages(caseId, before) {
      const page = await client.getCaseMessages({ caseId, before, limit: 50 })
      return { items: page.items.map(mapCaseMessage), nextCursor: page.nextCursor ?? undefined }
    },
    async markRead(caseId, messageId) {
      await client.markCaseRead({ caseId, body: { messageId } })
    },
    markAllResolvedRead: unavailable,
    async claim(caseId) {
      try {
        await client.claimCase({ caseId, "Idempotency-Key": crypto.randomUUID() })
      } catch (error) {
        if (error instanceof ApiHttpError && error.status === 409 && error.problem?.code === "CASE_ALREADY_CLAIMED") {
          throw new InboxConflictError()
        }
        throw error
      }
    },
    ignore: unavailable,
    askCustomer: unavailable,
    async resolve(caseId, input) {
      await client.resolveCase({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { resolutionCategory: input.category?.trim() || null },
      })
    },
    snooze: unavailable,
    async sendMessage(caseId, input) {
      if (input.attachments?.length || input.simulateFailure) throw new InboxActionUnavailableError()
      const body = input.body.trim()
      if (!body) throw new Error("Wiadomość nie może być pusta.")
      const accepted = await client.sendCaseMessage({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { body, bodyFormat: "PLAIN_TEXT" },
      })
      const history = await client.getCaseMessages({ caseId, limit: 50 })
      const persisted = history.items.find((message) => message.id === accepted.messageId)
      if (!persisted) throw new Error("Wysłana wiadomość nie pojawiła się w historii case’u.")
      return mapCaseMessage(persisted)
    },
  }
}

export const apiInboxRepository = createApiInboxRepository(browserApiTransport)

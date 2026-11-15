import { translate as t } from "@/lib/i18n/catalog"
import { createApiClient } from "@usi/api-client/generated"
import type {
  ApiTransport,
  AttachmentUploadResponse,
  CaseDetail,
  CaseListItem,
  CaseMessage,
  CaseResolutionCategory,
} from "@usi/api-client/generated"
import type { InboxActivity, InboxCase, InboxMessage } from "@/lib/domain/inbox"
import type { SlaState } from "@/lib/domain/shared"
import {
  InboxConflictError,
  type InboxPendingAttachment,
  type InboxRepository,
} from "@/lib/services/inbox"
import { attachmentLimitError } from "@/lib/services/attachment-limits"
import { mapApiChannel } from "./channel-adapter"
import { ApiHttpError, browserApiTransport } from "./http-transport"

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
  workflowStatus?: CaseListItem["workflowStatus"]
  closureReason?: CaseListItem["closureReason"]
  customerId: string | null
  customerName: string
  channelName: string
  provider: CaseListItem["provider"]
  ownerUserId: string | null
  ownerDisplayName: string | null
  slaState: string | null
  slaDueAt: string | null
  responseSla?: CaseListItem["responseSla"]
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
    customer: { id: !input.customerId || input.customerId === "00000000-0000-0000-0000-000000000000" ? "unassigned" : input.customerId, name: input.customerName, contactName: input.customerName },
    status: input.status.toLowerCase() as InboxCase["status"],
    workflowStatus: input.workflowStatus,
    closureReason: input.closureReason ?? (input.status === "IGNORED" ? "IGNORED" : undefined),
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
    sla: {
      state: mapSlaState(input.responseSla?.state ?? input.slaState),
      dueAt: (input.responseSla ? input.responseSla.dueAt : input.slaDueAt) ?? undefined,
      kind: input.responseSla?.kind ?? undefined,
      previouslyBreached: input.responseSla?.historicallyBreached,
      remainingBusinessSeconds: input.responseSla?.remainingBusinessSeconds ?? undefined,
    },
    ignoreVotes: { current: input.ignoreScore, required: 2, voters: [] },
    metadata: { priority: "Nieustalony", category: "", product: "", environment: "", tags: [] },
    waitingUntil: input.waitingUntil ?? undefined,
    activity: [],
  }
}

export function mapCaseListItem(item: CaseListItem): InboxCase {
  const preview = item.lastMessagePreview ?? ""
  return {
    ...commonCase({
      ...item,
      subject: preview || item.reference,
      preview,
      unread: item.unreadForCurrentUser,
      lastMessageId: item.lastMessageId,
    }),
    lastMessageAt: item.lastMessageAt ?? undefined,
    lastMessageAuthor: item.lastMessageAuthor ?? undefined,
    lastMessageKind: item.lastMessageKind?.toLowerCase() as InboxCase["lastMessageKind"],
    lastMessageFile: item.lastMessageFile ?? undefined,
  }
}

export function mapCaseDetail(detail: CaseDetail): InboxCase {
  return {
    ...commonCase({
      id: detail.id,
      reference: detail.reference,
      status: detail.status,
      workflowStatus: detail.workflowStatus,
      closureReason: detail.closureReason,
      customerId: detail.customer.id,
      customerName: detail.customer.name,
      channelName: detail.channel.name,
      provider: detail.integration.provider,
      ownerUserId: detail.owner.id,
      ownerDisplayName: detail.owner.displayName,
      responseSla: detail.responseSla,
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
    snoozedForCurrentUserAt: detail.personalState.snoozedAt ?? undefined,
    relatedCase: detail.relatedCase.reference
      ? { id: detail.relatedCase.id ?? undefined, reference: detail.relatedCase.reference, subject: detail.relatedCase.reference }
      : undefined,
    resolutionCategory: detail.resolutionCategory ?? undefined,
    integration: {
      name: detail.integration.displayName,
      workspace: detail.integration.workspaceName ?? detail.integration.workspaceExternalId ?? undefined,
      externalChannelId: detail.channel.externalChannelId,
    },
  }
}

const inlineImageTypes = new Set(["image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp"])

export function mapCaseMessage(message: CaseMessage, caseId?: string): InboxMessage
export function mapCaseMessage(caseId: string, message: CaseMessage): InboxMessage
export function mapCaseMessage(first: CaseMessage | string, second?: string | CaseMessage): InboxMessage {
  const message = typeof first === "string" ? second as CaseMessage : first
  const caseId = typeof first === "string" ? first : typeof second === "string" ? second : undefined
  return {
    id: message.id,
    kind: message.kind.toLowerCase() as InboxMessage["kind"],
    sender: message.authorName ?? undefined,
    body: message.deletedAt ? "Wiadomość została usunięta w Slack." : message.body,
    createdAt: message.providerCreatedAt ?? message.createdAt,
    edited: Boolean(message.editedAt),
    deleted: Boolean(message.deletedAt),
    deliveryStatus: message.deliveryStatus?.toLowerCase() as InboxMessage["deliveryStatus"],
    retryAllowed: message.retryAllowed ?? false,
    deliveryErrorCode: message.deliveryErrorCode ?? undefined,
    nextRetryAt: message.nextRetryAt ?? undefined,
    attachments: message.attachments.map((item) => {
      const url = caseId && !message.deletedAt && item.scanStatus === "CLEAN"
        ? "/api/v1/cases/" + encodeURIComponent(caseId) + "/attachments/" + encodeURIComponent(item.id)
        : undefined
      return {
        id: item.id,
        fileName: item.fileName,
        size: formatBytes(item.sizeBytes),
        type: item.detectedContentType?.startsWith("image/") ? "image" : "document",
        scanStatus: item.scanStatus,
        failureStage: item.failureStage ?? undefined,
        processingStage: item.processingStage ?? undefined,
        requiredSlackScope: item.requiredSlackScope ?? undefined,
        downloadUrl: url,
        previewUrl: url && inlineImageTypes.has(item.detectedContentType ?? "") ? url + "/preview" : undefined,
      }
    }),
  }
}

function mapPendingAttachment(uploaded: AttachmentUploadResponse): InboxPendingAttachment {
  return {
    id: uploaded.attachmentId, fileName: uploaded.filename, size: formatBytes(uploaded.sizeBytes),
    sizeBytes: uploaded.sizeBytes, type: uploaded.contentType?.startsWith("image/") ? "image" : "document",
    scanStatus: uploaded.scanStatus.toLowerCase() as InboxPendingAttachment["scanStatus"],
    scanError: uploaded.scanError,
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

export function createApiInboxRepository(transport: ApiTransport): InboxRepository {
  const client = createApiClient(transport)
  const uploadedByDraft = new WeakMap<object, Map<File, InboxPendingAttachment>>()
  const repository: InboxRepository = {
    async bulk(input) {
      if (!input.caseIds.length || input.caseIds.length > 1000) throw new Error(t("Select 1 to 1000 Cases."))
      const results: import("../inbox").InboxBulkResult[] = []
      for (let offset = 0; offset < input.caseIds.length; offset += 100) {
        const response = await client.bulkCases({
          "Idempotency-Key": `${input.idempotencyKey}:${offset / 100}`,
          body: { caseIds: input.caseIds.slice(offset, offset + 100), action: input.action, targetUserId: input.targetUserId ?? null },
        })
        results.push(...response.items)
      }
      return results
    },
    async list(cursor, query = {}) {
      const page = await client.listCases({
        cursor,
        limit: 50,
        view: query.view?.toUpperCase() as "ACTIVE" | "SNOOZED" | undefined,
        quickView: query.quickView?.toUpperCase() as "UNASSIGNED" | "MINE" | "SLA" | "ALL" | undefined,
        mine: query.mine,
        unassigned: query.unassigned,
        unread: query.unread,
        slaAttention: query.slaAttention,
        status: query.status?.toUpperCase() as CaseListItem["status"] | undefined,
        workflowStatus: query.workflowStatus,
        actionableQueue: query.actionableQueue,
        statusScope: query.statusScope?.toUpperCase() as "ALL" | "ACTIVE" | "TERMINAL" | undefined,
        customerId: query.customerId,
        channelId: query.channelId,
        provider: query.provider?.toUpperCase() as CaseListItem["provider"] | undefined,
        ownerId: query.ownerId,
        search: query.search,
      })
      return { items: page.items.map(mapCaseListItem), nextCursor: page.nextCursor ?? undefined, quickViewCounts: page.quickViewCounts }
    },
    async getFilterOptions() {
      const options = await client.getCaseFilterOptions()
      return {
        customers: options.customers,
        channels: options.channels,
        owners: options.owners,
        providers: options.providers.map((provider) => provider.toLowerCase() as "slack" | "teams" | "telegram"),
      }
    },
    async getCase(caseId) {
      return mapCaseDetail(await client.getCaseDetail({ caseId }))
    },
    async getMessages(caseId, before) {
      const page = await client.getCaseMessages({ caseId, before, limit: 50, includeInternalNotes: true })
      return { items: page.items.map((message) => mapCaseMessage(message, caseId)), nextCursor: page.nextCursor ?? undefined }
    },
    async getActivity(caseId, cursor) {
      const page = await client.getCaseActivity({ caseId, cursor, limit: 50 })
      return {
        items: page.items.map((item): InboxActivity => ({
          id: item.id,
          label: item.actionDescription,
          createdAt: item.occurredAt,
          author: item.actorLabel,
          action: item.action,
          snoozedAt: item.snoozedAt ?? undefined,
          snoozedUntil: item.snoozedUntil ?? undefined,
          historicalSlaBreach: item.historicalSlaBreach,
        })),
        nextCursor: page.nextCursor ?? undefined,
      }
    },
    async markRead(caseId, messageId) {
      await client.markCaseRead({ caseId, body: { messageId } })
    },
    async markAllResolvedRead() {
      await client.markResolvedCasesRead({ "Idempotency-Key": crypto.randomUUID() })
    },
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
    async ignore(caseId, input) {
      await client.ignoreCase({
        caseId,
        "Idempotency-Key": crypto.randomUUID(),
        body: { reason: input.reason ?? null },
      })
    },
    async askCustomer(caseId, input) {
      await client.askCustomer({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { message: input.message, waitingMinutes: input.waitingMinutes, bodyFormat: "PLAIN_TEXT" },
      })
    },
    async resolve(caseId, input) {
      await client.resolveCase({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { resolutionCategory: input.category as CaseResolutionCategory | undefined },
      })
    },
    async snooze(caseId, until) {
      await client.snoozeCase({
        caseId,
        "Idempotency-Key": crypto.randomUUID(),
        body: { until },
      })
    },
    async cancelSnooze(caseId) {
      await client.cancelCaseSnooze({ caseId, "Idempotency-Key": crypto.randomUUID() })
    },
    async listAssignees(caseId) {
      return (await client.listCaseAssignees({ caseId })).map((item) => ({
        id: item.id,
        displayName: item.displayName,
        eligible: item.eligible,
        ignoreWeight: item.ignoreWeight,
        ignoreWeightValidUntil: item.ignoreWeightValidUntil ?? undefined,
      }))
    },
    async assign(caseId, userId, reassign) {
      const input = { caseId, "Idempotency-Key": crypto.randomUUID(), body: { targetUserId: userId } }
      if (reassign) await client.reassignCase(input)
      else await client.assignCase(input)
    },
    async unassign(caseId) {
      await client.unassignCase({ caseId, "Idempotency-Key": crypto.randomUUID() })
    },
    reopen(caseId, idempotencyKey) {
      return client.reopenCase({ caseId, "Idempotency-Key": idempotencyKey })
    },
    async forceResolve(caseId, input) {
      await client.forceResolveCase({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { resolutionCategory: input.category as CaseResolutionCategory | undefined },
      })
    },
    async addInternalNote(caseId, input) {
      await client.addCaseInternalNote({ caseId, "Idempotency-Key": input.idempotencyKey, body: { body: input.body } })
    },
    async sendMessage(caseId, input) {
      if (input.simulateFailure) throw new Error(t("Symulowanie błędu jest dostępne wyłącznie w trybie demonstracyjnym."))
      const body = input.body.trim()
      const attachments = input.attachments ?? []
      if (!body && attachments.length === 0) throw new Error(t("Wiadomość nie może być pusta."))
      const files = attachments.filter((attachment): attachment is File => attachment instanceof File)
      const pending = attachments.filter((attachment): attachment is InboxPendingAttachment => !(attachment instanceof File) && "id" in attachment && "scanStatus" in attachment)
      if (files.length + pending.length !== attachments.length) throw new Error(t("Nieobsługiwany format załącznika."))
      if (pending.some((attachment) => attachment.scanStatus !== "clean")) throw new Error(t("Wiadomość może zawierać tylko poprawnie zeskanowane załączniki."))
      const limitError = attachmentLimitError(files, pending.map((attachment) => attachment.sizeBytes))
      if (limitError) throw limitError

      // A lost response may already have committed the message. Keep the same
      // attachment IDs when the composer retries its unchanged submission.
      const uploaded = uploadedByDraft.get(input) ?? new Map<File, InboxPendingAttachment>()
      uploadedByDraft.set(input, uploaded)
      for (const file of files) {
        const previous = uploaded.get(file)
        if (previous?.scanStatus === "clean") continue
        const result = !previous
          ? await repository.uploadAttachment(caseId, file)
          : previous.scanStatus === "infected" || previous.scanError?.startsWith("POLICY_")
            ? previous
            : mapPendingAttachment(await client.retryCaseAttachmentScan({ caseId, attachmentId: previous.id }))
        uploaded.set(file, result)
        if (result.scanStatus !== "clean") {
          let key = "Trwa skanowanie pliku {{value0}}. Spróbuj ponownie za chwilę."
          if (result.scanStatus === "infected") key = "W pliku {{value0}} wykryto zagrożenie."
          else if (result.scanError?.startsWith("POLICY_")) key = "Typ pliku {{value0}} jest niedozwolony."
          else if (result.scanStatus === "error") key = "Nie udało się zeskanować pliku {{value0}}. Spróbuj ponownie."
          throw new Error(t(key, { value0: file.name }))
        }
      }
      const accepted = await client.sendCaseMessage({
        caseId,
        "Idempotency-Key": input.idempotencyKey ?? crypto.randomUUID(),
        body: { body, bodyFormat: "PLAIN_TEXT", attachmentIds: [...pending, ...uploaded.values()].map((item) => item.id) },
      })
      const history = await client.getCaseMessages({ caseId, limit: 50 })
      const persisted = history.items.find((message) => message.id === accepted.messageId)
      if (!persisted) throw new Error(t("Wysłana wiadomość nie pojawiła się w historii case’u."))
      uploadedByDraft.delete(input)
      return mapCaseMessage(persisted, caseId)
    },
    async uploadAttachment(caseId, file) {
      const body = new FormData()
      body.append("file", file)
      try {
        return mapPendingAttachment(await client.uploadCaseAttachment({ caseId, body }))
      } catch (error) {
        if (error instanceof ApiHttpError) {
          if (error.problem?.code === "ATTACHMENT_TYPE_REJECTED") {
            throw new Error(t("Typ pliku {{value0}} jest niedozwolony.", { value0: file.name }))
          }
          if (error.status === 413 || error.problem?.code === "ATTACHMENT_TOO_LARGE") {
            throw new Error(t("Pojedynczy plik nie może przekraczać 25 MiB."))
          }
        }
        throw error
      }
    },
    async removePendingAttachment(caseId, attachmentId) {
      await client.removePendingCaseAttachment({ caseId, attachmentId })
    },
    async retryMessage(messageId, idempotencyKey) {
      await client.retryMessageDelivery({
        messageId,
        "Idempotency-Key": idempotencyKey ?? crypto.randomUUID(),
      })
    },
  }
  return repository
}

export const apiInboxRepository = createApiInboxRepository(browserApiTransport)

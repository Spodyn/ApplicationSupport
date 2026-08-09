import type { InboxCase, InboxMessage } from "@/lib/domain/inbox"
import type { User } from "@/lib/domain/types"
import { mockInboxCaseRecords, mockInboxMessages } from "@/mocks/inbox"
import { mockCurrentUser } from "@/mocks/users"
import {
  getCurrentAdministrationSettingsSnapshot,
} from "./administration"

export interface InboxIgnoreInput {
  reason?: string
}

export interface InboxAskInput {
  message: string
}

export interface InboxResolveInput {
  category?: string
}

export interface InboxSendInput {
  body: string
  attachments?: { fileName: string; size: string }[]
  simulateFailure?: boolean
}

export interface InboxRepository {
  list(): Promise<InboxCase[]>
  getMessages(caseId: string): Promise<InboxMessage[]>
  markRead(caseId: string): Promise<void>
  markAllResolvedRead(): Promise<void>
  claim(caseId: string): Promise<void>
  ignore(caseId: string, input: InboxIgnoreInput): Promise<void>
  askCustomer(caseId: string, input: InboxAskInput): Promise<void>
  resolve(caseId: string, input: InboxResolveInput): Promise<void>
  snooze(caseId: string, until: string): Promise<void>
  sendMessage(caseId: string, input: InboxSendInput): Promise<InboxMessage>
}

export class InboxConflictError extends Error {
  readonly status = 409
  readonly code = "CASE_ALREADY_CLAIMED"
  readonly owner?: Pick<User, "id" | "fullName">

  constructor(owner?: Pick<User, "id" | "fullName">) {
    super(
      owner
        ? `Case został już przejęty przez: ${owner.fullName}.`
        : "Case został już przejęty przez innego agenta.",
    )
    this.name = "InboxConflictError"
    this.owner = owner
  }
}

const wait = <T,>(value: T, delay = 180) =>
  new Promise<T>((resolve) => setTimeout(() => resolve(value), delay))

const createId = (prefix: string) =>
  `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`

const getRecord = (caseId: string) => {
  const record = mockInboxCaseRecords.find((candidate) => candidate.id === caseId)
  if (!record) throw new Error("Nie znaleziono case’u.")
  return record
}

const markCurrentUserRead = (caseId: string) => {
  const record = getRecord(caseId)
  record.unreadForUserIds = record.unreadForUserIds.filter(
    (id) => id !== mockCurrentUser.id,
  )
}

const appendSystemEvent = (caseId: string, body: string) => {
  const createdAt = new Date().toISOString()
  const event: InboxMessage = {
    id: createId(`${caseId}-system`),
    kind: "system",
    body,
    createdAt,
  }
  mockInboxMessages[caseId] = [...(mockInboxMessages[caseId] ?? []), event]
  const record = getRecord(caseId)
  record.activity.push({
    id: createId(`${caseId}-activity`),
    label: body,
    createdAt,
    author: mockCurrentUser.fullName,
  })
  record.updatedAt = createdAt
}

const appendSupportMessage = (
  caseId: string,
  input: Omit<InboxSendInput, "simulateFailure">,
) => {
  const createdAt = new Date().toISOString()
  const message: InboxMessage = {
    id: createId(`${caseId}-message`),
    kind: "support",
    sender: mockCurrentUser.fullName,
    body: input.body,
    createdAt,
    attachments: input.attachments?.map((attachment, index) => ({
      id: createId(`${caseId}-attachment-${index}`),
      fileName: attachment.fileName,
      size: attachment.size,
      type: "document",
    })),
    deliveryStatus: "sent",
  }
  mockInboxMessages[caseId] = [...(mockInboxMessages[caseId] ?? []), message]
  const record = getRecord(caseId)
  record.lastMessagePreview = input.body
  record.updatedAt = createdAt
  markCurrentUserRead(caseId)
  return message
}

const canUseUnassignedActions = (caseId: string) => {
  const record = getRecord(caseId)
  return (
    !record.owner &&
    !record.restrictedUserIds.includes(mockCurrentUser.id) &&
    (record.status === "new" || record.status === "partially_ignored")
  )
}

export const mockInboxRepository: InboxRepository = {
  async list() {
    const cases = mockInboxCaseRecords.map(
      ({ unreadForUserIds, snoozedUntilByUser, restrictedUserIds, ...item }) => {
        const snoozedUntil = snoozedUntilByUser[mockCurrentUser.id]
        return {
          ...item,
          unreadForCurrentUser: unreadForUserIds.includes(mockCurrentUser.id),
          snoozedForCurrentUserUntil:
            snoozedUntil && new Date(snoozedUntil).getTime() > Date.now()
              ? snoozedUntil
              : undefined,
          currentUserRestrictedByIgnore: restrictedUserIds.includes(mockCurrentUser.id),
        }
      },
    )
    return wait(cases)
  },

  async getMessages(caseId) {
    return wait([...(mockInboxMessages[caseId] ?? [])], 120)
  },

  async markRead(caseId) {
    markCurrentUserRead(caseId)
    await wait(undefined, 80)
  },

  async markAllResolvedRead() {
    for (const record of mockInboxCaseRecords) {
      if (record.status === "resolved") {
        record.unreadForUserIds = record.unreadForUserIds.filter(
          (id) => id !== mockCurrentUser.id,
        )
      }
    }
    await wait(undefined, 180)
  },

  async claim(caseId) {
    await wait(undefined, 260)
    const record = getRecord(caseId)
    if (record.owner) {
      throw new InboxConflictError(record.owner)
    }
    if (!canUseUnassignedActions(caseId)) {
      throw new Error("Case nie może zostać przejęty w aktualnym stanie.")
    }
    record.owner = mockCurrentUser
    record.status = "verification"
    record.waitingUntil = undefined
    delete record.snoozedUntilByUser[mockCurrentUser.id]
    markCurrentUserRead(caseId)
    appendSystemEvent(caseId, `${mockCurrentUser.fullName} przejęła case`)
  },

  async ignore(caseId, input) {
    await wait(undefined, 260)
    if (!canUseUnassignedActions(caseId)) {
      throw new Error("Nie możesz oddać głosu dla tego case’u.")
    }
    const record = getRecord(caseId)
    record.ignoreVotes.current = Math.min(
      record.ignoreVotes.required,
      record.ignoreVotes.current + 1,
    )
    record.ignoreVotes.voters = [
      ...new Set([...record.ignoreVotes.voters, mockCurrentUser.fullName]),
    ]
    record.restrictedUserIds = [
      ...new Set([...record.restrictedUserIds, mockCurrentUser.id]),
    ]
    record.owner = undefined
    record.status =
      record.ignoreVotes.current >= record.ignoreVotes.required
        ? "ignored"
        : "partially_ignored"
    if (record.status === "ignored") {
      record.sla = { state: "paused", dueAt: undefined }
    }
    markCurrentUserRead(caseId)
    const reason = input.reason?.trim() ? ` Powód: ${input.reason.trim()}` : ""
    appendSystemEvent(
      caseId,
      `${mockCurrentUser.fullName} oddała głos ignorowania.${reason}`,
    )
  },

  async askCustomer(caseId, input) {
    await wait(undefined, 260)
    if (!canUseUnassignedActions(caseId)) {
      throw new Error("Nie możesz dopytać klienta w aktualnym stanie case’u.")
    }
    const record = getRecord(caseId)
    appendSupportMessage(caseId, { body: input.message })
    record.owner = undefined
    record.status = "waiting_for_customer"
    record.waitingUntil = new Date(Date.now() + 24 * 60 * 60_000).toISOString()
    if (getCurrentAdministrationSettingsSnapshot().sla.pauseWhileWaiting) {
      record.sla = { state: "paused", dueAt: undefined }
    }
    appendSystemEvent(
      caseId,
      "Case oczekuje na odpowiedź klienta przez 24 godziny i został zwolniony z przypisania",
    )
  },

  async resolve(caseId, input) {
    await wait(undefined, 260)
    const record = getRecord(caseId)
    if (record.status === "resolved" || record.status === "ignored") {
      throw new Error("Case ma już status końcowy.")
    }
    if (record.status === "waiting_for_customer") {
      throw new Error("Case oczekujący na klienta jest tylko do odczytu.")
    }
    if (record.owner?.id !== mockCurrentUser.id) {
      throw new Error("Tylko przypisany agent może rozwiązać ten case.")
    }
    record.status = "resolved"
    record.resolutionCategory = input.category?.trim() || undefined
    record.waitingUntil = undefined
    record.sla = { state: "paused", dueAt: undefined }
    markCurrentUserRead(caseId)
    appendSystemEvent(
      caseId,
      input.category
        ? `Case oznaczony jako rozwiązany. Kategoria: ${input.category}`
        : "Case oznaczony jako rozwiązany",
    )
  },

  async snooze(caseId, until) {
    await wait(undefined, 180)
    const record = getRecord(caseId)
    const assignedToCurrentUser = record.owner?.id === mockCurrentUser.id
    if (!assignedToCurrentUser && !canUseUnassignedActions(caseId)) {
      throw new Error("Nie możesz odłożyć tego case’u.")
    }
    record.snoozedUntilByUser[mockCurrentUser.id] = until
    markCurrentUserRead(caseId)
  },

  async sendMessage(caseId, input) {
    await wait(undefined, 650)
    const record = getRecord(caseId)
    if (
      record.owner?.id !== mockCurrentUser.id ||
      record.status === "waiting_for_customer" ||
      record.status === "resolved" ||
      record.status === "ignored" ||
      record.restrictedUserIds.includes(mockCurrentUser.id)
    ) {
      throw new Error("Odpowiadanie jest niedostępne w aktualnym stanie case’u.")
    }
    if (input.simulateFailure) {
      throw new Error("Symulowany błąd dostarczenia wiadomości.")
    }
    return appendSupportMessage(caseId, {
      body: input.body,
      attachments: input.attachments,
    })
  },
}

import type { CurrentCaseAdminItem } from "@/lib/domain/analytics"
import { mockInboxCaseRecords } from "@/mocks/inbox"
import { mockUsers } from "@/mocks/users"
import { requireCurrentAdministrationPermission } from "./administration"

export interface CurrentCasesRepository {
  list(): Promise<CurrentCaseAdminItem[]>
  reassign(caseId: string, userId: string): Promise<void>
  unassign(caseId: string): Promise<void>
  forceResolve(caseId: string): Promise<void>
}

function delay<T>(value: T, ms = 280) {
  return new Promise<T>((resolve) => setTimeout(() => resolve(structuredClone(value)), ms))
}

function getRecord(caseId: string) {
  const record = mockInboxCaseRecords.find((item) => item.id === caseId)
  if (!record) throw new Error("Nie znaleziono case’a.")
  return record
}

function appendAudit(caseId: string, label: string, author = "Administrator") {
  const record = getRecord(caseId)
  record.updatedAt = new Date().toISOString()
  record.activity.push({
    id: `${caseId}-audit-${Date.now()}`,
    label,
    author,
    createdAt: record.updatedAt,
  })
}

export const mockCurrentCasesRepository: CurrentCasesRepository = {
  async list() {
    const active = mockInboxCaseRecords
      .filter((record) => record.status !== "resolved" && record.status !== "ignored")
      .map((record) => {
        const assignedActivity = [...record.activity].reverse().find((activity) => activity.label.includes("Przypisano") || activity.label.includes("przepisał"))
        return {
          ...record,
          unreadForCurrentUser: false,
          snoozedForCurrentUserUntil: undefined,
          currentUserRestrictedByIgnore: false,
          claimedAt: record.owner ? assignedActivity?.createdAt ?? new Date(new Date(record.createdAt).getTime() + 25 * 60_000).toISOString() : undefined,
        }
      })
      .sort((a, b) => {
        const slaOrder = { breached: 0, at_risk: 1, on_track: 2, paused: 3 }
        return slaOrder[a.sla.state] - slaOrder[b.sla.state] || new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
      })
    return delay(active)
  },

  async reassign(caseId, userId) {
    requireCurrentAdministrationPermission("reassign_cases")
    const record = getRecord(caseId)
    const user = mockUsers.find((item) => item.id === userId && !item.fullName.startsWith("Klient"))
    if (!user) throw new Error("Nie znaleziono użytkownika.")
    const previous = record.owner?.fullName ?? "brak przypisania"
    record.owner = user
    if (record.status === "new" || record.status === "partially_ignored") record.status = "verification"
    appendAudit(caseId, `Administrator przepisał case: ${previous} → ${user.fullName}`)
    return delay(undefined)
  },

  async unassign(caseId) {
    requireCurrentAdministrationPermission("reassign_cases")
    const record = getRecord(caseId)
    if (!record.owner) throw new Error("Case nie ma przypisanego użytkownika.")
    const previous = record.owner.fullName
    record.owner = undefined
    appendAudit(caseId, `Administrator usunął przypisanie użytkownika ${previous}`)
    return delay(undefined)
  },

  async forceResolve(caseId) {
    requireCurrentAdministrationPermission("force_resolve")
    const record = getRecord(caseId)
    if (record.status === "resolved" || record.status === "ignored") {
      throw new Error("Case ma już status końcowy.")
    }
    record.status = "resolved"
    record.resolutionCategory = "Wymuszone rozwiązanie administracyjne"
    record.waitingUntil = undefined
    record.sla = { state: "paused", dueAt: undefined }
    appendAudit(caseId, "Administrator wymusił rozwiązanie case’a")
    return delay(undefined)
  },
}

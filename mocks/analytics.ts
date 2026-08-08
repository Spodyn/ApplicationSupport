import type {
  AnalyticsRecord,
  AnalyticsResolutionReason,
  AnalyticsSlaState,
} from "@/lib/domain/analytics"
import type { Channel } from "@/lib/domain/types"

export const analyticsDataToday = "2026-08-08"

const users = [
  ["u-001", "Anna Kowalska", "Wsparcie L1"],
  ["u-002", "Piotr Nowak", "Wsparcie L1"],
  ["u-003", "Magdalena Wiśniewska", "Wsparcie L2"],
  ["u-004", "Tomasz Zieliński", "Wsparcie L2"],
  ["u-006", "Rafał Wójcik", "Wsparcie L1"],
] as const

const sources = [
  ["slack", "#rozliczenia-premium", "customer-1", "Northstar Retail"],
  ["slack", "#incydenty", "customer-2", "Evergreen Cloud"],
  ["teams", "Obsługa / Ogólny", "customer-3", "Orbit Labs"],
  ["telegram", "@support", "customer-4", "Nova Works"],
  ["teams", "Finanse / Faktury", "customer-5", "Vistala Energy"],
  ["slack", "#integracje", "customer-6", "Atlas Commerce"],
] as const satisfies readonly (readonly [Channel, string, string, string])[]

const activeStatuses = [
  ["new", "Nowy"],
  ["verification", "W trakcie weryfikacji"],
  ["waiting_customer", "Oczekuje na klienta"],
  ["waiting_team", "Oczekuje na zespół"],
  ["snoozed", "Odłożony"],
] as const

function atDay(dayOffset: number, hour: number, minute: number) {
  const date = new Date(`${analyticsDataToday}T12:00:00.000Z`)
  date.setUTCDate(date.getUTCDate() - dayOffset)
  date.setUTCHours(hour, minute, 0, 0)
  return date
}

function addMinutes(date: Date, minutes: number) {
  return new Date(date.getTime() + minutes * 60_000).toISOString()
}

function getResolutionReason(index: number): AnalyticsResolutionReason {
  if (index % 29 === 0) return "spam"
  if (index % 17 === 0) return "not_applicable"
  return "resolved"
}

function getSlaState(index: number, responseMinutes: number): AnalyticsSlaState {
  if (index % 23 === 0 || responseMinutes > 72) return "breached"
  if (index % 11 === 0 || responseMinutes > 64) return "warning"
  return "met"
}

/**
 * Deterministyczne rekordy z 210 dni. Dzięki temu 90-dniowy zakres może być
 * porównany z pełnym, bezpośrednio poprzedzającym okresem.
 */
export const mockAnalyticsRecords: AnalyticsRecord[] = Array.from(
  { length: 210 * 6 },
  (_, index) => {
    const dayOffset = Math.floor(index / 6)
    const dailyIndex = index % 6
    const [platform, sourceChannel, customerId, customerName] = sources[(index * 5 + dayOffset * 3) % sources.length]
    const [userId, userName, team] = users[(index + dayOffset * 2) % users.length]
    const created = atDay(dayOffset, 1 + dailyIndex * 4, (index * 13 + dayOffset * 7) % 60)
    const claimMinutes = 5 + (index * 7 + dayOffset) % 58
    const responseMinutes = 8 + (index * 11 + dayOffset * 3) % 68
    const resolutionMinutes = 75 + (index * 23 + dayOffset * 11) % 1_920
    const hasOwner = index % 23 !== 0
    const isResolved = index % 5 !== 0 && dayOffset > 0
    const resolutionReason = isResolved ? getResolutionReason(index) : undefined
    const [activeStatus, activeStatusLabel] = activeStatuses[(index + dayOffset) % activeStatuses.length]
    const slaState = getSlaState(index, responseMinutes)

    return {
      id: `analytics-${index + 1}`,
      createdAt: created.toISOString(),
      claimedAt: hasOwner ? addMinutes(created, claimMinutes) : undefined,
      firstResponseAt: hasOwner ? addMinutes(created, responseMinutes) : undefined,
      resolvedAt: isResolved ? addMinutes(created, resolutionMinutes) : undefined,
      userId: hasOwner ? userId : undefined,
      userName: hasOwner ? userName : undefined,
      team: hasOwner ? team : undefined,
      customerId,
      customerName,
      platform,
      sourceChannel,
      status: isResolved ? "resolved" : activeStatus,
      statusLabel: isResolved ? "Rozwiązany" : activeStatusLabel,
      priority: (["low", "medium", "high", "urgent"] as const)[(index + dayOffset) % 4],
      tags: index % 3 === 0 ? ["billing", "enterprise"] : index % 3 === 1 ? ["integracja"] : ["support"],
      resolutionReason,
      slaState,
      slaDeadlineAt: addMinutes(created, 60),
    }
  },
)

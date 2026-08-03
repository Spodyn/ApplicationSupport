import type { AnalyticsRecord } from "@/lib/domain/analytics"

const users = [
  ["u-001", "Anna Kowalska"],
  ["u-002", "Piotr Nowak"],
  ["u-003", "Magdalena Wiśniewska"],
  ["u-004", "Tomasz Zieliński"],
  ["u-006", "Rafał Wójcik"],
] as const

const sources = [
  ["slack", "#help-produkcja", "customer-1", "Allegro Retail"],
  ["teams", "Obsługa / Ogólny", "customer-2", "Nova Logistics"],
  ["telegram", "@acme_support", "customer-3", "ACME Polska"],
  ["slack", "#partner-support", "customer-4", "Baltic Energy"],
  ["teams", "Projekt Orion", "customer-5", "Orion Bank"],
  ["telegram", "@medica_help", "customer-6", "Medica Group"],
] as const

function atDay(dayOffset: number, hour: number, minute: number) {
  const date = new Date("2026-08-03T12:00:00.000Z")
  date.setUTCDate(date.getUTCDate() - dayOffset)
  date.setUTCHours(hour, minute, 0, 0)
  return date
}

function addMinutes(date: Date, minutes: number) {
  return new Date(date.getTime() + minutes * 60_000).toISOString()
}

/**
 * Surowe, deterministyczne zdarzenia analityczne z ostatnich 35 dni.
 * Serwis analityczny filtruje je i oblicza metryki tak samo, jak zrobi to przyszłe API.
 */
export const mockAnalyticsRecords: AnalyticsRecord[] = Array.from(
  { length: 35 * 4 },
  (_, index) => {
    const dayOffset = Math.floor(index / 4)
    const dailyIndex = index % 4
    const [platform, sourceChannel, customerId, customerName] = sources[(index * 5 + dayOffset) % sources.length]
    const [userId, userName] = users[(index + dayOffset * 2) % users.length]
    const created = atDay(dayOffset, 7 + dailyIndex * 3, (index * 13) % 60)
    const claimMinutes = 4 + (index * 7) % 52
    const responseMinutes = 9 + (index * 11) % 88
    const resolved = index % 5 !== 0
    const hasOwner = index % 13 !== 0

    return {
      id: `analytics-${index + 1}`,
      createdAt: created.toISOString(),
      claimedAt: hasOwner ? addMinutes(created, claimMinutes) : undefined,
      firstResponseAt: hasOwner ? addMinutes(created, responseMinutes) : undefined,
      resolvedAt: resolved ? addMinutes(created, 95 + (index * 17) % 720) : undefined,
      userId: hasOwner ? userId : undefined,
      userName: hasOwner ? userName : undefined,
      customerId,
      customerName,
      platform,
      sourceChannel,
      askedForInformation: index % 6 === 0,
      ignoreVotes: index % 9 === 0 ? 2 : index % 7 === 0 ? 1 : 0,
      slaMet: responseMinutes <= 60 && index % 11 !== 0,
    }
  },
)

import type { SupportStatistics } from "@/lib/domain/types"

/** Dane makietowe statystyk pulpitu wsparcia. */
export const mockStatistics: SupportStatistics = {
  totalCases: 342,
  openCases: 47,
  resolvedToday: 18,
  avgFirstResponseMinutes: 12,
  avgResolutionHours: 6.4,
  slaComplianceRate: 0.938,
  casesByChannel: {
    slack: 156,
    teams: 121,
    telegram: 65,
  },
  casesByStatus: {
    new: 14,
    open: 33,
    pending: 21,
    on_hold: 9,
    resolved: 41,
    closed: 224,
  },
  dailyVolume: [
    { date: "2026-07-28", created: 38, resolved: 34 },
    { date: "2026-07-29", created: 42, resolved: 40 },
    { date: "2026-07-30", created: 35, resolved: 37 },
    { date: "2026-07-31", created: 48, resolved: 44 },
    { date: "2026-08-01", created: 29, resolved: 31 },
    { date: "2026-08-02", created: 33, resolved: 30 },
    { date: "2026-08-03", created: 27, resolved: 18 },
  ],
}

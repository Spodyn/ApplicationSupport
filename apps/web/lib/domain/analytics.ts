import type { Channel, User } from "./shared"

/**
 * Wymiar raportowy pochodzący z danych analitycznych. Nie jest stanem workflow
 * i nie zastępuje kanonicznego `InboxStatus`.
 */
export type AnalyticsStatusDimension = string

export type AnalyticsSlaState = "met" | "warning" | "breached"
export type AnalyticsResolutionReason = "resolved" | "not_applicable" | "spam"

export interface AnalyticsFilters {
  dateFrom: string
  dateTo: string
  userId?: string
  customerId?: string
  platform?: Channel
  sourceChannel?: string
  status?: AnalyticsStatusDimension
  slaState?: AnalyticsSlaState
  priority?: string
  tag?: string
  team?: string
  comparePrevious?: boolean
}

export interface AnalyticsRecord {
  id: string
  createdAt: string
  claimedAt?: string
  firstResponseAt?: string
  resolvedAt?: string
  userId?: string
  userName?: string
  team?: string
  customerId: string
  customerName: string
  platform: Channel
  sourceChannel: string
  status: AnalyticsStatusDimension
  statusLabel: string
  priority: "low" | "medium" | "high" | "urgent"
  tags: string[]
  resolutionReason?: AnalyticsResolutionReason
  slaState: AnalyticsSlaState
  slaDeadlineAt: string
}

export interface AnalyticsKpis {
  created: number
  resolved: number
  backlog: number
  slaFirstResponsePercentage: number
  breachedSla: number
  averageClaimMinutes: number
  averageFirstResponseMinutes: number
  averageResolutionMinutes: number
  notApplicable: number
  spam: number
}

export interface AnalyticsTimePoint {
  date: string
  created: number
  resolved: number
  backlog: number
}

export interface AnalyticsSlaTimePoint {
  date: string
  met: number
  warning: number
  breached: number
}

export interface DistributionPoint<TState extends string = string> {
  state: TState
  label: string
  value: number
  percentage: number
}

export interface BacklogAgePoint {
  bucket: "0_1h" | "1_4h" | "4_8h" | "8_24h" | "1_3d" | "over_3d"
  label: string
  value: number
}

export interface ClientPerformance {
  customerId: string
  customerName: string
  created: number
  resolved: number
  notApplicable: number
  spam: number
  slaPercentage: number
  averageResponseMinutes: number
  averageResolutionMinutes: number
}

export interface HeatmapPoint {
  dayIndex: number
  dayLabel: string
  hour: number
  value: number
}

export interface ResponseTimePoint {
  date: string
  averageMinutes: number
  medianMinutes: number
  p90Minutes: number
}

export interface UserPerformance {
  userId: string
  userName: string
  claimed: number
  resolved: number
  currentlyAssigned: number
  notApplicable: number
  spam: number
  averageClaimMinutes: number
  medianResponseMinutes: number
  p90ResponseMinutes: number
  averageResolutionMinutes: number
  slaPercentage: number
}

export interface AnalyticsComparison {
  filters: AnalyticsFilters
  label: string
  kpis: AnalyticsKpis
}

export interface AnalyticsResult {
  filters: AnalyticsFilters
  lastUpdatedAt: string
  comparison?: AnalyticsComparison
  kpis: AnalyticsKpis
  timeSeries: AnalyticsTimePoint[]
  slaTimeSeries: AnalyticsSlaTimePoint[]
  sourceDistribution: DistributionPoint<Channel>[]
  statusDistribution: DistributionPoint[]
  slaDistribution: DistributionPoint<AnalyticsSlaState>[]
  backlogAgeDistribution: BacklogAgePoint[]
  clientPerformance: ClientPerformance[]
  heatmap: HeatmapPoint[]
  responseTimeSeries: ResponseTimePoint[]
  userPerformance: UserPerformance[]
  options: {
    users: Pick<User, "id" | "fullName">[]
    customers: { id: string; name: string }[]
    channels: { value: string; label: string; platform: Channel }[]
    statuses: { value: AnalyticsStatusDimension; label: string }[]
    slaStates: { value: AnalyticsSlaState; label: string }[]
  }
}

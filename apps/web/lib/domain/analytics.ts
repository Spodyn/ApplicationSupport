import type { InboxWorkflowStatus } from "./inbox"
import type { Channel, User } from "./shared"

/**
 * Wymiar raportowy pochodzący z danych analitycznych. Nie jest stanem workflow
 * i nie zastępuje kanonicznego `InboxStatus`.
 */
export type AnalyticsStatusDimension = string

export type AnalyticsSlaState = "met" | "warning" | "breached"
export type FirstResponseOutcome = "met" | "breached" | "pending" | "unmeasured"
export type AnalyticsDeadlineState = "ON_TRACK" | "WARNING" | "BREACHED" | "FOLLOW_UP_DUE"
export type AnalyticsDatePreset = "today" | "yesterday" | "7days" | "30days" | "90days" | "this_month" | "previous_month"
export type AnalyticsResolutionReason = "resolved" | "not_applicable" | "spam"

export interface AnalyticsFilters {
  dateFrom: string
  dateTo: string
  datePreset?: AnalyticsDatePreset
  userId?: string
  customerId?: string
  platform?: Channel
  sourceChannel?: string
  status?: AnalyticsStatusDimension
  firstResponseOutcome?: FirstResponseOutcome
  priority?: string
  tag?: string
  team?: string
  comparePrevious?: boolean
}

export interface AnalyticsRecord {
  id: string
  reference?: string
  createdAt: string
  createdDate?: string
  claimedDate?: string
  resolvedDate?: string
  createdDayIndex?: number
  createdHour?: number
  claimedAt?: string
  firstResponseAt?: string
  firstResponseStartedAt?: string | null
  firstResponseStoppedAt?: string | null
  resolutionWallSeconds?: number | null
  resolutionWorkingSeconds?: number | null
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
  workflowStatus?: InboxWorkflowStatus
  priority: "low" | "medium" | "high" | "urgent"
  tags: string[]
  resolutionReason?: AnalyticsResolutionReason
  slaState: AnalyticsSlaState
  slaDeadlineAt?: string
  historicalSupportBreach?: boolean
  historicalNextResponseBreach?: boolean
  operationalDeadlineKind?: "FRT" | "NRT" | "FOLLOW_UP" | null
  operationalDeadlineState?: AnalyticsDeadlineState | null
  firstResponderId?: string | null
  firstResponderName?: string | null
  firstResponseOutcome?: FirstResponseOutcome
  excludedFromUserPerformance?: boolean
}

export interface AnalyticsKpis {
  created: number
  resolved: number
  ignored: number
  claimed: number
  backlog: number
  slaFirstResponsePercentage: number | null
  breachedSla: number
  historicalSupportBreachedCases: number
  nextResponseBreachedCases: number
  averageClaimMinutes: number | null
  averageFirstResponseMinutes: number | null
  averageResolutionMinutes: number | null
  averageWorkingResolutionMinutes: number | null
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
  pending: number
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
  slaPercentage: number | null
  averageResponseMinutes: number | null
  averageResolutionMinutes: number | null
}

export interface HeatmapPoint {
  dayIndex: number
  dayLabel: string
  hour: number
  value: number
}

export interface ResponseTimePoint {
  date: string
  averageMinutes: number | null
  medianMinutes: number | null
  p90Minutes: number | null
}

export interface UserPerformance {
  userId: string
  userName: string
  claimed: number
  resolved: number
  currentlyAssigned: number
  notApplicable: number
  spam: number
  averageClaimMinutes: number | null
  medianResponseMinutes: number | null
  p90ResponseMinutes: number | null
  averageResolutionMinutes: number | null
  slaPercentage: number | null
}

export interface AnalyticsComparison {
  filters: AnalyticsFilters
  label: string
  kpis: AnalyticsKpis
}

export interface AnalyticsResult {
  filters: AnalyticsFilters
  lastUpdatedAt: string
  reportingTimezone: string
  reportingDate: string
  comparison?: AnalyticsComparison
  kpis: AnalyticsKpis
  timeSeries: AnalyticsTimePoint[]
  slaTimeSeries: AnalyticsSlaTimePoint[]
  sourceDistribution: DistributionPoint<Channel>[]
  statusDistribution: DistributionPoint[]
  slaAttention: { overdueSupportResponses: number; dueCustomerFollowUps: number }
  backlogAgeDistribution: BacklogAgePoint[]
  clientPerformance: ClientPerformance[]
  heatmap: HeatmapPoint[]
  responseTimeSeries: ResponseTimePoint[]
  userPerformance: UserPerformance[]
  currentWork?: {
    id: string
    reference: string
    customerId?: string
    customerName: string
    platform: Channel
    sourceChannel: string
    status: string
    statusLabel: string
    userName?: string
    createdAt: string
    operationalDeadlineKind?: AnalyticsRecord["operationalDeadlineKind"]
    operationalDeadlineState?: AnalyticsDeadlineState | null
  }[]
  options: {
    users: Pick<User, "id" | "fullName">[]
    customers: { id: string; name: string }[]
    channels: { value: string; label: string; platform: Channel }[]
    statuses: { value: AnalyticsStatusDimension; label: string }[]
    firstResponseOutcomes: { value: FirstResponseOutcome; label: string }[]
  }
}

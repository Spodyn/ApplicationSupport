import type { Channel, SlaState, User } from "./types"
import type { InboxCase, InboxStatus } from "./inbox"

export interface CurrentCaseAdminItem extends InboxCase {
  claimedAt?: string
}

export interface CurrentCaseFilters {
  userId?: string
  status?: InboxStatus
  platform?: Channel
  customerId?: string
  slaState?: SlaState
}

export interface AnalyticsFilters {
  dateFrom: string
  dateTo: string
  userId?: string
  customerId?: string
  platform?: Channel
  sourceChannel?: string
}

export interface AnalyticsRecord {
  id: string
  createdAt: string
  claimedAt?: string
  firstResponseAt?: string
  resolvedAt?: string
  userId?: string
  userName?: string
  customerId: string
  customerName: string
  platform: Channel
  sourceChannel: string
  askedForInformation: boolean
  ignoreVotes: number
  slaMet: boolean
}

export interface AnalyticsKpis {
  created: number
  resolved: number
  active: number
  slaMetPercentage: number
  averageClaimMinutes: number
  averageFirstResponseMinutes: number
}

export interface AnalyticsTimePoint {
  date: string
  created: number
  resolved: number
}

export interface UserPerformance {
  userId: string
  userName: string
  claimed: number
  resolved: number
  askedForInformation: number
  ignoreVotes: number
  averageClaimMinutes: number
  averageResponseMinutes: number
  slaPercentage: number
}

export interface AnalyticsResult {
  filters: AnalyticsFilters
  kpis: AnalyticsKpis
  timeSeries: AnalyticsTimePoint[]
  sourceDistribution: { platform: Channel; value: number }[]
  slaDistribution: { state: "met" | "breached"; value: number }[]
  responseByUser: { userId: string; userName: string; minutes: number }[]
  userPerformance: UserPerformance[]
  options: {
    users: Pick<User, "id" | "fullName">[]
    customers: { id: string; name: string }[]
    channels: { value: string; label: string; platform: Channel }[]
  }
}

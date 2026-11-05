import type { AnalyticsRecord } from "@/lib/domain/analytics"
import type { AnalyticsRepository } from "@/lib/services/analytics"
import { buildAnalyticsResult } from "@/lib/services/analytics"
import { browserApiTransport } from "./http-transport"

type ApiFacts = {
  records: AnalyticsRecord[]
  users: { id: string; fullName: string }[]
  generatedAt: string
  global: boolean
}

export const apiAnalyticsRepository: AnalyticsRepository = {
  async calculate(filters) {
    const facts = await browserApiTransport.request<ApiFacts>({ method: "GET", path: "/api/v1/statistics/records" })
    return buildAnalyticsResult(facts.records, facts.users, filters, facts.generatedAt, facts.global)
  },
}

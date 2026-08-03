import type {
  CaseQuery,
  CaseRepository,
  IntegrationRepository,
  StatisticsRepository,
  UserQuery,
  UserRepository,
} from "./types"
import { mockCases, mockMessages } from "@/mocks/cases"
import { mockCurrentUser, mockUsers } from "@/mocks/users"
import { mockStatistics } from "@/mocks/statistics"
import { mockIntegrations } from "@/mocks/integrations"

/** Symuluje opóźnienie sieciowe, aby stany ładowania były widoczne w UI. */
function delay<T>(value: T, ms = 400): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms))
}

function normalize(text: string): string {
  return text.toLowerCase().trim()
}

export const mockCaseRepository: CaseRepository = {
  async list(query: CaseQuery = {}) {
    let result = [...mockCases]
    if (query.onlyMine) {
      result = result.filter((c) => c.assignee?.id === mockCurrentUser.id)
    }
    if (query.status) {
      result = result.filter((c) => c.status === query.status)
    }
    if (query.channel) {
      result = result.filter((c) => c.channel === query.channel)
    }
    if (query.priority) {
      result = result.filter((c) => c.priority === query.priority)
    }
    if (query.assigneeId) {
      result = result.filter((c) => c.assignee?.id === query.assigneeId)
    }
    if (query.search) {
      const q = normalize(query.search)
      result = result.filter(
        (c) =>
          normalize(c.subject).includes(q) ||
          normalize(c.reference).includes(q) ||
          c.tags.some((t) => normalize(t).includes(q)),
      )
    }
    return delay(result)
  },
  async getById(id: string) {
    return delay(mockCases.find((c) => c.id === id) ?? null)
  },
  async getMessages(caseId: string) {
    return delay(mockMessages.filter((m) => m.caseId === caseId))
  },
}

export const mockUserRepository: UserRepository = {
  async list(query: UserQuery = {}) {
    let result = [...mockUsers]
    if (query.role) {
      result = result.filter((u) => u.role === query.role)
    }
    if (query.search) {
      const q = normalize(query.search)
      result = result.filter(
        (u) => normalize(u.fullName).includes(q) || normalize(u.email).includes(q),
      )
    }
    return delay(result)
  },
  async getById(id: string) {
    return delay(mockUsers.find((u) => u.id === id) ?? null)
  },
  async getCurrentUser() {
    return delay(mockCurrentUser, 100)
  },
}

export const mockStatisticsRepository: StatisticsRepository = {
  async getOverview() {
    return delay(mockStatistics)
  },
}

export const mockIntegrationRepository: IntegrationRepository = {
  async list() {
    return delay(mockIntegrations)
  },
}

/**
 * Pojedynczy punkt dostępu do repozytoriów. Aby podłączyć prawdziwy backend,
 * wystarczy podmienić te implementacje na klienta OpenAPI.
 */
export const repositories = {
  cases: mockCaseRepository,
  users: mockUserRepository,
  statistics: mockStatisticsRepository,
  integrations: mockIntegrationRepository,
}

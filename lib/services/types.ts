import type {
  Case,
  Integration,
  Message,
  SupportStatistics,
  User,
} from "@/lib/domain/types"

/**
 * Typowane interfejsy warstwy dostępu do danych.
 *
 * Cały dostęp do backendu przechodzi przez te interfejsy. Obecnie realizują je
 * repozytoria makietowe (mocks), które docelowo zostaną zastąpione przez
 * wygenerowanego klienta OpenAPI komunikującego się z API Java 25 / Spring Boot 4.1.
 * Interfejs UI nie może zależeć od konkretnej implementacji.
 */

export interface CaseQuery {
  search?: string
  status?: Case["status"]
  channel?: Case["channel"]
  priority?: Case["priority"]
  assigneeId?: string
  /** Zwraca wyłącznie zgłoszenia przypisane do bieżącego użytkownika. */
  onlyMine?: boolean
}

export interface CaseRepository {
  list(query?: CaseQuery): Promise<Case[]>
  getById(id: string): Promise<Case | null>
  getMessages(caseId: string): Promise<Message[]>
}

export interface UserQuery {
  search?: string
  role?: User["role"]
}

export interface UserRepository {
  list(query?: UserQuery): Promise<User[]>
  getById(id: string): Promise<User | null>
  getCurrentUser(): Promise<User>
}

export interface StatisticsRepository {
  getOverview(): Promise<SupportStatistics>
}

export interface IntegrationRepository {
  list(): Promise<Integration[]>
}

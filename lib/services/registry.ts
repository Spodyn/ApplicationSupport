import { repositories } from "./mock-repositories"
import { mockInboxRepository } from "./inbox"
import { mockAdministrationSettingsRepository, mockAdministrationUserRepository } from "./administration"
import { mockAnalyticsRepository } from "./analytics"
import { mockCurrentCasesRepository } from "./current-cases"

/**
 * Jedyny punkt wiążący interfejsy usług z implementacją danych.
 * Przy podłączaniu klienta wygenerowanego z OpenAPI należy podmienić wyłącznie
 * implementacje w tym rejestrze; komponenty i hooki zapytań pozostają bez zmian.
 */
export const serviceRegistry = {
  cases: repositories.cases,
  users: repositories.users,
  statistics: repositories.statistics,
  integrations: repositories.integrations,
  inbox: mockInboxRepository,
  administrationUsers: mockAdministrationUserRepository,
  administrationSettings: mockAdministrationSettingsRepository,
  analytics: mockAnalyticsRepository,
  currentCases: mockCurrentCasesRepository,
}

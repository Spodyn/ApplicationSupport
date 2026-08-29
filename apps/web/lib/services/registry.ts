import { apiCurrentUserRepository } from "./api/auth-adapter"
import { apiAdministrationSettingsRepository } from "./api/administration-settings-adapter"
import { mockInboxRepository } from "./inbox"
import { mockAdministrationUserRepository } from "./administration"
import { mockAnalyticsRepository } from "./analytics"

/**
 * Jedyny punkt wiążący interfejsy usług z implementacją danych.
 * Przy podłączaniu klienta wygenerowanego z OpenAPI należy podmienić wyłącznie
 * implementacje w tym rejestrze; komponenty i hooki zapytań pozostają bez zmian.
 */
export const serviceRegistry = {
  currentUser: apiCurrentUserRepository,
  inbox: mockInboxRepository,
  administrationUsers: mockAdministrationUserRepository,
  administrationSettings: apiAdministrationSettingsRepository,
  analytics: mockAnalyticsRepository,
}

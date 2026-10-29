import { apiCurrentUserRepository } from "./api/auth-adapter"
import { apiAdministrationSettingsRepository } from "./api/administration-settings-adapter"
import { apiInboxRepository } from "./api/inbox-adapter"
import { apiAdministrationUserRepository } from "./api/administration-users-adapter"
import { mockAnalyticsRepository } from "./analytics"

/**
 * Jedyny punkt wiążący interfejsy usług z implementacją danych.
 * Przy podłączaniu klienta wygenerowanego z OpenAPI należy podmienić wyłącznie
 * implementacje w tym rejestrze; komponenty i hooki zapytań pozostają bez zmian.
 */
export const serviceRegistry = {
  currentUser: apiCurrentUserRepository,
  inbox: apiInboxRepository,
  administrationUsers: apiAdministrationUserRepository,
  administrationSettings: apiAdministrationSettingsRepository,
  analytics: mockAnalyticsRepository,
}

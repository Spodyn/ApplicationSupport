import { apiCurrentUserRepository } from "./api/auth-adapter"
import { apiAdministrationSettingsRepository } from "./api/administration-settings-adapter"
import { apiAdministrationUserRepository } from "./api/administration-users-adapter"
import { apiInboxRepository } from "./api/inbox-adapter"
import { apiDirectMessagesRepository } from "./api/direct-messages-adapter"
import { apiAnalyticsRepository } from "./api/analytics-adapter"

/**
 * Jedyny punkt wiążący interfejsy usług z implementacją danych.
 * Przy podłączaniu klienta wygenerowanego z OpenAPI należy podmienić wyłącznie
 * implementacje w tym rejestrze; komponenty i hooki zapytań pozostają bez zmian.
 */
export const serviceRegistry = {
  currentUser: apiCurrentUserRepository,
  inbox: apiInboxRepository,
  directMessages: apiDirectMessagesRepository,
  administrationUsers: apiAdministrationUserRepository,
  administrationSettings: apiAdministrationSettingsRepository,
  analytics: apiAnalyticsRepository,
}

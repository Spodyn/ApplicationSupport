import type { ApiTransport } from "@usi/api-client/generated"
import { createApiClient } from "@usi/api-client/generated"
import type { InboxPreferencesRepository } from "../inbox-preferences"
import { browserApiTransport } from "./http-transport"

export function createInboxPreferencesRepository(transport: ApiTransport): InboxPreferencesRepository {
  const client = createApiClient(transport)
  return {
    async get() {
      return (await client.getInboxPreferences()).mode === "SEPARATE_BY_PLATFORM" ? "separate_by_platform" : "unified"
    },
    async save(mode) {
      return (await client.saveInboxPreferences({ body: { mode: mode === "unified" ? "UNIFIED" : "SEPARATE_BY_PLATFORM" } })).mode === "SEPARATE_BY_PLATFORM"
        ? "separate_by_platform" : "unified"
    },
  }
}

export const apiInboxPreferencesRepository = createInboxPreferencesRepository(browserApiTransport)

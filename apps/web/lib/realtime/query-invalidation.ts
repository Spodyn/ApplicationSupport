import type { QueryClient, QueryKey } from "@tanstack/react-query"
import { parseRealtimeEventEnvelope } from "./event-envelope"

export const reconciliationScopes = [
  ["support-inbox"], ["direct-messages"], ["direct-recipients"],
  ["current-user"], ["administration"], ["analytics"],
] as const

/** Only identifiers select a REST query. Socket payloads never replace cached data. */
export function realtimeQueryScopes(destination: string, body: unknown, accountId: string): readonly QueryKey[] {
  const event = parseRealtimeEventEnvelope(body)
  if (!event || event.version !== 1) return reconciliationScopes
  if (destination === "/user/queue/application") {
    switch (event.eventType) {
      case "application.catalog_changed":
        return [["support-inbox", "filter-options"], ["support-inbox", "cases"], ["analytics"]]
      case "application.settings_changed":
        return [["administration", "settings"], ["administration", "resync"], ["administration", "provider-setup"],
          ["support-inbox", "filter-options"], ["analytics"]]
      case "application.directory_changed":
        return [["administration", "users"], ["direct-recipients"], ["support-inbox", "filter-options"],
          ["support-inbox", "assignees"], ["analytics"]]
      case "application.identity_changed":
        return reconciliationScopes
      case "application.case_changed": {
        const id = event.payload.caseId
        return typeof id === "string" && /^[0-9a-f-]{36}$/i.test(id)
          ? [["support-inbox", "cases"], ["support-inbox", "case", id], ["support-inbox", "messages", id],
            ["support-inbox", "activity", id], ["support-inbox", "sla-reminders"], ["analytics"], ["administration", "users"]]
          : [["support-inbox"], ["analytics"]]
      }
      default: return reconciliationScopes
    }
  }
  if (destination === "/user/queue/direct-messages") {
    return [["direct-messages", accountId]]
  }
  const id = event.payload.caseId
  const scopes: QueryKey[] = [["support-inbox", "cases"]]
  if (typeof id === "string" && /^[0-9a-f-]{36}$/i.test(id)) {
    scopes.push(["support-inbox", "case", id], ["support-inbox", "activity", id])
    if (destination === "/topic/cases/" + id) scopes.push(["support-inbox", "messages", id])
  }
  if (destination === "/topic/cases" || destination.startsWith("/topic/cases/")) {
    scopes.push(["analytics"], ["administration", "users"])
  }
  if (event.eventType === "case.sla_changed") scopes.push(["support-inbox", "sla-reminders", accountId])
  return scopes
}

/** Fixed batches bound latency even under continuous traffic. A dirty in-flight query refetches again. */
export function coalescedInvalidation(client: QueryClient, delay = 100) {
  const pending = new Map<string, QueryKey>()
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false
  const flush = () => {
    timer = undefined
    const keys = [...pending.values()]
    pending.clear()
    for (const queryKey of keys) {
      void client.invalidateQueries({ queryKey }).then(() => {
        if (!disposed && pending.size && timer === undefined) timer = setTimeout(flush, delay)
      })
    }
  }
  return {
    enqueue(keys: readonly QueryKey[]) {
      for (const key of keys) pending.set(JSON.stringify(key), key)
      if (timer === undefined) timer = setTimeout(flush, delay)
    },
    dispose() { disposed = true; clearTimeout(timer); pending.clear() },
  }
}

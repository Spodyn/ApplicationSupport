import type { Query, QueryClient, QueryKey } from "@tanstack/react-query"
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
      case "application.reminders_changed":
        return [["support-inbox", "sla-reminders", accountId]]
      case "application.catalog_changed":
        return [["support-inbox", "filter-options"], ["support-inbox", "cases"], ["support-inbox", "case"], ["analytics"]]
      case "application.settings_changed":
        return [["administration", "settings"], ["administration", "resync"], ["administration", "provider-setup"],
          ["support-inbox", "filter-options"], ["support-inbox", "cases"], ["support-inbox", "case"], ["analytics"]]
      case "application.directory_changed":
        return [["administration", "users"], ["direct-recipients"], ["support-inbox", "filter-options"],
          ["support-inbox", "assignees"], ["support-inbox", "cases"], ["support-inbox", "case"], ["analytics"]]
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

/** Fixed batches bound latency. In-flight snapshots finish before one dirty follow-up. */
export function coalescedInvalidation(client: QueryClient, delay = 100) {
  const pending = new Map<string, QueryKey>()
  const flights = new Map<string, {dirty: boolean}>()
  let timer: ReturnType<typeof setTimeout> | undefined
  let disposed = false
  const enqueue = (keys: readonly QueryKey[]) => {
    if (disposed) return
    for (const key of keys) pending.set(JSON.stringify(key), key)
    if (timer === undefined) timer = setTimeout(flush, delay)
  }
  const flush = () => {
    timer = undefined
    const keys = [...pending.values()]
    pending.clear()
    const queries = new Map<string, Query>()
    for (const queryKey of keys) {
      // Mark inactive caches stale as well, without cancelling overlapping requests.
      void client.invalidateQueries({queryKey, refetchType: "none"})
      for (const query of client.getQueryCache().findAll({queryKey})) {
        if (query.isActive()) queries.set(query.queryHash, query)
      }
    }
    for (const [hash, query] of queries) {
      const current = flights.get(hash)
      if (current) { current.dirty = true; continue }
      const flight = {dirty: query.state.fetchStatus === "fetching"}
      flights.set(hash, flight)
      void client.refetchQueries({queryKey: query.queryKey, exact: true, type: "active"}, {cancelRefetch: false})
        .finally(() => {
          flights.delete(hash)
          if (!disposed && flight.dirty) enqueue([query.queryKey])
        })
    }
  }
  return {
    enqueue,
    dispose() { disposed = true; clearTimeout(timer); pending.clear() },
  }
}

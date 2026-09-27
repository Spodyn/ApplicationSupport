"use client"

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import {
  RealtimeStompClient,
  type RealtimeConnectionState,
} from "@/lib/realtime/stomp-client"
import { parseRealtimeEventEnvelope, REALTIME_EVENT_VERSION } from "@/lib/realtime/event-envelope"
import { queryKeys } from "@/lib/services/queries"

interface RealtimeContextValue {
  state: RealtimeConnectionState
  subscribeCase: (caseId: string) => () => void
}

const NOOP_UNSUBSCRIBE = () => {}
const RealtimeContext = createContext<RealtimeContextValue>({
  state: "connecting",
  subscribeCase: () => NOOP_UNSUBSCRIBE,
})
const CASE_LIFECYCLE_EVENTS = new Set([
  "case.created",
  "case.updated",
  "case.claimed",
  "case.sla_changed",
])
const CONVERSATION_EVENTS = new Set(["message.created", "message.delivery_updated"])
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<RealtimeConnectionState>("connecting")
  const queryClient = useQueryClient()
  const client = useMemo(() => new RealtimeStompClient(), [])

  useEffect(() => {
    const unsubscribe = client.subscribe(setState)
    const unsubscribeCases = client.subscribeDestination("/topic/cases")
    const unsubscribeFrames = client.subscribeFrames((destination, body) => {
      const event = parseRealtimeEventEnvelope(body)
      // Unknown/future/malformed notifications are deliberately a safe refetch, never a local patch.
      if (!event || event.version !== REALTIME_EVENT_VERSION) {
        void queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
        return
      }
      if (destination === "/topic/cases" && CASE_LIFECYCLE_EVENTS.has(event.eventType)) {
        // REST/DB remains the source of truth. Invalidation avoids overwriting a local pending
        // workflow mutation while still making changes from another browser visible promptly.
        void queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
        return
      }
      if (CONVERSATION_EVENTS.has(event.eventType)) {
        const caseId = typeof event.payload.caseId === "string" ? event.payload.caseId : null
        if (caseId && UUID_PATTERN.test(caseId) && destination === `/topic/cases/${caseId}`) {
          // Query refetch is intentionally idempotent: duplicate/out-of-order realtime hints never
          // append another Message locally and therefore cannot duplicate the conversation.
          void queryClient.invalidateQueries({ queryKey: queryKeys.inboxMessages(caseId) })
          void queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
          return
        }
      }
      void queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
    })
    client.start()
    return () => {
      unsubscribeFrames()
      unsubscribeCases()
      unsubscribe()
      client.stop()
    }
  }, [client, queryClient])

  const subscribeCase = useCallback(
    (caseId: string) => {
      if (!UUID_PATTERN.test(caseId)) return NOOP_UNSUBSCRIBE
      return client.subscribeDestination(`/topic/cases/${caseId}`)
    },
    [client],
  )

  const value = useMemo(() => ({ state, subscribeCase }), [state, subscribeCase])
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>
}

export function useRealtimeConnection(): RealtimeContextValue {
  return useContext(RealtimeContext)
}

export function useRealtimeCase(caseId?: string): void {
  const { subscribeCase } = useContext(RealtimeContext)
  useEffect(() => {
    if (!caseId) return undefined
    return subscribeCase(caseId)
  }, [caseId, subscribeCase])
}

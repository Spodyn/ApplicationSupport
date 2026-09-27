"use client"

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import {
  RealtimeStompClient,
  type RealtimeConnectionState,
} from "@/lib/realtime/stomp-client"
import { parseRealtimeEventEnvelope, REALTIME_EVENT_VERSION } from "@/lib/realtime/event-envelope"
import { queryKeys } from "@/lib/services/queries"

interface RealtimeContextValue {
  state: RealtimeConnectionState
}

const RealtimeContext = createContext<RealtimeContextValue>({ state: "connecting" })
const CASE_LIFECYCLE_EVENTS = new Set([
  "case.created",
  "case.updated",
  "case.claimed",
  "case.sla_changed",
])

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<RealtimeConnectionState>("connecting")
  const queryClient = useQueryClient()

  useEffect(() => {
    const client = new RealtimeStompClient()
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
      if (event.eventType === "message.delivery_updated") {
        const caseId = typeof event.payload.caseId === "string" ? event.payload.caseId : null
        void queryClient.invalidateQueries({ queryKey: queryKeys.inboxCases() })
        if (caseId) void queryClient.invalidateQueries({ queryKey: queryKeys.inboxMessages(caseId) })
        return
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
  }, [queryClient])

  const value = useMemo(() => ({ state }), [state])
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>
}

export function useRealtimeConnection(): RealtimeContextValue {
  return useContext(RealtimeContext)
}

"use client"

import { createContext, useContext, useEffect } from "react"
import type { RealtimeConnectionState } from "./stomp-client"

export interface RealtimeContextValue {
  state: RealtimeConnectionState
  subscribeCase: (caseId: string) => () => void
}

const NOOP_UNSUBSCRIBE = () => {}

export const RealtimeContext = createContext<RealtimeContextValue>({
  state: "connecting",
  subscribeCase: () => NOOP_UNSUBSCRIBE,
})

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

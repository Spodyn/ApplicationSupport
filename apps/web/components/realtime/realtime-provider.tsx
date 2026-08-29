"use client"

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react"
import {
  RealtimeStompClient,
  type RealtimeConnectionState,
} from "@/lib/realtime/stomp-client"

interface RealtimeContextValue {
  state: RealtimeConnectionState
}

const RealtimeContext = createContext<RealtimeContextValue>({ state: "connecting" })

export function RealtimeProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<RealtimeConnectionState>("connecting")

  useEffect(() => {
    const client = new RealtimeStompClient()
    const unsubscribe = client.subscribe(setState)
    client.start()
    return () => {
      unsubscribe()
      client.stop()
    }
  }, [])

  const value = useMemo(() => ({ state }), [state])
  return <RealtimeContext.Provider value={value}>{children}</RealtimeContext.Provider>
}

export function useRealtimeConnection(): RealtimeContextValue {
  return useContext(RealtimeContext)
}

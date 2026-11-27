"use client"

import { useCallback, useEffect, useMemo, useState, type ReactNode } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { RealtimeStompClient, type RealtimeConnectionState } from "@/lib/realtime/stomp-client"
import { parseRealtimeEventEnvelope, REALTIME_EVENT_VERSION } from "@/lib/realtime/event-envelope"
import { RealtimeContext } from "@/lib/realtime/realtime-context"
import { deliveryPresentation } from "@/lib/i18n/delivery-presentation"
import { translate } from "@/lib/i18n/catalog"
import { notify } from "@/components/design-system/notify"
import { coalescedInvalidation, realtimeQueryScopes, reconciliationScopes } from "@/lib/realtime/query-invalidation"

export { useRealtimeConnection } from "@/lib/realtime/realtime-context"

const NOOP_UNSUBSCRIBE = () => {}
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function RealtimeProvider({ children, accountId }: { children: ReactNode; accountId: string }) {
  const [state, setState] = useState<RealtimeConnectionState>("connecting")
  const queryClient = useQueryClient()
  const client = useMemo(() => new RealtimeStompClient(), [])

  useEffect(() => {
    const invalidation = coalescedInvalidation(queryClient)
    const notifiedFailures = new Set<string>()
    let connectedOnce = false
    let resyncAfterReconnect = false
    const unsubscribe = client.subscribe((nextState) => {
      setState(nextState)
      if (nextState === "disconnected" && connectedOnce) {
        resyncAfterReconnect = true
        invalidation.enqueue([["current-user"]])
      }
      if (nextState === "connected") {
        if (resyncAfterReconnect) {
          invalidation.enqueue(reconciliationScopes)
          resyncAfterReconnect = false
        }
        connectedOnce = true
      }
    })
    const unsubscribeCases = client.subscribeDestination("/topic/cases")
    const unsubscribePersonal = client.subscribeDestination("/user/queue/personal")
    const unsubscribeDirectMessages = client.subscribeDestination("/user/queue/direct-messages")
    const unsubscribeApplication = client.subscribeDestination("/user/queue/application")
    const unsubscribeFrames = client.subscribeFrames((destination, body) => {
      const event = parseRealtimeEventEnvelope(body)
      invalidation.enqueue(realtimeQueryScopes(destination, body, accountId))
      if (event?.version === REALTIME_EVENT_VERSION
          && destination === "/topic/cases/" + event.payload.caseId) {
          if (event.eventType === "message.delivery_updated" && event.payload.deliveryStatus === "FAILED"
              && UUID_PATTERN.test(event.entityId) && !notifiedFailures.has(event.entityId)) {
            notifiedFailures.add(event.entityId)
            if (notifiedFailures.size > 500) notifiedFailures.delete(notifiedFailures.values().next().value!)
            const code = typeof event.payload.errorCode === "string" ? event.payload.errorCode : undefined
            notify.error(translate(deliveryPresentation(code).message))
          }
      }
    })
    client.start()
    return () => {
      invalidation.dispose()
      unsubscribeApplication()
      unsubscribeFrames()
      unsubscribeDirectMessages()
      unsubscribePersonal()
      unsubscribeCases()
      unsubscribe()
      client.stop()
    }
  }, [accountId, client, queryClient])

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

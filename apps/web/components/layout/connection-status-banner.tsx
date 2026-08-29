"use client"

import { useEffect, useState } from "react"
import { RadioTower, WifiOff } from "lucide-react"
import { useRealtimeConnection } from "@/components/realtime/realtime-provider"

export function ConnectionStatusBanner() {
  const [offline, setOffline] = useState(false)
  const realtime = useRealtimeConnection()

  useEffect(() => {
    const update = () => setOffline(!navigator.onLine)
    update()
    window.addEventListener("online", update)
    window.addEventListener("offline", update)
    return () => {
      window.removeEventListener("online", update)
      window.removeEventListener("offline", update)
    }
  }, [])

  if (offline) {
    return (
      <div className="flex min-h-9 shrink-0 items-center justify-center gap-2 border-b border-warning/30 bg-warning/15 px-4 py-2 text-center text-xs font-medium text-warning-foreground" role="status" aria-live="polite">
        <WifiOff className="size-4" />
        Brak połączenia z siecią. Dane mogą być nieaktualne, a zmiany nie zostaną wysłane.
      </div>
    )
  }

  if (realtime.state !== "disconnected") return null

  return (
    <div className="flex min-h-9 shrink-0 items-center justify-center gap-2 border-b border-warning/30 bg-warning/15 px-4 py-2 text-center text-xs font-medium text-warning-foreground" role="status" aria-live="polite">
      <RadioTower className="size-4" />
      Połączenie realtime jest chwilowo niedostępne. Aplikacja nadal działa, ale dane mogą odświeżać się z opóźnieniem.
    </div>
  )
}

"use client"

import { useEffect, useState } from "react"
import { WifiOff } from "lucide-react"

export function ConnectionStatusBanner() {
  const [offline, setOffline] = useState(false)

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

  if (!offline) return null

  return (
    <div className="flex min-h-9 shrink-0 items-center justify-center gap-2 border-b border-warning/30 bg-warning/15 px-4 py-2 text-center text-xs font-medium text-warning-foreground" role="status" aria-live="polite">
      <WifiOff className="size-4" />
      Brak połączenia z siecią. Dane mogą być nieaktualne, a zmiany nie zostaną wysłane.
    </div>
  )
}

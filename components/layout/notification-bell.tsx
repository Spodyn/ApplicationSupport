"use client"

import { useState } from "react"
import { Bell } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover"
import { PlatformIcon } from "@/components/design-system/platform-badge"
import type { Channel } from "@/lib/domain/types"

interface Notification {
  id: string
  channel: Channel
  title: string
  detail: string
  time: string
  unread: boolean
}

/** Makietowe powiadomienia — docelowo pochodzące z kanału zdarzeń backendu. */
const notifications: Notification[] = [
  {
    id: "n-1",
    channel: "slack",
    title: "Nowa wiadomość w ZG-1001",
    detail: "Jan Dąbrowski: Nadal nie mogę się zalogować…",
    time: "2 min temu",
    unread: true,
  },
  {
    id: "n-2",
    channel: "slack",
    title: "SLA przekroczone — ZG-1004",
    detail: "Krytyczna awaria usługi płatności",
    time: "18 min temu",
    unread: true,
  },
  {
    id: "n-3",
    channel: "teams",
    title: "Zgłoszenie przypisane do Ciebie",
    detail: "ZG-1008 — Powiadomienia push na Androidzie",
    time: "1 godz. temu",
    unread: false,
  },
]

export function NotificationBell() {
  const [items, setItems] = useState(notifications)
  const unreadCount = items.filter((n) => n.unread).length

  return (
    <Popover>
      <PopoverTrigger
        render={
          <Button variant="ghost" size="icon-sm" className="relative" aria-label="Powiadomienia">
            <Bell />
            {unreadCount > 0 && (
              <span className="absolute top-0.5 right-0.5 flex size-4 items-center justify-center rounded-full bg-destructive text-[10px] font-semibold text-destructive-foreground ring-2 ring-background">
                {unreadCount}
              </span>
            )}
          </Button>
        }
      />
      <PopoverContent align="end" className="w-80 p-0">
        <div className="flex items-center justify-between p-3">
          <span className="text-sm font-medium">Powiadomienia</span>
          <Button variant="link" size="xs" className="h-auto p-0" disabled={unreadCount === 0} onClick={() => setItems((current) => current.map((item) => ({ ...item, unread: false })))}>
            Oznacz wszystkie jako przeczytane
          </Button>
        </div>
        <Separator />
        <ul className="max-h-80 overflow-y-auto">
          {items.map((notification) => (
            <li key={notification.id}>
              <button
                type="button"
                aria-label={`${notification.title}. ${notification.unread ? "Nieprzeczytane." : "Przeczytane."}`}
                onClick={() => setItems((current) => current.map((item) => item.id === notification.id ? { ...item, unread: false } : item))}
                className="flex w-full items-start gap-3 p-3 text-left transition-colors hover:bg-muted/60"
              >
                <PlatformIcon channel={notification.channel} />
                <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                  <span className="flex items-center gap-2">
                    <span className="truncate text-sm font-medium">{notification.title}</span>
                    {notification.unread && (
                      <span className="inline-flex items-center gap-1 text-[10px] font-medium text-primary"><span className="size-1.5 shrink-0 rounded-full bg-primary" aria-hidden />Nowe</span>
                    )}
                  </span>
                  <span className="truncate text-xs text-muted-foreground">
                    {notification.detail}
                  </span>
                  <span className="text-xs text-muted-foreground/80">{notification.time}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}

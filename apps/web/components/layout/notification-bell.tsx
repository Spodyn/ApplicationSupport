"use client"

import { Bell } from "lucide-react"
import { useState } from "react"
import { PlatformIcon } from "@/components/design-system/platform-badge"
import { Button } from "@/components/ui/button"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Separator } from "@/components/ui/separator"
import { locale } from "@/lib/i18n/catalog"
import { useT } from "@/lib/i18n/provider"
import { useSlaReminders } from "@/lib/services/queries"

export function NotificationBell({ onSelect }: { onSelect: (caseId: string) => void }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const { reminders, markRead } = useSlaReminders()
  const unread = reminders.data?.unreadCount ?? 0
  const items = reminders.data?.items ?? []
  const date = new Intl.DateTimeFormat(locale(), { dateStyle: "short", timeStyle: "short" })
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger render={<Button variant="ghost" size="icon-sm" className="relative" aria-label={t("Powiadomienia")}>
        <Bell />
        {unread > 0 && <span className="absolute top-0 right-0 min-w-4 rounded-full bg-destructive px-0.5 text-[10px] text-destructive-foreground">{unread > 99 ? "99+" : unread}</span>}
      </Button>} />
      <PopoverContent align="end" className="w-[min(20rem,calc(100vw-2rem))] p-0">
        <div className="flex flex-wrap items-center justify-between gap-2 p-3">
          <span className="text-sm font-medium">{t("Powiadomienia")}</span>
          <Button variant="link" size="xs" className="h-auto p-0" disabled={!unread || markRead.isPending}
            onClick={() => markRead.mutate(undefined)}>{t("Oznacz wszystkie jako przeczytane")}</Button>
        </div>
        <Separator />
        {reminders.isError && <p role="alert" className="p-3 text-sm">{t("Nie udało się pobrać przypomnień.")}</p>}
        {reminders.isSuccess && !items.length && <p className="p-3 text-sm text-muted-foreground">{t("Brak przypomnień.")}</p>}
        <ul className="max-h-80 overflow-y-auto">
          {items.map((item) => (
            <li key={item.id}>
              <button type="button" className="flex w-full items-start gap-3 p-3 text-left hover:bg-muted/60"
                onClick={() => { if (item.unread) markRead.mutate(item.id); setOpen(false); onSelect(item.caseId) }}>
                <PlatformIcon channel={item.channel} />
                <span className="flex min-w-0 flex-1 flex-col text-sm">
                  <span className={item.unread ? "font-semibold" : undefined}>
                    {item.kind === "FOLLOW_UP" ? t("Czas skontaktować się z klientem") : t("Przekroczony termin odpowiedzi")}
                    {item.unread && <span className="sr-only"> {t("Nieprzeczytane.")}</span>}
                  </span>
                  <span className="text-xs text-muted-foreground">{item.reference} · {date.format(new Date(item.remindedAt))}</span>
                </span>
              </button>
            </li>
          ))}
        </ul>
      </PopoverContent>
    </Popover>
  )
}

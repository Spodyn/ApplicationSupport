"use client"

import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import { navItems } from "@/lib/navigation"
import { channelLabels, caseStatusLabels } from "@/lib/domain/labels"
import { PlatformIcon } from "@/components/design-system/platform-badge"
import type { Channel, CaseStatus } from "@/lib/domain/types"

interface SidebarSection {
  title: string
  items: { label: string; count?: number }[]
}

/** Kontekstowa zawartość drugorzędnego panelu zależna od aktywnej trasy. */
function getSections(pathname: string): SidebarSection[] {
  if (pathname.startsWith("/cases") || pathname.startsWith("/current-cases")) {
    const statuses: CaseStatus[] = ["new", "open", "pending", "on_hold", "resolved", "closed"]
    return [
      {
        title: "Widoki",
        items: [
          { label: "Wszystkie zgłoszenia", count: 8 },
          { label: "Nieprzeczytane", count: 3 },
          { label: "Priorytet krytyczny", count: 1 },
        ],
      },
      {
        title: "Statusy",
        items: statuses.map((status) => ({ label: caseStatusLabels[status] })),
      },
    ]
  }
  if (pathname.startsWith("/users")) {
    return [
      {
        title: "Zespoły",
        items: [
          { label: "Wsparcie L1", count: 3 },
          { label: "Wsparcie L2", count: 2 },
          { label: "Administracja", count: 1 },
          { label: "Klienci zewnętrzni", count: 2 },
        ],
      },
    ]
  }
  if (pathname.startsWith("/statistics")) {
    return [
      {
        title: "Zakres czasu",
        items: [{ label: "Dziś" }, { label: "Ostatnie 7 dni" }, { label: "Ostatnie 30 dni" }],
      },
    ]
  }
  return []
}

export function SecondarySidebar({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname()
  const current = navItems.find(
    (item) => pathname === item.href || pathname.startsWith(`${item.href}/`),
  )
  const sections = getSections(pathname)
  const channels: Channel[] = ["slack", "teams", "telegram"]

  return (
    <aside
      aria-label="Panel kontekstowy"
      className="flex w-64 shrink-0 flex-col overflow-hidden border-r bg-card"
    >
      <div className="flex w-64 flex-col gap-5 overflow-y-auto p-4">
        <div className="flex flex-col gap-0.5">
          <h2 className="text-sm font-semibold">{current?.label ?? "Nawigacja"}</h2>
          {current?.description && (
            <p className="text-xs text-muted-foreground text-pretty">{current.description}</p>
          )}
        </div>

        {sections.map((section) => (
          <div key={section.title} className="flex flex-col gap-1">
            <p className="px-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
              {section.title}
            </p>
            <ul className="flex flex-col gap-0.5">
              {section.items.map((item) => (
                <li key={item.label}>
                  <div className="flex w-full items-center justify-between rounded-md px-2 py-1.5 text-sm text-foreground/80">
                    <span className="truncate">{item.label}</span>
                    {typeof item.count === "number" && (
                      <span className="ml-2 shrink-0 rounded-full bg-muted px-1.5 text-xs text-muted-foreground">
                        {item.count}
                      </span>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          </div>
        ))}

        <div className="mt-auto flex flex-col gap-2 border-t pt-4">
          <p className="px-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">
            Kanały
          </p>
          <ul className="flex flex-col gap-0.5">
            {channels.map((channel) => (
              <li key={channel}>
                <div className="flex items-center gap-2 rounded-md px-2 py-1.5 text-sm">
                  <PlatformIcon channel={channel} />
                  <span className="truncate">{channelLabels[channel]}</span>
                </div>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </aside>
  )
}

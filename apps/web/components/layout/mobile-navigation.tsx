"use client"

import { useT } from "@/lib/i18n/provider"
import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import { visibleNavItems } from "@/lib/navigation"
import { useCurrentUser } from "@/lib/services/queries"
import { UserMenu } from "./user-menu"

export function MobileNavigation({ onNavigate }: { onNavigate: () => void }) {
  const t = useT()
  const pathname = usePathname()
  const currentUser = useCurrentUser()

  return (
    <div className="flex h-full min-h-0 flex-col bg-card">
      <div className="flex items-center justify-between gap-3 border-b px-4 py-3">
        <UserMenu variant="mobile" onNavigate={onNavigate} />
        <p className="text-xs font-medium text-muted-foreground">Support Inbox</p>
      </div>

      <nav aria-label={t("Nawigacja mobilna")} className="min-h-0 flex-1 overflow-y-auto p-3">
        <ul className="grid gap-1">
          {visibleNavItems(currentUser.data).map((item) => {
            const active = pathname === item.href || pathname.startsWith(`${item.href}/`)
            return (
              <li key={item.href}>
                <Link
                  href={item.href}
                  onClick={onNavigate}
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "flex items-center gap-3 rounded-lg px-3 py-3 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                    active ? "bg-accent text-accent-foreground" : "hover:bg-muted",
                  )}
                >
                  <span className={cn("flex size-9 items-center justify-center rounded-lg", active ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground")}>
                    <item.icon className="size-4.5" />
                  </span>
                  <span className="min-w-0">
                    <span className="block text-sm font-medium">{t(item.label)}</span>
                    <span className="block truncate text-xs text-muted-foreground">{t(item.description)}</span>
                  </span>
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>
    </div>
  )
}

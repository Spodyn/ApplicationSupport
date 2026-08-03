"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { Headset } from "lucide-react"
import { cn } from "@/lib/utils"
import { navItems } from "@/lib/navigation"
import { ThemeToggle } from "./theme-toggle"

export function MobileNavigation({ onNavigate }: { onNavigate: () => void }) {
  const pathname = usePathname()

  return (
    <div className="flex h-full min-h-0 flex-col bg-card">
      <div className="flex items-center gap-3 border-b px-4 py-4">
        <span className="flex size-10 items-center justify-center rounded-lg bg-primary text-primary-foreground">
          <Headset className="size-5" />
        </span>
        <div>
          <p className="text-sm font-semibold">Unified Support Inbox</p>
          <p className="text-xs text-muted-foreground">Nawigacja aplikacji</p>
        </div>
      </div>

      <nav aria-label="Nawigacja mobilna" className="min-h-0 flex-1 overflow-y-auto p-3">
        <ul className="grid gap-1">
          {navItems.map((item) => {
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
                    <span className="block text-sm font-medium">{item.label}</span>
                    <span className="block truncate text-xs text-muted-foreground">{item.description}</span>
                  </span>
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>

      <div className="flex items-center justify-between border-t px-4 py-3 text-xs text-muted-foreground">
        <span>Wygląd interfejsu</span>
        <ThemeToggle />
      </div>
    </div>
  )
}

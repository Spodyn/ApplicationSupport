"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { PanelLeftClose, PanelLeftOpen } from "lucide-react"
import { cn } from "@/lib/utils"
import { navItems } from "@/lib/navigation"
import { UserMenu } from "@/components/layout/user-menu"

/**
 * Wąska, główna listwa nawigacyjna aplikacji (po lewej stronie).
 * Widoczność (desktop vs. arkusz mobilny) kontroluje komponent nadrzędny.
 */
export function AppRail({
  onNavigate,
  onToggleSecondary,
  secondarySidebarOpen,
}: {
  onNavigate?: () => void
  onToggleSecondary?: () => void
  secondarySidebarOpen?: boolean
}) {
  const pathname = usePathname()

  return (
    <nav
      aria-label="Nawigacja główna"
      className="flex w-20 shrink-0 flex-col items-center gap-1 bg-sidebar py-3 text-sidebar-foreground"
    >
      <UserMenu variant="rail" onNavigate={onNavigate} />

      <ul className="flex w-full flex-col items-center gap-1 px-1.5">
        {navItems.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`)
          return (
            <li key={item.href} className="w-full">
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "group flex flex-col items-center gap-1 rounded-lg px-1 py-2 text-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring",
                  active
                    ? "bg-sidebar-accent text-sidebar-accent-foreground"
                    : "text-sidebar-foreground/70 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground",
                )}
              >
                <item.icon className="size-5 shrink-0" />
                <span className="text-[11px] leading-tight font-medium text-balance">
                  {item.label}
                </span>
              </Link>
            </li>
          )
        })}
      </ul>

      {onToggleSecondary && (
        <button
          type="button"
          onClick={onToggleSecondary}
          className="mt-auto flex size-9 items-center justify-center rounded-lg text-sidebar-foreground/70 transition-colors hover:bg-sidebar-accent hover:text-sidebar-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-sidebar-ring"
          aria-label={secondarySidebarOpen ? "Zwiń panel kontekstowy" : "Rozwiń panel kontekstowy"}
          title={secondarySidebarOpen ? "Zwiń panel kontekstowy" : "Rozwiń panel kontekstowy"}
        >
          {secondarySidebarOpen ? <PanelLeftClose className="size-4" /> : <PanelLeftOpen className="size-4" />}
        </button>
      )}
    </nav>
  )
}

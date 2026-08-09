"use client"

import Link from "next/link"
import { usePathname } from "next/navigation"
import { cn } from "@/lib/utils"
import { navItems } from "@/lib/navigation"
import { UserMenu } from "@/components/layout/user-menu"

/**
 * Wąska, główna listwa nawigacyjna aplikacji (po lewej stronie).
 * Widoczność (desktop vs. arkusz mobilny) kontroluje komponent nadrzędny.
 */
export function AppRail({
  onNavigate,
}: {
  onNavigate?: () => void
}) {
  const pathname = usePathname()

  return (
    <nav
      aria-label="Nawigacja główna"
      className="app-rail flex w-[88px] shrink-0 flex-col items-center border-r border-white/[0.09] bg-[#08111f] py-[14px] text-[#aeb8c7]"
    >
      <Link
        href="/cases"
        onClick={onNavigate}
        className="relative mb-[25px] grid size-[47px] place-items-center"
        aria-label="Unified Support Inbox"
      >
        <span className="absolute size-[35px] rounded-[5px] bg-[linear-gradient(145deg,#7043f5,#4015a9)] shadow-[0_0_20px_rgba(91,33,232,0.26)] [clip-path:polygon(50%_0,93%_25%,93%_75%,50%_100%,7%_75%,7%_25%)]" />
        <span className="absolute size-[22px] bg-[#0c1522] [clip-path:polygon(50%_0,93%_25%,93%_75%,50%_100%,7%_75%,7%_25%)]" />
        <span className="absolute size-[10px] rounded-[3px] bg-[#d7ccff] shadow-[0_0_7px_rgba(221,214,254,0.7)]" />
      </Link>

      <ul className="flex w-full flex-col items-center gap-[12px] px-[13px]">
        {navItems.map((item) => {
          const active = pathname === item.href || pathname.startsWith(`${item.href}/`)
          return (
            <li key={item.href} className="w-full">
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "group flex h-[61px] items-center justify-center rounded-[8px] text-center transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-violet-500",
                  active
                    ? "bg-[#28124f] text-[#f0eaff] shadow-[inset_0_1px_0_rgba(255,255,255,0.02)]"
                    : "text-[#aeb8c7] hover:bg-white/[0.045] hover:text-white",
                )}
                title={item.label}
              >
                <item.icon className="size-[25px] shrink-0" strokeWidth={1.65} />
                <span className="sr-only">
                  {item.label}
                </span>
              </Link>
            </li>
          )
        })}
      </ul>

      <div className="mb-3 mt-auto">
        <UserMenu variant="rail" onNavigate={onNavigate} />
      </div>
    </nav>
  )
}

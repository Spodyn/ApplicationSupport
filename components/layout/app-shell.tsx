"use client"

import { useState, type ReactNode } from "react"
import { usePathname } from "next/navigation"
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet"
import { AppRail } from "@/components/layout/app-rail"
import { SecondarySidebar } from "@/components/layout/secondary-sidebar"
import { ShellProvider } from "@/components/layout/shell-context"
import { MobileNavigation } from "@/components/layout/mobile-navigation"
import { ConnectionStatusBanner } from "@/components/layout/connection-status-banner"

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const pathname = usePathname()
  const [mobileNavOpen, setMobileNavOpen] = useState(false)
  const [secondarySidebarOpen, setSecondarySidebarOpen] = useState(true)
  const usesContextSidebar =
    !pathname.startsWith("/cases") && !pathname.startsWith("/current-cases")

  return (
    <ShellProvider value={{ openMobileNav: () => setMobileNavOpen(true) }}>
    <div className="flex h-svh w-full overflow-hidden bg-background">
      <a href="#main-content" className="sr-only z-[100] rounded-md bg-background px-3 py-2 text-sm font-medium focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:ring-2 focus:ring-ring">
        Przejdź do treści
      </a>
      {/* Desktop navigation */}
      <div className="hidden lg:flex">
        <AppRail
          secondarySidebarOpen={usesContextSidebar ? secondarySidebarOpen : undefined}
          onToggleSecondary={
            usesContextSidebar
              ? () => setSecondarySidebarOpen((open) => !open)
              : undefined
          }
        />
        {usesContextSidebar && secondarySidebarOpen && <SecondarySidebar />}
      </div>

      {/* Mobile navigation */}
      <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
        <SheetContent side="left" className="w-[min(88vw,22rem)] gap-0 p-0">
          <SheetTitle className="sr-only">Nawigacja</SheetTitle>
          <MobileNavigation onNavigate={() => setMobileNavOpen(false)} />
        </SheetContent>
      </Sheet>

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        <ConnectionStatusBanner />
        <div id="main-content" tabIndex={-1} className="flex min-h-0 flex-1 flex-col overflow-hidden outline-none">
          {children}
        </div>
      </div>
    </div>
    </ShellProvider>
  )
}

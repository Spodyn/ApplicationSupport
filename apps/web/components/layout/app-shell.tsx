"use client"

import { useEffect, useState, type ReactNode } from "react"
import { usePathname, useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet"
import { AppRail } from "@/components/layout/app-rail"
import { ShellProvider } from "@/components/layout/shell-context"
import { MobileNavigation } from "@/components/layout/mobile-navigation"
import { ConnectionStatusBanner } from "@/components/layout/connection-status-banner"
import { AuthenticationRequiredError } from "@/lib/services/current-user"
import { useCurrentUser } from "@/lib/services/queries"

interface AppShellProps {
  children: ReactNode
}

export function AppShell({ children }: AppShellProps) {
  const pathname = usePathname()

  if (pathname === "/login") {
    return <>{children}</>
  }

  return <AuthenticatedAppShell>{children}</AuthenticatedAppShell>
}

function AuthenticatedAppShell({ children }: AppShellProps) {
  const router = useRouter()
  const currentUser = useCurrentUser()
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  useEffect(() => {
    if (currentUser.error instanceof AuthenticationRequiredError) {
      router.replace("/login")
    }
  }, [currentUser.error, router])

  if (currentUser.isPending || currentUser.error instanceof AuthenticationRequiredError) {
    return (
      <main className="grid min-h-svh place-items-center bg-background">
        <p className="text-sm text-muted-foreground">Sprawdzanie sesji…</p>
      </main>
    )
  }

  if (currentUser.isError) {
    return (
      <main className="grid min-h-svh place-items-center bg-background px-4">
        <div className="space-y-3 text-center">
          <p className="text-sm text-destructive">Nie udało się sprawdzić bieżącej sesji.</p>
          <Button variant="outline" onClick={() => void currentUser.refetch()}>
            Spróbuj ponownie
          </Button>
        </div>
      </main>
    )
  }

  return (
    <ShellProvider value={{ openMobileNav: () => setMobileNavOpen(true) }}>
      <div className="flex h-svh w-full overflow-hidden bg-background">
        <a href="#main-content" className="sr-only z-[100] rounded-md bg-background px-3 py-2 text-sm font-medium focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:ring-2 focus:ring-ring">
          Przejdź do treści
        </a>
        <div className="hidden lg:flex">
          <AppRail />
        </div>

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

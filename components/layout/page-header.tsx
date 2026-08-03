"use client"

import type { ReactNode } from "react"
import { PanelLeftIcon } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { NotificationBell } from "@/components/layout/notification-bell"
import { UserMenu } from "@/components/layout/user-menu"
import { ThemeToggle } from "@/components/layout/theme-toggle"
import { useShell } from "@/components/layout/shell-context"

interface PageHeaderProps {
  title: string
  description?: string
  actions?: ReactNode
}

export function PageHeader({ title, description, actions }: PageHeaderProps) {
  const shell = useShell()
  return (
    <header className="flex h-14 shrink-0 items-center gap-2 border-b border-border bg-card px-3 sm:h-16 sm:gap-3 sm:px-4 md:px-6">
      <Button
        variant="ghost"
        size="icon"
        className="lg:hidden"
        onClick={() => shell?.openMobileNav()}
        aria-label="Przełącz panel boczny"
      >
        <PanelLeftIcon />
      </Button>

      <div className="flex min-w-0 flex-col">
        <h1 className="truncate text-base font-semibold leading-tight text-foreground">{title}</h1>
        {description ? <p className="hidden truncate text-xs text-muted-foreground sm:block">{description}</p> : null}
      </div>

      <div className="ml-auto flex items-center gap-1.5">
        {actions ? (
          <>
            <div className="flex min-w-0 items-center gap-2">{actions}</div>
            <Separator orientation="vertical" className="mx-1 h-6" />
          </>
        ) : null}
        <span className="hidden sm:contents"><ThemeToggle /><NotificationBell /></span>
        <Separator orientation="vertical" className="mx-1 h-6" />
        <UserMenu />
      </div>
    </header>
  )
}

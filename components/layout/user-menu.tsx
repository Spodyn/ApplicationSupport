"use client"

import { useEffect, useState } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { useTheme } from "next-themes"
import {
  Bell,
  ChevronDown,
  LogOut,
  Moon,
  RefreshCw,
  Settings,
  Sun,
  User,
} from "lucide-react"
import { useRouter } from "next/navigation"
import { Button } from "@/components/ui/button"
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { UserAvatar } from "@/components/design-system/user-avatar"
import { notify } from "@/components/design-system/notify"
import { userRoleLabels } from "@/lib/domain/labels"
import { useCurrentUser } from "@/lib/services/queries"

export function UserMenu({
  variant = "rail",
  onNavigate,
}: {
  variant?: "rail" | "mobile"
  onNavigate?: () => void
}) {
  const router = useRouter()
  const queryClient = useQueryClient()
  const { resolvedTheme, setTheme } = useTheme()
  const { data: user } = useCurrentUser()
  const [mounted, setMounted] = useState(false)
  const [refreshing, setRefreshing] = useState(false)

  useEffect(() => setMounted(true), [])

  const isDark = mounted && resolvedTheme === "dark"

  const refreshData = async () => {
    setRefreshing(true)
    try {
      await queryClient.invalidateQueries()
      router.refresh()
      notify.success("Dane odświeżone", "Widok pokazuje najnowsze dostępne informacje.")
    } finally {
      setRefreshing(false)
    }
  }

  const goTo = (href: string) => {
    router.push(href)
    onNavigate?.()
  }

  if (!user) {
    return (
      <Button
        variant="ghost"
        size="icon-lg"
        disabled
        aria-label="Wczytywanie profilu"
        className={variant === "rail" ? "mb-2 text-sidebar-foreground" : undefined}
      >
        <User />
      </Button>
    )
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          variant === "rail" ? (
            <Button
              variant="ghost"
              size="icon-lg"
              className="mb-2 size-11 rounded-xl border border-sidebar-border bg-sidebar-accent/70 p-0 text-sidebar-foreground shadow-sm hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
              aria-label={`Otwórz menu profilu: ${user.fullName}`}
            >
              <UserAvatar user={user} size="lg" showPresence />
            </Button>
          ) : (
            <Button variant="ghost" size="sm" className="h-11 gap-2 px-1.5">
              <UserAvatar user={user} size="default" showPresence />
              <span className="flex min-w-0 flex-col text-left leading-tight">
                <span className="truncate text-xs font-semibold">{user.fullName}</span>
                <span className="truncate text-[11px] text-muted-foreground">
                  {userRoleLabels[user.role]}
                </span>
              </span>
              <ChevronDown className="ml-auto text-muted-foreground" />
            </Button>
          )
        }
      />
      <DropdownMenuContent
        side={variant === "rail" ? "right" : "bottom"}
        align="start"
        sideOffset={8}
        className="w-72 p-2"
      >
        <DropdownMenuGroup>
          <DropdownMenuLabel className="flex items-center gap-3 px-2 py-2">
            <UserAvatar user={user} size="lg" showPresence />
            <span className="flex min-w-0 flex-col gap-0.5">
              <span className="truncate text-sm font-semibold text-foreground">{user.fullName}</span>
              <span className="truncate text-xs font-normal text-muted-foreground">{user.email}</span>
              <span className="text-[11px] font-normal text-muted-foreground">
                {userRoleLabels[user.role]}
              </span>
            </span>
          </DropdownMenuLabel>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem
            onClick={() => void refreshData()}
            disabled={refreshing}
            className="min-h-9 px-2"
          >
            <RefreshCw className={refreshing ? "animate-spin" : undefined} />
            {refreshing ? "Odświeżanie…" : "Odśwież dane"}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => notify.info("Powiadomienia", "Masz 2 nowe powiadomienia do sprawdzenia.")}
            className="min-h-9 px-2"
          >
            <Bell />
            Powiadomienia
            <span className="ml-auto rounded-full bg-destructive px-1.5 py-0.5 text-[10px] font-semibold text-destructive-foreground">
              2
            </span>
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => setTheme(isDark ? "light" : "dark")}
            className="min-h-9 px-2"
          >
            {isDark ? <Sun /> : <Moon />}
            {isDark ? "Włącz tryb jasny" : "Włącz tryb ciemny"}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem onClick={() => notify.info("Profil", "Widok profilu będzie dostępny wkrótce.")} className="min-h-9 px-2">
            <User />
            Mój profil
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => goTo("/settings")} className="min-h-9 px-2">
            <Settings />
            Ustawienia i preferencje
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          variant="destructive"
          className="min-h-9 px-2"
          onClick={() => notify.message("Wylogowano", "To makieta — sesja nie jest zarządzana.")}
        >
          <LogOut />
          Wyloguj się
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

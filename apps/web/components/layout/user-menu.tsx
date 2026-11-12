"use client"

import { useT } from "@/lib/i18n/provider"
import { useState, useSyncExternalStore } from "react"
import { useQueryClient } from "@tanstack/react-query"
import { useTheme } from "next-themes"
import {
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
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu"
import { UserAvatar } from "@/components/design-system/user-avatar"
import { notify } from "@/components/design-system/notify"
import { userRoleLabels } from "@/lib/domain/labels"
import { useLogout } from "@/lib/services/auth-queries"
import { useCurrentUser } from "@/lib/services/queries"

import { useLanguagePreference } from "@/lib/i18n/provider"
import { language, languages } from "@/lib/i18n/catalog"

const subscribe = () => () => {}
const getClientSnapshot = () => true
const getServerSnapshot = () => false

export function UserMenu({
  variant = "rail",
  onNavigate,
}: {
  variant?: "rail" | "mobile"
  onNavigate?: () => void
}) {
  const t = useT()
  const { selectedLanguage, setLanguage } = useLanguagePreference()
  const router = useRouter()
  const queryClient = useQueryClient()
  const logout = useLogout()
  const { resolvedTheme, setTheme } = useTheme()
  const { data: user } = useCurrentUser()
  const mounted = useSyncExternalStore(subscribe, getClientSnapshot, getServerSnapshot)
  const [refreshing, setRefreshing] = useState(false)
  const [open, setOpen] = useState(false)

  const isDark = mounted && resolvedTheme === "dark"

  const refreshData = async () => {
    setRefreshing(true)
    try {
      await queryClient.invalidateQueries()
      router.refresh()
      notify.success(t("Dane odświeżone"), t("Widok pokazuje najnowsze dostępne informacje."))
    } finally {
      setRefreshing(false)
    }
  }

  const signOut = async () => {
    try {
      await logout.mutateAsync()
      queryClient.clear()
      router.replace("/login")
      notify.message(t("Wylogowano"), t("Sesja została zakończona."))
    } catch {
      notify.message(t("Nie udało się wylogować"), t("Spróbuj ponownie."))
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
        aria-label={t("Wczytywanie profilu")}
        className={variant === "rail" ? "mb-2 text-sidebar-foreground" : undefined}
      >
        <User />
      </Button>
    )
  }

  return (
    <DropdownMenu open={open} onOpenChange={setOpen}>
      <DropdownMenuTrigger
        render={
          variant === "rail" ? (
            <Button
              variant="ghost"
              size="icon-lg"
              className="mb-2 size-[54px] rounded-full border-0 bg-transparent p-0 text-white shadow-none hover:bg-white/[0.04] hover:text-white"
              aria-label={t("Otwórz menu profilu: {{value0}}", { value0: user.fullName })}
            >
              <span className="relative grid size-[51px] place-items-center rounded-full bg-[linear-gradient(145deg,#6231e6,#3d159f)] text-[17px] font-medium text-white">
                {user.fullName.split(/\s+/).filter(Boolean).slice(0, 2).map((part) => part[0]).join("").toLocaleUpperCase("pl")}
                <span className="absolute bottom-0 right-0 size-[13px] rounded-full border-[3px] border-[#08111f] bg-emerald-400" />
              </span>
            </Button>
          ) : (
            <Button variant="ghost" size="sm" className="h-11 gap-2 px-1.5">
              <UserAvatar user={user} size="default" showPresence />
              <span className="flex min-w-0 flex-col text-left leading-tight">
                <span className="truncate text-xs font-semibold">{user.fullName}</span>
                <span className="truncate text-[11px] text-muted-foreground">
                  {t(userRoleLabels[user.role])}
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
                {t(userRoleLabels[user.role])}
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
            {refreshing ? t("Odświeżanie…") : t("Odśwież dane")}
          </DropdownMenuItem>
          <DropdownMenuItem
            onClick={() => setTheme(isDark ? "light" : "dark")}
            className="min-h-9 px-2"
          >
            {isDark ? <Sun /> : <Moon />}
            {isDark ? t("Włącz tryb jasny") : t("Włącz tryb ciemny")}
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuSub>
          <DropdownMenuSubTrigger className="min-h-9 px-2">
            {t("Język interfejsu")}
            <span className="ml-auto text-muted-foreground">{selectedLanguage === "en" ? "English" : "Polski"}</span>
          </DropdownMenuSubTrigger>
          <DropdownMenuSubContent>
            <DropdownMenuRadioGroup value={selectedLanguage} onValueChange={(value) => setLanguage(language(value))}>
              {languages.map((value) => (
                <DropdownMenuRadioItem key={value} value={value} lang={value} onClick={() => setOpen(false)}>
                  {value === "en" ? "English" : "Polski"}
                </DropdownMenuRadioItem>
              ))}
            </DropdownMenuRadioGroup>
          </DropdownMenuSubContent>
        </DropdownMenuSub>
        {user.role === "admin" && (
          <DropdownMenuItem onClick={() => goTo("/settings")} className="min-h-9 px-2">
            <Settings />
            {t("Ustawienia i preferencje")}
          </DropdownMenuItem>
        )}
        <DropdownMenuSeparator />
        <DropdownMenuItem
          variant="destructive"
          className="min-h-9 px-2"
          disabled={logout.isPending}
          onClick={() => void signOut()}
        >
          <LogOut />
          {logout.isPending ? t("Wylogowywanie…") : t("Wyloguj się")}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

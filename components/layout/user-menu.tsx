"use client"

import { ChevronDown, LogOut, Settings, User } from "lucide-react"
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

export function UserMenu() {
  const router = useRouter()
  const { data: user } = useCurrentUser()

  if (!user) {
    return <Button variant="ghost" size="icon-sm" disabled aria-label="Wczytywanie profilu"><User /></Button>
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={
          <Button variant="ghost" size="sm" className="h-9 gap-2 pr-1.5 pl-1">
            <UserAvatar user={user} size="sm" showPresence />
            <span className="hidden text-left leading-tight sm:flex sm:flex-col">
              <span className="text-xs font-medium">{user.fullName}</span>
              <span className="text-[11px] text-muted-foreground">
                {userRoleLabels[user.role]}
              </span>
            </span>
            <ChevronDown className="text-muted-foreground" />
          </Button>
        }
      />
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel className="flex flex-col gap-0.5">
          <span className="text-sm font-medium text-foreground">{user.fullName}</span>
          <span className="text-xs font-normal text-muted-foreground">{user.email}</span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuGroup>
          <DropdownMenuItem onClick={() => notify.info("Profil", "Widok profilu będzie dostępny wkrótce.")}>
            <User />
            Mój profil
          </DropdownMenuItem>
          <DropdownMenuItem onClick={() => router.push("/settings")}>
            <Settings />
            Preferencje
          </DropdownMenuItem>
        </DropdownMenuGroup>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          variant="destructive"
          onClick={() => notify.message("Wylogowano", "To makieta — sesja nie jest zarządzana.")}
        >
          <LogOut />
          Wyloguj się
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

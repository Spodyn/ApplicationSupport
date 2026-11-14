import {
  ChartColumn,
  MessagesSquare,
  MessageCircleMore,
  Settings,
  Users,
  type LucideIcon,
} from "lucide-react"

export interface NavItem {
  label: string
  href: string
  icon: LucideIcon
  description: string
  adminOnly?: boolean
  requiredPermission?: string
}

/** Główna nawigacja aplikacji (lewa listwa). */
export const navItems: NavItem[] = [
  {
    label: "Czaty",
    href: "/cases",
    icon: MessageCircleMore,
    description: "Wszystkie rozmowy ze wszystkich kanałów",
  },
  {
    label: "Wiadomości prywatne",
    href: "/direct-messages",
    icon: MessagesSquare,
    description: "Prywatne rozmowy z członkami zespołu",
  },
  {
    label: "Statystyki",
    href: "/statistics",
    icon: ChartColumn,
    description: "Wskaźniki i trendy zespołu wsparcia",
    requiredPermission: "view_global_statistics",
  },
  {
    label: "Użytkownicy",
    href: "/users",
    icon: Users,
    description: "Agenci i klienci w systemie",
    requiredPermission: "manage_users",
  },
  {
    label: "Ustawienia",
    href: "/settings",
    icon: Settings,
    description: "Ustawienia aplikacji",
    adminOnly: true,
  },
]

export function visibleNavItems(user: { role: string; effectivePermissions: string[] } | undefined) {
  return navItems.filter((item) => {
    if (item.adminOnly && user?.role !== "admin") return false
    return !item.requiredPermission || user?.effectivePermissions.includes(item.requiredPermission)
  })
}

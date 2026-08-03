import {
  ChartColumn,
  Inbox,
  MessagesSquare,
  Settings,
  Users,
  type LucideIcon,
} from "lucide-react"

export interface NavItem {
  label: string
  href: string
  icon: LucideIcon
  description: string
}

/** Główna nawigacja aplikacji (lewa listwa). */
export const navItems: NavItem[] = [
  {
    label: "Case’y",
    href: "/cases",
    icon: Inbox,
    description: "Wszystkie zgłoszenia ze wszystkich kanałów",
  },
  {
    label: "Bieżące case’y",
    href: "/current-cases",
    icon: MessagesSquare,
    description: "Zgłoszenia przypisane do Ciebie",
  },
  {
    label: "Statystyki",
    href: "/statistics",
    icon: ChartColumn,
    description: "Wskaźniki i trendy zespołu wsparcia",
  },
  {
    label: "Użytkownicy",
    href: "/users",
    icon: Users,
    description: "Agenci i klienci w systemie",
  },
  {
    label: "Ustawienia",
    href: "/settings",
    icon: Settings,
    description: "Integracje i konfiguracja aplikacji",
  },
]

import {
  ChartColumn,
  MessageCircleMore,
  Settings,
  SquareCheckBig,
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
    label: "Czaty",
    href: "/cases",
    icon: MessageCircleMore,
    description: "Wszystkie rozmowy ze wszystkich kanałów",
  },
  {
    label: "Do zrobienia",
    href: "/current-cases",
    icon: SquareCheckBig,
    description: "Sprawy wymagające działania",
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
    description: "Ustawienia aplikacji",
  },
]

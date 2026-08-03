import type { User } from "@/lib/domain/types"

/**
 * Dane makietowe użytkowników. W środowisku produkcyjnym zostaną zastąpione
 * odpowiedziami z API (Java 25 / Spring Boot 4.1) poprzez wygenerowanego klienta OpenAPI.
 */
export const mockUsers: User[] = [
  {
    id: "u-001",
    fullName: "Anna Kowalska",
    email: "anna.kowalska@firma.pl",
    role: "agent",
    presence: "online",
    team: "Wsparcie L1",
    createdAt: "2024-01-12T08:00:00.000Z",
  },
  {
    id: "u-002",
    fullName: "Piotr Nowak",
    email: "piotr.nowak@firma.pl",
    role: "agent",
    presence: "busy",
    team: "Wsparcie L1",
    createdAt: "2024-02-03T08:00:00.000Z",
  },
  {
    id: "u-003",
    fullName: "Magdalena Wiśniewska",
    email: "magdalena.wisniewska@firma.pl",
    role: "supervisor",
    presence: "online",
    team: "Wsparcie L2",
    createdAt: "2023-11-20T08:00:00.000Z",
  },
  {
    id: "u-004",
    fullName: "Tomasz Zieliński",
    email: "tomasz.zielinski@firma.pl",
    role: "agent",
    presence: "away",
    team: "Wsparcie L2",
    createdAt: "2024-03-15T08:00:00.000Z",
  },
  {
    id: "u-005",
    fullName: "Katarzyna Lewandowska",
    email: "katarzyna.lewandowska@firma.pl",
    role: "admin",
    presence: "online",
    team: "Administracja",
    createdAt: "2023-09-01T08:00:00.000Z",
  },
  {
    id: "u-006",
    fullName: "Rafał Wójcik",
    email: "rafal.wojcik@firma.pl",
    role: "agent",
    presence: "offline",
    team: "Wsparcie L1",
    createdAt: "2024-04-22T08:00:00.000Z",
  },
  {
    id: "u-100",
    fullName: "Klient — Jan Dąbrowski",
    email: "jan.dabrowski@klient.pl",
    role: "agent",
    presence: "offline",
    team: "Klient zewnętrzny",
    createdAt: "2024-05-10T08:00:00.000Z",
  },
  {
    id: "u-101",
    fullName: "Klient — Ewa Kaczmarek",
    email: "ewa.kaczmarek@klient.pl",
    role: "agent",
    presence: "offline",
    team: "Klient zewnętrzny",
    createdAt: "2024-06-18T08:00:00.000Z",
  },
]

/** Bieżący zalogowany użytkownik (agent wsparcia). */
export const mockCurrentUser: User = mockUsers[0]

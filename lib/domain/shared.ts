/**
 * Współdzielone, stabilne typy domenowe frontendu.
 *
 * Nie są typami transportowymi ani zapowiedzią schematu OpenAPI. Przyszły
 * adapter API będzie mapował wygenerowane DTO na te typy oraz modele obszarowe.
 */

export type Channel = "slack" | "teams" | "telegram"

export type SlaState = "on_track" | "at_risk" | "breached" | "paused"

export type MessageDeliveryStatus =
  | "queued"
  | "sending"
  | "sent"
  | "delivered"
  | "read"
  | "failed"

export type UserRole = "agent" | "supervisor" | "admin"

export type UserPresence = "online" | "busy" | "away" | "offline"

export interface User {
  id: string
  fullName: string
  email: string
  avatarUrl?: string
  role: UserRole
  presence: UserPresence
  team?: string
  createdAt: string
}

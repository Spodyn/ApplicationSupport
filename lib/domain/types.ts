/**
 * Domain types for the Unified Support Inbox.
 *
 * These types describe the shape of data that the future Java 25 / Spring Boot 4.1
 * backend will expose. They are intentionally framework-agnostic so a generated
 * OpenAPI client can later replace the mock repositories without touching the UI.
 */

/** Kanał komunikacji, z którego pochodzi zgłoszenie. */
export type Channel = "slack" | "teams" | "telegram"

/** Stan cyklu życia zgłoszenia (case). */
export type CaseStatus =
  | "new" // Nowy
  | "open" // Otwarty
  | "pending" // Oczekujący
  | "on_hold" // Wstrzymany
  | "resolved" // Rozwiązany
  | "closed" // Zamknięty

/** Priorytet zgłoszenia. */
export type CasePriority = "low" | "medium" | "high" | "urgent"

/** Stan realizacji SLA dla zgłoszenia. */
export type SlaState = "on_track" | "at_risk" | "breached" | "paused"

/** Stan dostarczenia pojedynczej wiadomości. */
export type MessageDeliveryStatus =
  | "queued" // W kolejce
  | "sending" // Wysyłanie
  | "sent" // Wysłano
  | "delivered" // Dostarczono
  | "read" // Odczytano
  | "failed" // Błąd

/** Rola użytkownika w systemie wsparcia. */
export type UserRole = "agent" | "supervisor" | "admin"

/** Dostępność / status obecności agenta. */
export type UserPresence = "online" | "busy" | "away" | "offline"

/** Relacja użytkownika do konkretnego zgłoszenia. */
export type CaseUserState = "assignee" | "collaborator" | "watcher" | "requester"

/** Użytkownik systemu (agent wsparcia lub zgłaszający). */
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

/** Konfiguracja integracji z zewnętrzną platformą. */
export interface Integration {
  id: string
  channel: Channel
  displayName: string
  connected: boolean
  workspace?: string
  lastSyncAt?: string
}

/** Pojedyncza wiadomość w wątku zgłoszenia. */
export interface Message {
  id: string
  caseId: string
  authorId: string
  authorName: string
  channel: Channel
  body: string
  createdAt: string
  deliveryStatus: MessageDeliveryStatus
  inbound: boolean
}

/** Powiązanie użytkownika ze zgłoszeniem wraz z jego rolą w tym zgłoszeniu. */
export interface CaseParticipant {
  userId: string
  state: CaseUserState
}

/** Zgłoszenie serwisowe (case). */
export interface Case {
  id: string
  reference: string
  subject: string
  channel: Channel
  status: CaseStatus
  priority: CasePriority
  sla: SlaState
  slaDueAt?: string
  requester: User
  assignee?: User
  participants: CaseParticipant[]
  tags: string[]
  messageCount: number
  unreadCount: number
  createdAt: string
  updatedAt: string
  lastMessagePreview: string
}

/** Zbiorcze statystyki dla pulpitu. */
export interface SupportStatistics {
  totalCases: number
  openCases: number
  resolvedToday: number
  avgFirstResponseMinutes: number
  avgResolutionHours: number
  slaComplianceRate: number
  casesByChannel: Record<Channel, number>
  casesByStatus: Record<CaseStatus, number>
  dailyVolume: { date: string; created: number; resolved: number }[]
}

import type {
  Channel,
  MessageDeliveryStatus,
  SlaState,
  UserPresence,
  UserRole,
} from "./shared"

/**
 * Polskie etykiety i mapowania wariantów dla wartości domenowych.
 * Trzymane w jednym miejscu, aby cały interfejs pozostał spójny językowo.
 */

export const slaStateLabels: Record<SlaState, string> = {
  on_track: "W normie",
  at_risk: "Zagrożone",
  breached: "Przekroczone",
  paused: "Wstrzymane",
}

export const channelLabels: Record<Channel, string> = {
  slack: "Slack",
  teams: "Microsoft Teams",
  telegram: "Telegram",
}

export const deliveryStatusLabels: Record<MessageDeliveryStatus, string> = {
  queued: "W kolejce",
  sending: "Wysyłanie",
  sent: "Wysłano",
  delivered: "Dostarczono",
  read: "Odczytano",
  failed: "Błąd",
}

export const userRoleLabels: Record<UserRole, string> = {
  agent: "Agent",
  supervisor: "Kierownik",
  admin: "Administrator",
}

export const userPresenceLabels: Record<UserPresence, string> = {
  online: "Dostępny",
  busy: "Zajęty",
  away: "Zaraz wracam",
  offline: "Offline",
}

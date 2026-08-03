import type { Integration } from "@/lib/domain/types"

/** Dane makietowe integracji z platformami komunikacyjnymi. */
export const mockIntegrations: Integration[] = [
  {
    id: "int-slack",
    channel: "slack",
    displayName: "Slack — Przestrzeń firmowa",
    connected: true,
    workspace: "firma.slack.com",
    lastSyncAt: "2026-08-03T09:45:00.000Z",
  },
  {
    id: "int-teams",
    channel: "teams",
    displayName: "Microsoft Teams — Dział wsparcia",
    connected: true,
    workspace: "firma.onmicrosoft.com",
    lastSyncAt: "2026-08-03T09:42:00.000Z",
  },
  {
    id: "int-telegram",
    channel: "telegram",
    displayName: "Telegram — Bot wsparcia",
    connected: false,
    workspace: "@firma_support_bot",
    lastSyncAt: "2026-07-28T14:10:00.000Z",
  },
]

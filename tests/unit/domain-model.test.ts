import { describe, expect, it } from "vitest"
import {
  administrationPermissions,
  administrationRoles,
  hasAdministrationPermission,
} from "@/lib/domain/administration"
import { inboxStatusLabels } from "@/lib/domain/inbox"
import { channelLabels } from "@/lib/domain/labels"

describe("kanoniczny workflow inbox", () => {
  it("udostępnia wyłącznie uzgodnione statusy frontendu", () => {
    expect(Object.keys(inboxStatusLabels)).toEqual([
      "new",
      "verification",
      "waiting_for_customer",
      "partially_ignored",
      "ignored",
      "resolved",
    ])
  })
})

describe("kanały v1", () => {
  it("udostępnia wyłącznie Slack, Microsoft Teams i Telegram", () => {
    expect(Object.keys(channelLabels)).toEqual(["slack", "teams", "telegram"])
  })
})

describe("kontrakt autoryzacji aplikacji", () => {
  it("udostępnia wyłącznie zamrożone role i permissions", () => {
    expect(administrationRoles).toEqual(["USER", "ADMIN"])
    expect(administrationPermissions).toEqual([
      "manage_users",
      "manage_integrations",
      "manage_sla",
      "manage_schedule",
      "manage_notifications",
      "view_global_statistics",
      "reassign_cases",
      "force_resolve",
      "view_audit",
    ])
  })

  it("wymaga jednocześnie roli ADMIN i odpowiedniego permission", () => {
    expect(
      hasAdministrationPermission(
        { role: "USER", permissions: ["manage_users"] },
        "manage_users",
      ),
    ).toBe(false)
    expect(
      hasAdministrationPermission(
        { role: "ADMIN", permissions: [] },
        "manage_users",
      ),
    ).toBe(false)
    expect(
      hasAdministrationPermission(
        { role: "ADMIN", permissions: ["manage_users"] },
        "manage_users",
      ),
    ).toBe(true)
  })
})

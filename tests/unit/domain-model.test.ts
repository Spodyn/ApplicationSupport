import { describe, expect, it } from "vitest"
import {
  administrationPermissions,
  administrationRoles,
  hasAdministrationPermission,
} from "@/lib/domain/administration"
import {
  inboxOwnershipByStatus,
  inboxStatuses,
  inboxStateTransitionTargets,
  terminalInboxStatuses,
} from "@/lib/domain/inbox"
import { channelLabels } from "@/lib/domain/labels"
import { mockInboxCaseRecords } from "@/mocks/inbox"

describe("kanoniczny workflow inbox", () => {
  it("udostępnia wyłącznie uzgodnione statusy frontendu", () => {
    expect(inboxStatuses).toEqual([
      "new",
      "verification",
      "waiting_for_customer",
      "partially_ignored",
      "ignored",
      "resolved",
    ])
  })

  it("utrwala pełną macierz dozwolonych przejść", () => {
    expect(inboxStateTransitionTargets).toEqual({
      new: ["verification", "partially_ignored", "ignored", "resolved"],
      verification: ["waiting_for_customer", "resolved"],
      waiting_for_customer: ["new", "resolved"],
      partially_ignored: ["new", "verification", "ignored", "resolved"],
      ignored: [],
      resolved: [],
    })
  })

  it("utrwala stany terminalne i invariants ownership", () => {
    expect(terminalInboxStatuses).toEqual(["ignored", "resolved"])
    expect(inboxOwnershipByStatus).toEqual({
      new: "unassigned",
      verification: "required",
      waiting_for_customer: "unassigned",
      partially_ignored: "unassigned",
      ignored: "unassigned",
      resolved: "preserve_if_assigned",
    })
  })

  it("utrzymuje ownership fixture’ów zgodny ze stanem", () => {
    const invalidRecords = mockInboxCaseRecords
      .filter((record) => {
        const requirement = inboxOwnershipByStatus[record.status]
        if (requirement === "required") return !record.owner
        if (requirement === "unassigned") return Boolean(record.owner)
        return false
      })
      .map((record) => record.reference)

    expect(invalidRecords).toEqual([])
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

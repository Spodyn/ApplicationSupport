import { describe, expect, it } from "vitest"
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

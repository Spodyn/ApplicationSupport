import { describe, expect, it } from "vitest"
import { inboxStatusLabels } from "@/lib/domain/inbox"

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

import { describe, expect, it } from "vitest"
import { getEffectiveSlaState } from "@/lib/sla"

describe("getEffectiveSlaState", () => {
  const now = Date.parse("2026-08-09T10:00:00.000Z")

  it("oznacza termin, który minął, jako przekroczony", () => {
    expect(
      getEffectiveSlaState(
        { state: "at_risk", dueAt: "2026-08-09T09:59:59.000Z" },
        now,
      ),
    ).toBe("breached")
  })

  it("oznacza termin równy bieżącej chwili jako przekroczony", () => {
    expect(
      getEffectiveSlaState(
        { state: "on_track", dueAt: "2026-08-09T10:00:00.000Z" },
        now,
      ),
    ).toBe("breached")
  })

  it("nie nadpisuje wstrzymanego SLA nawet po terminie", () => {
    expect(
      getEffectiveSlaState(
        { state: "paused", dueAt: "2026-08-09T09:00:00.000Z" },
        now,
      ),
    ).toBe("paused")
  })

  it("zachowuje stan, gdy termin jeszcze nie minął", () => {
    expect(
      getEffectiveSlaState(
        { state: "at_risk", dueAt: "2026-08-09T10:15:00.000Z" },
        now,
      ),
    ).toBe("at_risk")
  })
})

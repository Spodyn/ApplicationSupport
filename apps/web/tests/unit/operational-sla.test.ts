import { describe, expect, it } from "vitest"
import { formatDeadlineDuration, operationalClock, type OperationalDeadline } from "@/lib/domain/operational-sla"

const instant = (value: string) => Date.parse(value)

function fixture(): OperationalDeadline {
  return {
    id: "response", kind: "NRT", state: "ON_TRACK", receivedAt: 300,
    observedAt: instant("2026-03-27T15:59:58Z"),
    clockValidUntil: instant("2026-03-30T08:30:00Z"),
    warningAt: instant("2026-03-30T07:00:00Z"),
    dueAt: instant("2026-03-30T07:00:02Z"),
    remainingMillis: 4_000,
    businessIntervals: [
      { start: instant("2026-03-27T15:59:58Z"), end: instant("2026-03-27T16:00:00Z") },
      { start: instant("2026-03-30T07:00:00Z"), end: instant("2026-03-30T08:30:00Z") },
    ],
  }
}

describe("operational countdown", () => {
  it("uses server windows across a weekend and time-zone offset change", () => {
    const deadline = fixture()
    expect(operationalClock(deadline, 100)).toEqual({ seconds: 4, overdue: false, warning: false })
    expect(operationalClock(deadline, 1_300).seconds).toBe(3)
    const weekend = instant("2026-03-29T10:00:00Z") - deadline.observedAt + deadline.receivedAt
    expect(operationalClock(deadline, weekend)).toEqual({ seconds: 2, overdue: false, warning: false })
    const monday = deadline.warningAt! - deadline.observedAt + deadline.receivedAt
    expect(operationalClock(deadline, monday)).toEqual({ seconds: 2, overdue: false, warning: true })
    expect(operationalClock(deadline, monday + 3_000)).toEqual({ seconds: 1, overdue: true, warning: false })
  })

  it("renders elapsed follow-up time and clamps a stale business calendar to the server horizon", () => {
    const deadline = fixture()
    expect(operationalClock({ ...deadline, kind: "FOLLOW_UP", businessIntervals: null,
      dueAt: deadline.observedAt + 5_000, warningAt: null, remainingMillis: 5_000 },
    deadline.receivedAt + 7_000)).toEqual({ seconds: 2, overdue: true, warning: false })
    expect(operationalClock(deadline, Number.MAX_SAFE_INTEGER))
      .toEqual(operationalClock(deadline, deadline.clockValidUntil - deadline.observedAt + deadline.receivedAt))
    expect(formatDeadlineDuration(3_662, "en")).toBe("1 hr 1 min 2 sec")
    expect(formatDeadlineDuration(3_662, "pl")).toMatch(/1.*1.*2/)
  })
})

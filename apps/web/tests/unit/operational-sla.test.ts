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
  it.each(["FRT", "NRT"] as const)("keeps overdue %s running through a closed weekend and after refetch", (kind) => {
    const dueAt = instant("2026-03-27T16:00:00Z")
    const observedAt = dueAt - 2_000
    const deadline: OperationalDeadline = { ...fixture(), kind, observedAt, dueAt,
      receivedAt: 0, remainingMillis: 2_000, warningAt: observedAt,
      clockValidUntil: dueAt + 86_400_000,
      businessIntervals: [{ start: observedAt, end: dueAt }],
    }
    expect(operationalClock(deadline, 1_000)).toEqual({ seconds: 1, overdue: false, warning: true })
    expect(operationalClock(deadline, 2_000)).toEqual({ seconds: 0, overdue: true, warning: false })
    expect(operationalClock(deadline, 4_000)).toEqual({ seconds: 2, overdue: true, warning: false })
    // The business calendar horizon cannot stop an already expired deadline.
    const sunday = instant("2026-03-29T10:00:00Z")
    expect(operationalClock(deadline, sunday - observedAt).seconds).toBe(42 * 3_600)
    const refreshed = { ...deadline, observedAt: sunday, receivedAt: 900,
      remainingMillis: -(sunday - dueAt), businessIntervals: [] }
    expect(operationalClock(refreshed, 3_900).seconds).toBe(42 * 3_600 + 3)
  })

  it.each([["FRT", 15], ["NRT", 30]] as const)("runs %s in business hours and warns at exactly 25%% remaining", (kind, minutes) => {
    const observedAt = instant("2026-11-23T11:55:00Z")
    const opens = instant("2026-11-23T13:00:00Z")
    const businessMillis = minutes * 60_000
    const warningAt = opens + businessMillis * 3 / 4 - 300_000
    const dueAt = opens + businessMillis - 300_000
    const deadline: OperationalDeadline = {
      ...fixture(), kind, observedAt, receivedAt: 0, clockValidUntil: dueAt + 60_000,
      warningAt, dueAt, remainingMillis: businessMillis,
      businessIntervals: [
        { start: observedAt, end: observedAt + 300_000 },
        { start: opens, end: dueAt + 60_000 },
      ],
    }
    const clock = (at: number) => operationalClock(deadline, at - observedAt)
    expect(clock(observedAt + 2_000).seconds).toBe(minutes * 60 - 2)
    expect(clock(observedAt + 320_000)).toEqual(clock(opens - 2_000))
    expect(clock(opens + 2_000).seconds).toBe(minutes * 60 - 302)
    expect(clock(warningAt - 1).warning).toBe(false)
    expect(clock(warningAt)).toEqual({ seconds: minutes * 15, overdue: false, warning: true })
    expect(clock(dueAt)).toEqual({ seconds: 0, overdue: true, warning: false })
    expect(clock(dueAt + 2_000)).toEqual({ seconds: 2, overdue: true, warning: false })
  })

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

  it("renders elapsed follow-up time and clamps an unexpired business calendar to the server horizon", () => {
    const deadline = fixture()
    expect(operationalClock({ ...deadline, kind: "FOLLOW_UP", businessIntervals: null,
      dueAt: deadline.observedAt + 5_000, warningAt: null, remainingMillis: 5_000 },
    deadline.receivedAt + 7_000)).toEqual({ seconds: 2, overdue: true, warning: false })
    expect(operationalClock({ ...deadline, kind: "FOLLOW_UP", businessIntervals: null,
      dueAt: deadline.observedAt + 5_000, warningAt: null, remainingMillis: 5_000 },
    deadline.receivedAt + 2_000)).toEqual({ seconds: 3, overdue: false, warning: false })
    const beforeDue = { ...deadline, dueAt: deadline.clockValidUntil + 60_000, remainingMillis: 2_000 + 91 * 60_000 }
    const horizon = deadline.clockValidUntil - deadline.observedAt + deadline.receivedAt
    expect(operationalClock(beforeDue, horizon + 30_000)).toEqual(operationalClock(beforeDue, horizon))
    expect(operationalClock(beforeDue, horizon)).toEqual({ seconds: 60, overdue: false, warning: true })
    expect(formatDeadlineDuration(3_662, "en")).toBe("1 hr 1 min 2 sec")
    expect(formatDeadlineDuration(3_662, "pl")).toMatch(/1.*1.*2/)
  })

  it.each([[0, "0 sec"], [59, "59 sec"], [60, "1 min 0 sec"], [3_600, "1 hr 0 min 0 sec"]] as const)(
    "formats the %s-second boundary without losing seconds", (seconds, expected) => {
      expect(formatDeadlineDuration(seconds, "en")).toBe(expected)
    })
})

import { describe, expect, it } from "vitest"

import type { WorkScheduleSettings } from "@/lib/domain/administration"
import {
  mapApiBusinessHours,
  mapWorkScheduleToApiIntervals,
  type ApiBusinessHoursSchedule,
} from "@/lib/services/api/administration-settings-adapter"

describe("business-hours administration adapter", () => {
  it("maps two backend intervals into the existing break editor without losing wall-clock semantics", () => {
    const apiSchedule: ApiBusinessHoursSchedule = {
      id: "00000000-0000-0000-0000-000000000001",
      timezone: "Europe/Warsaw",
      active: true,
      intervals: [
        { dayOfWeek: 1, start: "08:00", end: "12:30" },
        { dayOfWeek: 1, start: "13:00", end: "17:00" },
        { dayOfWeek: 5, start: "09:00", end: "15:00" },
      ],
      updatedBy: "admin@example.com",
      updatedAt: "2026-08-29T07:00:00Z",
    }

    const mapped = mapApiBusinessHours(apiSchedule, [])

    expect(mapped.timezone).toBe("Europe/Warsaw")
    expect(mapped.days[0]).toMatchObject({
      key: "mon",
      enabled: true,
      start: "08:00",
      end: "17:00",
      breakEnabled: true,
      breakStart: "12:30",
      breakEnd: "13:00",
    })
    expect(mapped.days[1].enabled).toBe(false)
    expect(mapped.days[4]).toMatchObject({
      enabled: true,
      start: "09:00",
      end: "15:00",
      breakEnabled: false,
    })
  })

  it("converts the current editor shape back into disjoint backend intervals", () => {
    const schedule: WorkScheduleSettings = {
      timezone: "UTC",
      exceptions: [],
      days: [
        { key: "mon", label: "Poniedziałek", enabled: true, start: "08:00", end: "17:00", breakEnabled: true, breakStart: "12:00", breakEnd: "13:00" },
        { key: "tue", label: "Wtorek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "wed", label: "Środa", enabled: true, start: "10:00", end: "16:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "thu", label: "Czwartek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "fri", label: "Piątek", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "sat", label: "Sobota", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
        { key: "sun", label: "Niedziela", enabled: false, start: "09:00", end: "17:00", breakEnabled: false, breakStart: "12:00", breakEnd: "13:00" },
      ],
    }

    expect(mapWorkScheduleToApiIntervals(schedule)).toEqual([
      { dayOfWeek: 1, start: "08:00", end: "12:00" },
      { dayOfWeek: 1, start: "13:00", end: "17:00" },
      { dayOfWeek: 3, start: "10:00", end: "16:00" },
    ])
  })

  it("fails loudly rather than collapsing a schedule the current editor cannot represent", () => {
    const apiSchedule: ApiBusinessHoursSchedule = {
      id: "00000000-0000-0000-0000-000000000001",
      timezone: "UTC",
      active: true,
      intervals: [
        { dayOfWeek: 1, start: "08:00", end: "09:00" },
        { dayOfWeek: 1, start: "10:00", end: "11:00" },
        { dayOfWeek: 1, start: "12:00", end: "13:00" },
      ],
      updatedBy: "admin@example.com",
      updatedAt: "2026-08-29T07:00:00Z",
    }

    expect(() => mapApiBusinessHours(apiSchedule)).toThrow(/maksymalnie dwa/)
  })
})
